import com.google.gson.JsonObject;
import fr.euphyllia.fidorial.server.VersionConstants;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

/**
 * Postbuild hook of fidorial-server: writes the distributable server jar, {@code dist/Fidorial-<version>.jar}:
 * the launcher classes (fidorial-bootstrap) at its root and, under {@code META-INF/fidorial/}, what the
 * launcher needs to rebuild the runtime classpath at boot.
 *
 * <ul>
 *   <li>artifacts with Maven coordinates go into {@code libraries.list} and are downloaded at runtime;</li>
 *   <li>everything else (Fidorial's own modules, the patched libraries) and every snapshot dependency
 *       is copied next to the lists and ships inside the launcher jar ({@code bundled.list}).</li>
 * </ul>
 *
 * <p>Both lists carry a SHA-256 so the runtime can verify what it reads back. No {@code repositories.list}
 * is written, so the launcher uses the mirror chain in {@code Repositories.DEFAULTS}.
 *
 * <p>Snapshots are bundled rather than downloaded: a -SNAPSHOT is stored remotely under a timestamped
 * name only maven-metadata.xml knows about, and a released jar must not depend on a third-party snapshot
 * surviving that repository's cleanup policy.
 */
public final class BootstrapJar {

    /** Reproducible entries, like the jars jpm writes. */
    private static final FileTime EPOCH = FileTime.from(Instant.parse("1980-02-01T00:00:00Z"));

    public static void main(final String[] args) throws Exception {
        final Path project = Path.of(System.getenv("JPM_PROJECT_DIR"));
        final Path module = Path.of(System.getenv("JPM_MODULE_DIR"));
        final String version = System.getenv("JPM_VERSION");
        final JsonObject info = JpmInfo.read();
        final JsonObject server = JpmInfo.module(info, "fidorial-server");

        final Map<String, Path> bundled = new LinkedHashMap<>();
        final Map<String, Path> external = new TreeMap<>();
        final List<String> snapshots = new ArrayList<>();

        // The server's own jar goes first on the classpath, so a patched dependency always wins over
        // whatever a repository might serve under the same package name.
        final Path serverJar = Path.of(server.get("jar").getAsString());
        bundled.put(serverJar.getFileName().toString(), serverJar);

        final Map<String, Path> others = new TreeMap<>();
        // dev dependencies are never shipped
        for (final JsonObject d : JpmInfo.runtimeAndCompile(server).values()) {
            final String id = d.get("id").getAsString();
            for (final Path jar : JpmInfo.jars(d)) {
                if (!Files.isRegularFile(jar)) {
                    throw new IllegalStateException(jar + " (" + id + ") does not exist; build it first");
                }
                // workspace modules and patched libraries have no coordinates a repository could serve
                final Optional<String> coordinates = JpmInfo.coordinates(d);
                if (coordinates.isEmpty()) {
                    others.put(jar.getFileName().toString(), jar);
                } else if (coordinates.get().split(":")[2].endsWith("-SNAPSHOT")) {
                    snapshots.add(coordinates.get());
                    others.put(jar.getFileName().toString(), jar);
                } else {
                    final Path previous = external.put(coordinates.get(), jar);
                    if (previous != null && !previous.equals(jar)) {
                        throw new IllegalStateException("two files resolved for " + coordinates.get() + ": "
                                + previous + " and " + jar);
                    }
                }
            }
        }
        for (final Map.Entry<String, Path> e : others.entrySet()) {
            final Path previous = bundled.putIfAbsent(e.getKey(), e.getValue());
            if (previous != null && !previous.equals(e.getValue())) {
                throw new IllegalStateException("two bundled jars are named " + e.getKey() + " (" + previous + " and "
                        + e.getValue() + "); rename one, the launcher unpacks them into a single flat directory");
            }
        }

        final StringBuilder bundledList = new StringBuilder(LibraryLists.header(
                "Jars shipped inside the launcher, unpacked into libraries/.fidorial/.", "BootstrapJar.java"));
        for (final Map.Entry<String, Path> e : bundled.entrySet()) {
            bundledList.append(e.getKey()).append(' ').append(LibraryLists.sha256(e.getValue())).append(' ')
                    .append(Files.size(e.getValue())).append('\n');
        }
        final StringBuilder librariesList = new StringBuilder(LibraryLists.header(
                "Downloaded at boot. Format: group:artifact:version[:classifier] sha256 size", "BootstrapJar.java"));
        long externalBytes = 0;
        for (final Map.Entry<String, Path> e : external.entrySet()) {
            librariesList.append(e.getKey()).append(' ').append(LibraryLists.sha256(e.getValue())).append(' ')
                    .append(Files.size(e.getValue())).append('\n');
            externalBytes += Files.size(e.getValue());
        }

        final String serverVersion = VersionConstants.SERVER_VERSION;
        final Path launcher = project.resolve("fidorial-bootstrap/dist/fidorial-bootstrap-" + version + ".jar");
        final Path out = module.resolve("dist").resolve("Fidorial-" + serverVersion + ".jar");
        final Path tmp = out.resolveSibling(out.getFileName() + ".tmp");

        final Manifest manifest = new Manifest();
        final Attributes main = manifest.getMainAttributes();
        main.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        main.put(Attributes.Name.MAIN_CLASS, "fr.euphyllia.fidorial.bootstrap.Main");
        main.putValue("Enable-Native-Access", "ALL-UNNAMED");
        main.put(Attributes.Name.IMPLEMENTATION_TITLE, "Fidorial");
        main.put(Attributes.Name.IMPLEMENTATION_VERSION, serverVersion);

        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(tmp))) {
            put(jar, JarFile.MANIFEST_NAME, manifest::write);
            try (JarFile classes = new JarFile(launcher.toFile())) {
                for (final JarEntry e : classes.stream().toList()) {
                    if (e.isDirectory() || e.getName().equals(JarFile.MANIFEST_NAME)) {
                        continue;
                    }
                    try (InputStream in = classes.getInputStream(e)) {
                        put(jar, e.getName(), in::transferTo);
                    }
                }
            }
            for (final Map.Entry<String, Path> e : bundled.entrySet()) {
                put(jar, "META-INF/fidorial/" + e.getKey(), o -> Files.copy(e.getValue(), o));
            }
            put(jar, "META-INF/fidorial/bundled.list", o -> o.write(bundledList.toString().getBytes(StandardCharsets.UTF_8)));
            put(jar, "META-INF/fidorial/libraries.list", o -> o.write(librariesList.toString().getBytes(StandardCharsets.UTF_8)));
        }
        Files.move(tmp, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        if (!snapshots.isEmpty()) {
            System.out.println("    warning: bundled " + snapshots.size() + " snapshot dependenc(y/ies) instead of "
                    + "downloading them: " + String.join(", ", snapshots.stream().sorted().toList())
                    + ". Pin them to a release version to shrink the jar further.");
        }
        System.out.println("    Bootstrap payload: " + bundled.size() + " bundled jar(s), " + external.size()
                + " downloaded librar(y/ies) (" + externalBytes / (1024 * 1024) + " MiB kept out of the release jar)");
        System.out.println("    Packaged " + project.relativize(out));
    }

    private interface Writer {
        void write(OutputStream out) throws IOException;
    }

    private static void put(final JarOutputStream jar, final String name, final Writer writer) throws IOException {
        final ZipEntry entry = new ZipEntry(name);
        entry.setLastModifiedTime(EPOCH);
        jar.putNextEntry(entry);
        writer.write(jar);
        jar.closeEntry();
    }

    private BootstrapJar() {
    }
}
