package fr.fidorial.registrygen;

import java.nio.file.Path;
import java.util.List;

/**
 * Regenerates the registry sources and datasets of the modules given as arguments (their directories),
 * from the {@code "registry-generator"} settings in their {@code project.jsonc}. From the workspace root:
 * {@code jpm run generate-registries}, which runs it for fidorial-api and fidorial-server.
 */
public final class Main {

    private Main() {
    }

    public static void main(final String[] args) throws Exception {
        final List<String> modules = args.length > 0 ? List.of(args) : List.of("../fidorial-api", "../fidorial-server");
        for (final String module : modules) {
            new Pipeline(Settings.read(Path.of(module).toAbsolutePath().normalize())).run();
        }
    }
}
