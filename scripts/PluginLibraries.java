import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
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
 * declared as {@code dev} dependencies. Their graph is flattened from {@code project.lock} (compile and
 * runtime edges, honouring exclusions) and checksummed here, at build time, so the server never needs a
 * Maven resolver at runtime — and so a plugin's dependency set is the same on every machine that loads it.
 *
 * <p>Plain JDK only: a plugin's runtime classpath is usually empty, so no JSON library is at hand.
 * {@code project.lock} keeps one package per line, which is what is parsed.
 */
public final class PluginLibraries {

    private static final Pattern PACKAGE = Pattern.compile(
            "^\\s*\"([^\"]+)\":\\s*\\{\\s*\"version\":\\s*\"([^\"]+)\".*?(?:\"dependencies\":\\s*\\{(.*)\\})?\\s*\\},?\\s*$");
    private static final Pattern EDGE = Pattern.compile("\"([^\"]+)\":\\s*\"([^\"]*)\"");

    private record Pkg(String version, Map<String, String> edges) {
    }

    public static void main(final String[] args) throws Exception {
        final Path project = Path.of(System.getenv("JPM_PROJECT_DIR"));
        final Path module = Path.of(System.getenv("JPM_MODULE_DIR"));
        final String manifest = Files.readString(Path.of(System.getenv("JPM_MANIFEST")));

        final List<String> roots = new ArrayList<>();
        final Matcher list = Pattern.compile("\"fidorial\"\\s*:\\s*\\{[^}]*?\"libraries\"\\s*:\\s*\\[([^\\]]*)\\]", Pattern.DOTALL)
                .matcher(manifest.replaceAll("//[^\n]*", ""));
        if (list.find()) {
            final Matcher key = Pattern.compile("\"([^\"]+)\"").matcher(list.group(1));
            while (key.find()) {
                roots.add(key.group(1));
            }
        }

        final Map<String, Pkg> packages = new HashMap<>();
        for (final String line : Files.readAllLines(project.resolve("project.lock"))) {
            final Matcher m = PACKAGE.matcher(line);
            if (!m.matches()) {
                continue;
            }
            final Map<String, String> edges = new HashMap<>();
            if (m.group(3) != null) {
                final Matcher e = EDGE.matcher(m.group(3));
                while (e.find()) {
                    edges.put(e.group(1), e.group(2));
                }
            }
            packages.put(m.group(1), new Pkg(m.group(2), edges));
        }

        // breadth-first over compile and runtime edges; "!group:artifact" (or "*:artifact") in an edge excludes
        // that package from everything reached through it, as in Maven
        record Visit(String id, Set<String> excludes) {
        }
        final Set<String> closure = new LinkedHashSet<>();
        final Deque<Visit> queue = new ArrayDeque<>();
        for (final String root : roots) {
            if (!packages.containsKey(root)) {
                throw new IllegalStateException(root + " is listed as a library but is not in project.lock; "
                        + "declare it as a dev dependency and run jpm install");
            }
            queue.add(new Visit(root, Set.of()));
        }
        while (!queue.isEmpty()) {
            final Visit v = queue.poll();
            if (!closure.add(v.id())) {
                continue;
            }
            for (final Map.Entry<String, String> edge : packages.get(v.id()).edges().entrySet()) {
                final String[] parts = edge.getValue().trim().split("\\s+");
                if (!parts[0].equals("compile") && !parts[0].equals("runtime")) {
                    continue;
                }
                if (excluded(edge.getKey(), v.excludes())) {
                    continue;
                }
                final Set<String> excludes = new LinkedHashSet<>(v.excludes());
                for (int i = 1; i < parts.length; i++) {
                    if (parts[i].startsWith("!")) {
                        excludes.add(parts[i].substring(1));
                    }
                }
                if (packages.containsKey(edge.getKey())) {
                    queue.add(new Visit(edge.getKey(), excludes));
                }
            }
        }

        final Set<String> lines = new TreeSet<>();
        final Path repository = LibraryLists.jpmHome().resolve("repository");
        for (final String id : closure) {
            final String[] ga = id.split(":");
            if (ga.length != 2) {
                throw new IllegalStateException(id + " has a classifier or is not a Maven package; "
                        + "the server can only download plain Maven jars");
            }
            final String version = packages.get(id).version();
            final Path jar = repository.resolve(ga[0].replace('.', '/')).resolve(ga[1]).resolve(version)
                    .resolve(ga[1] + "-" + version + ".jar");
            if (!Files.isRegularFile(jar)) {
                throw new IllegalStateException(jar + " is missing; run jpm install");
            }
            lines.add(id + ":" + version + " " + LibraryLists.sha256(jar) + " " + Files.size(jar));
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

    private static boolean excluded(final String id, final Set<String> excludes) {
        final String[] ga = id.split(":");
        for (final String x : excludes) {
            final String[] p = x.split(":");
            if (p.length == 2 && (p[0].equals("*") || p[0].equals(ga[0])) && (p[1].equals("*") || p[1].equals(ga[1]))) {
                return true;
            }
        }
        return false;
    }

    private PluginLibraries() {
    }
}
