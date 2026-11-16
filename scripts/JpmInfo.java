import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads {@code jpm info --json --ide}, jpm's documented, stable description of the workspace. Needs Gson,
 * so only scripts of modules that have it at runtime (fidorial-server) use it.
 */
final class JpmInfo {

    private JpmInfo() {
    }

    /** {@code jpm info --json --ide}: the documented, stable description of the workspace. */
    static JsonObject read() throws IOException, InterruptedException {
        return read(Path.of(System.getenv("JPM_PROJECT_DIR")));
    }

    static JsonObject read(final Path project) throws IOException, InterruptedException {
        final String jpm = System.getenv().getOrDefault("JPM_BIN", "jpm");
        final Process p = new ProcessBuilder(jpm, "info", "--json", "--ide")
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

    /** The jars of a module's dependencies with the given scopes (compile, runtime, provided). */
    static List<Path> dependencyJars(final JsonObject module, final List<String> scopes) {
        final List<Path> jars = new ArrayList<>();
        final JsonArray deps = module.getAsJsonArray("ideDependencies");
        for (final JsonElement e : deps) {
            final JsonObject d = e.getAsJsonObject();
            if (!scopes.contains(d.get("scope").getAsString()) || !d.has("jars")) {
                continue;
            }
            for (final JsonElement jar : d.getAsJsonArray("jars")) {
                jars.add(Path.of(jar.getAsString()));
            }
        }
        return jars;
    }
}
