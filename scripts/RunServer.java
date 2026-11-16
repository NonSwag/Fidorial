import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * {@code jpm run server} in fidorial-server: spins up a test server in {@code fidorial-server/run} without
 * assembling the release jar, with the test plugin installed. Arguments are passed to the server.
 *
 * <p>Like the release launcher, it runs the server on the class path.
 */
public final class RunServer {

    public static void main(final String[] args) throws Exception {
        final Path module = Path.of(System.getenv("JPM_MODULE_DIR"));
        final Path workDir = module.resolve("run");
        Files.createDirectories(workDir.resolve("plugins"));
        installTestPlugin(workDir);
        System.exit(launch(workDir, "fr.euphyllia.fidorial.server.Main", List.of(
                "-DFidorial.logLevel=TRACE",
                "-XX:+UnlockDiagnosticVMOptions",
                "-XX:+DebugNonSafepoints"), List.of(args)));
    }

    /** Builds the test plugin and the server, then copies the plugin to {@code <workDir>/plugins/TestPlugin.jar}. */
    static void installTestPlugin(final Path workDir) throws Exception {
        final Path project = Path.of(System.getenv("JPM_PROJECT_DIR"));
        final int built = new ProcessBuilder(System.getenv().getOrDefault("JPM_EXE", "jpm"), "build",
                "fidorial-test-plugin", "fidorial-server")
                .directory(project.toFile()).inheritIO().start().waitFor();
        if (built != 0) {
            System.exit(built);
        }
        final Path jar = project.resolve("fidorial-test-plugin/dist/fidorial-test-plugin-" + System.getenv("JPM_VERSION") + ".jar");
        Files.createDirectories(workDir.resolve("plugins"));
        Files.copy(jar, workDir.resolve("plugins/TestPlugin.jar"), StandardCopyOption.REPLACE_EXISTING);
    }

    static int launch(final Path workDir, final String mainClass, final List<String> jvmArgs, final List<String> args)
            throws Exception {
        final String javaHome = System.getenv().getOrDefault("JPM_JAVA_HOME", System.getProperty("java.home"));
        final List<String> command = new ArrayList<>();
        command.add(Path.of(javaHome, "bin", "java").toString());
        command.add("--enable-native-access=ALL-UNNAMED");
        command.addAll(jvmArgs);
        command.add("-cp");
        command.add(classpath());
        command.add(mainClass);
        command.addAll(args);
        return new ProcessBuilder(command).directory(workDir.toFile()).inheritIO().start().waitFor();
    }

    /** The server's runtime classpath: its classes and resources, then its dependencies. */
    private static String classpath() throws Exception {
        final List<String> entries = new ArrayList<>();
        // the module's jar holds both classes and resources (api-packages.list included)
        final Path jar = Path.of(System.getenv("JPM_MODULE_DIR"), "dist",
                System.getenv("JPM_NAME") + "-" + System.getenv("JPM_VERSION") + ".jar");
        entries.add(jar.toString());
        final String classes = System.getenv("JPM_CLASSES_DIR");
        for (final String entry : System.getenv("JPM_CLASSPATH").split(File.pathSeparator)) {
            if (!entry.isEmpty() && !entry.equals(classes)) {
                entries.add(entry);
            }
        }
        return String.join(File.pathSeparator, entries);
    }

    static void deleteTree(final Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (final Path p : walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                Files.delete(p);
            }
        }
    }

    private RunServer() {
    }
}
