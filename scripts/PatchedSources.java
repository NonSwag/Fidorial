import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Prebuild hook of a "patched library" module, such as fidorial-brigadier: writes the module's sources
 * as a library's sources jar with Fidorial's patches applied.
 *
 * <p>jpm's own {@code "patches"} recompiles the changed classes against the library's dependencies
 * only, so a patch cannot use another library (brigadier's patches use Adventure). A module of its own
 * can: it declares those as {@code dev} dependencies, and this hook supplies its sources.
 *
 * <p>Every {@code patches/<group>_<artifact>.patch} in the module is applied to the sources of
 * {@code <group>:<artifact>}, whose version is the one the module's {@code project.jsonc} declares for it.
 * The output, {@code src/main/java} and {@code src/main/resources}, is generated: it is git-ignored and
 * rewritten whenever a patch or the version changes. To change a patch, edit the generated sources,
 * then run {@code jpm run diff} in the module and move the result into {@code patches/}.
 */
public final class PatchedSources {

    private static final List<String> REPOSITORIES = List.of(
            "https://repo.maven.apache.org/maven2/",
            "https://libraries.minecraft.net/",
            "https://repo.papermc.io/repository/maven-public/");

    public static void main(final String[] args) throws Exception {
        final Path module = Path.of(System.getenv().getOrDefault("JPM_MODULE_DIR", ".")).toAbsolutePath();
        final String manifest = Files.readString(module.resolve("project.jsonc"));
        final boolean diff = args.length > 0 && args[0].equals("diff");

        final List<Path> patches;
        try (Stream<Path> files = Files.list(module.resolve("patches"))) {
            patches = files.filter(f -> f.getFileName().toString().endsWith(".patch")).sorted().toList();
        }

        for (final Path patch : patches) {
            final String stem = patch.getFileName().toString().replaceFirst("\\.patch$", "");
            final int sep = stem.indexOf('_');
            if (sep < 0) {
                throw new IllegalStateException(patch + ": expected the name <group>_<artifact>.patch");
            }
            final String group = stem.substring(0, sep);
            final String artifact = stem.substring(sep + 1);
            final String version = versionOf(manifest, group + ":" + artifact, module);
            if (diff) {
                diff(module, group, artifact, version);
            } else {
                generate(module, patch, group, artifact, version);
            }
        }
    }

    private static void generate(final Path module, final Path patch, final String group, final String artifact,
                                 final String version) throws Exception {
        final Path java = module.resolve("src/main/java");
        final Path resources = module.resolve("src/main/resources");
        final Path stamp = module.resolve(".cache/patched-sources.stamp");
        final String key = group + ":" + artifact + ":" + version + " " + sha256(Files.readAllBytes(patch));
        if (Files.isDirectory(java) && Files.exists(stamp) && Files.readString(stamp).equals(key)) {
            return;
        }

        final Path sources = sourcesJar(group, artifact, version);
        deleteTree(module.resolve("src"));
        Files.createDirectories(java);
        unpack(sources, java, resources);

        // git apply resolves paths from the top of the work tree it runs in, so point it at src/main/java.
        final Path top = Path.of(run(module, "git", "rev-parse", "--show-toplevel").trim());
        run(top, "git", "apply", "--whitespace=nowarn", "--directory=" + top.relativize(java).toString().replace('\\', '/'),
                patch.toAbsolutePath().toString());

        Files.createDirectories(stamp.getParent());
        Files.writeString(stamp, key);
        System.out.println("    Patched " + group + ":" + artifact + " " + version + " sources into "
                + module.relativize(java));
    }

    /** Prints the changes made to the generated sources as a new patch, for patches/. */
    private static void diff(final Path module, final String group, final String artifact, final String version)
            throws Exception {
        final Path pristine = Files.createTempDirectory("pristine-");
        try {
            final Path a = pristine.resolve("a");
            unpack(sourcesJar(group, artifact, version), a, pristine.resolve("unused"));
            final Path b = module.resolve("src/main/java");
            final ProcessBuilder pb = new ProcessBuilder("git", "diff", "--no-index", "--no-prefix", a.toString(), b.toString())
                    .redirectErrorStream(true);
            final String out = new String(pb.start().getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            // rewrite the absolute paths git prints into a/<path> and b/<path>
            System.out.print(out.replace(a + "/", "a/").replace(b + "/", "b/").replace(a.toString(), "a").replace(b.toString(), "b"));
        } finally {
            deleteTree(pristine);
        }
    }

    private static String versionOf(final String manifest, final String key, final Path module) {
        final Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(?:\\{[^}]*\"version\"\\s*:\\s*)?\"([^\"]+)\"")
                .matcher(manifest);
        if (!m.find()) {
            throw new IllegalStateException(module.resolve("project.jsonc") + " does not declare " + key);
        }
        return m.group(1);
    }

    private static Path sourcesJar(final String group, final String artifact, final String version) throws Exception {
        final String home = System.getenv("JPM_HOME");
        final Path repository = (home != null ? Path.of(home) : Path.of(System.getProperty("user.home"), ".jpm"))
                .resolve("repository");
        final String relative = group.replace('.', '/') + "/" + artifact + "/" + version + "/"
                + artifact + "-" + version + "-sources.jar";
        final Path jar = repository.resolve(relative);
        if (Files.exists(jar)) {
            return jar;
        }
        final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        for (final String base : REPOSITORIES) {
            final HttpResponse<InputStream> response = client.send(HttpRequest.newBuilder(URI.create(base + relative)).build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                if (response.statusCode() == 200) {
                    Files.createDirectories(jar.getParent());
                    final Path tmp = Files.createTempFile(jar.getParent(), "download", ".tmp");
                    Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                    Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                    return jar;
                }
            }
        }
        throw new IllegalStateException("no sources jar for " + group + ":" + artifact + ":" + version + " in " + REPOSITORIES);
    }

    private static void unpack(final Path jar, final Path java, final Path resources) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(jar))) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                final String name = e.getName();
                if (e.isDirectory() || name.startsWith("META-INF/")) {
                    continue;
                }
                final Path target = (name.endsWith(".java") ? java : resources).resolve(name).normalize();
                if (!target.startsWith(java) && !target.startsWith(resources)) {
                    throw new IOException("unsafe entry " + name + " in " + jar);
                }
                Files.createDirectories(target.getParent());
                Files.copy(zip, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static String run(final Path dir, final String... command) throws IOException, InterruptedException {
        final Process p = new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
        final String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new IllegalStateException(String.join(" ", command) + " failed:\n" + out);
        }
        return out;
    }

    private static void deleteTree(final Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        final List<Path> all = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.forEach(all::add);
        }
        for (int i = all.size() - 1; i >= 0; i--) {
            Files.delete(all.get(i));
        }
    }

    private static String sha256(final byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private PatchedSources() {
    }
}
