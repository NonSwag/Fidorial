//DEPS com.google.code.gson:gson:2.15.0
import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prebuild hook of a plugin module: writes {@code src/main/resources/META-INF/fidorial/libraries.list}
 * (git-ignored), the libraries the server downloads before loading the plugin (was Gradle's
 * {@code fidorialLibrary} configuration).
 *
 * <p>The libraries are the keys listed in the manifest under {@code "extensions": { "fidorial": { "libraries": [...] } }},
 * declared as {@code dev} dependencies. Their graph (compile and runtime edges, honouring exclusions) comes from
 * {@code jpm info --json --classpath} and is checksummed here, at build time, so the server never needs a Maven
 * resolver at runtime — and so a plugin's dependency set is the same on every machine that loads it.
 *
 * <p>A plugin's runtime classpath is usually empty, so Gson comes from the {@code //DEPS} line above.
 */
public final class PluginLibraries {

    public static void main(final String[] args) throws Exception {
        final Path module = Path.of(System.getenv("JPM_MODULE_DIR"));
        final String manifest = Files.readString(Path.of(System.getenv("JPM_MANIFEST")));

        final List<JpmInfo.Root> roots = new ArrayList<>();
        final Matcher list = Pattern.compile("\"fidorial\"\\s*:\\s*\\{[^}]*?\"libraries\"\\s*:\\s*\\[([^\\]]*)\\]", Pattern.DOTALL)
                .matcher(manifest.replaceAll("//[^\n]*", ""));
        if (list.find()) {
            final Matcher key = Pattern.compile("\"([^\"]+)\"").matcher(list.group(1));
            while (key.find()) {
                roots.add(new JpmInfo.Root(key.group(1), Set.of()));
            }
        }

        final JsonObject plugin = JpmInfo.module(JpmInfo.read(), System.getenv("JPM_NAME"));
        final Map<String, JsonObject> packages = JpmInfo.packages(plugin, "processor");
        for (final JpmInfo.Root root : roots) {
            if (!packages.containsKey(root.id())) {
                throw new IllegalStateException(root.id() + " is listed as a library but is not a dev dependency of "
                        + System.getenv("JPM_NAME") + "; declare it in \"dev\" and run jpm install");
            }
        }
        final List<String> closure = JpmInfo.closure(packages, roots, Set.of("compile", "runtime"));

        final Set<String> lines = new TreeSet<>();
        for (final String id : closure) {
            final JsonObject p = packages.get(id);
            final String coordinates = JpmInfo.coordinates(p).orElseThrow(() -> new IllegalStateException(id
                    + " is not a plain Maven package; the server can only download Maven jars"));
            if (p.has("classifier")) {
                throw new IllegalStateException(id + " has a classifier; the server can only download plain Maven jars");
            }
            final Path jar = JpmInfo.jars(p).getFirst();
            lines.add(coordinates + " " + LibraryLists.sha256(jar) + " " + Files.size(jar));
        }

        final Path out = module.resolve("src/main/resources/META-INF/fidorial/libraries.list");
        final String text = LibraryLists.header("Downloaded by the server before this plugin is loaded.", "PluginLibraries.java")
                + String.join("\n", lines) + "\n";
        Files.createDirectories(out.getParent());
        if (!Files.exists(out) || !Files.readString(out).equals(text)) {
            Files.writeString(out, text);
        }
        System.out.println("    Plugin library lock: " + lines.size() + " artifact(s)");
    }

    private PluginLibraries() {
    }
}
