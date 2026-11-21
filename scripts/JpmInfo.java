import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads {@code jpm info --json --classpath}, jpm's documented, stable description of the workspace: per module,
 * every classpath as packages with their Maven coordinates, jars and dependency edges. Needs Gson: scripts of
 * modules without it at runtime declare it with {@code //DEPS}.
 */
final class JpmInfo {

    private JpmInfo() {
    }

    /** {@code jpm info --json --classpath} of the project the script runs in. */
    static JsonObject read() throws IOException, InterruptedException {
        return read(Path.of(System.getenv("JPM_PROJECT_DIR")));
    }

    static JsonObject read(final Path project) throws IOException, InterruptedException {
        final String jpm = System.getenv("JPM_EXE");
        final Process p = new ProcessBuilder(jpm, "info", "--json", "--classpath")
                .directory(project.toFile())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        final String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new IllegalStateException("jpm info failed");
        }
        return JsonParser.parseString(out).getAsJsonObject();
    }

    static JsonObject module(final JsonObject info, final String name) {
        for (final JsonElement m : info.getAsJsonArray("modules")) {
            if (m.getAsJsonObject().get("name").getAsString().equals(name)) {
                return m.getAsJsonObject();
            }
        }
        throw new IllegalStateException("no module " + name);
    }

    /**
     * The packages on some of a module's classpaths ({@code compile}, {@code processor}, {@code runtime},
     * {@code testCompile}, {@code testRuntime}), by id, in classpath order.
     */
    static Map<String, JsonObject> packages(final JsonObject module, final String... classpaths) {
        final Map<String, JsonObject> out = new LinkedHashMap<>();
        final JsonObject all = module.getAsJsonObject("classpaths");
        for (final String cp : classpaths) {
            for (final JsonElement e : all.getAsJsonArray(cp)) {
                out.putIfAbsent(e.getAsJsonObject().get("id").getAsString(), e.getAsJsonObject());
            }
        }
        return out;
    }

    /** A root of a closure: a package and the exclusions declared on the dependency. */
    record Root(String id, Set<String> excludes) {
    }

    /** The module's declared dependencies of one scope (runtime, dev, test), with their exclusions. */
    static List<Root> roots(final JsonObject module, final String scope) {
        final List<Root> roots = new ArrayList<>();
        for (final JsonElement e : module.getAsJsonArray("dependencies")) {
            final JsonObject d = e.getAsJsonObject();
            if (d.get("scope").getAsString().equals(scope)) {
                roots.add(new Root(d.get("key").getAsString(), strings(d.getAsJsonArray("exclude"))));
            }
        }
        return roots;
    }

    /**
     * The packages reached from {@code roots} along edges of the given scopes (compile, runtime, provided), in
     * breadth-first order. An exclusion on an edge ({@code group:artifact}, {@code *} for either) applies to
     * everything reached through it, as in Maven and jpm.
     */
    static List<String> closure(final Map<String, JsonObject> pkgs, final List<Root> roots, final Set<String> follow) {
        record Visit(String id, Set<String> excludes) {
        }
        final Set<String> seen = new LinkedHashSet<>();
        // a package reached again with fewer exclusions is walked again, as jpm does
        final Map<String, List<Set<String>>> walked = new LinkedHashMap<>();
        final Deque<Visit> queue = new ArrayDeque<>();
        for (final Root r : roots) {
            queue.add(new Visit(r.id(), r.excludes()));
        }
        while (!queue.isEmpty()) {
            final Visit v = queue.poll();
            final JsonObject p = pkgs.get(v.id());
            if (p == null) {
                continue;
            }
            final List<Set<String>> prior = walked.computeIfAbsent(v.id(), k -> new ArrayList<>());
            if (prior.stream().anyMatch(v.excludes()::containsAll)) {
                continue;
            }
            prior.add(v.excludes());
            seen.add(v.id());
            for (final JsonElement e : p.getAsJsonArray("dependencies")) {
                final JsonObject edge = e.getAsJsonObject();
                final String target = edge.get("id").getAsString();
                if (!follow.contains(edge.get("scope").getAsString()) || excluded(target, v.excludes())) {
                    continue;
                }
                final Set<String> excludes = new LinkedHashSet<>(v.excludes());
                excludes.addAll(strings(edge.getAsJsonArray("exclude")));
                queue.add(new Visit(target, excludes));
            }
        }
        return new ArrayList<>(seen);
    }

    /** A package's files: its jars, or for a workspace module the jar it builds into. */
    static List<Path> jars(final JsonObject pkg) {
        final List<Path> out = new ArrayList<>();
        if (pkg.get("kind").getAsString().equals("workspace")) {
            out.add(Path.of(pkg.get("jar").getAsString()));
        } else {
            for (final JsonElement jar : pkg.getAsJsonArray("jars")) {
                out.add(Path.of(jar.getAsString()));
            }
        }
        return out;
    }

    /** {@code group:artifact:version[:classifier]} of a plain Maven package; empty for workspace, Git and patched ones. */
    static Optional<String> coordinates(final JsonObject pkg) {
        if (!pkg.get("kind").getAsString().equals("maven")) {
            return Optional.empty();
        }
        return Optional.of(pkg.get("group").getAsString() + ":" + pkg.get("artifact").getAsString() + ":"
                + pkg.get("version").getAsString() + (pkg.has("classifier") ? ":" + pkg.get("classifier").getAsString() : ""));
    }

    private static Set<String> strings(final JsonArray a) {
        final Set<String> out = new LinkedHashSet<>();
        if (a != null) {
            a.forEach(x -> out.add(x.getAsString()));
        }
        return out;
    }

    private static boolean excluded(final String id, final Set<String> excludes) {
        final String[] ga = id.split(":");
        if (ga.length < 2) {
            return false;
        }
        for (final String x : excludes) {
            final int c = x.indexOf(':');
            final String g = c < 0 ? x : x.substring(0, c);
            final String a = c < 0 ? "*" : x.substring(c + 1);
            if ((g.equals("*") || g.equals(ga[0])) && (a.equals("*") || a.equals(ga[1]))) {
                return true;
            }
        }
        return false;
    }
}
