import com.google.gson.JsonObject;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@code jpm run spotbugs} (in fidorial-build-tools, or from the root): SpotBugs over fidorial-server, with
 * the settings of the Gradle build (effort max, high-confidence bugs only), failing when it finds any.
 * The report is written to {@code fidorial-server/.cache/reports/spotbugs.html}.
 */
public final class SpotBugs {

    public static void main(final String[] args) throws Exception {
        final Path root = Path.of(System.getenv("JPM_PROJECT_DIR"));
        final String jpm = System.getenv("JPM_EXE");
        if (new ProcessBuilder(jpm, "build", "fidorial-server").directory(root.toFile()).inheritIO().start().waitFor() != 0) {
            System.exit(1);
        }

        final JsonObject info = JpmInfo.read(root);
        final JsonObject server = JpmInfo.module(info, "fidorial-server");

        // what the server compiles and runs against: compileClasspath + runtimeClasspath in Gradle's terms
        final Set<String> aux = new LinkedHashSet<>();
        for (final JsonObject p : JpmInfo.packages(server, "compile", "runtime", "testCompile", "testRuntime").values()) {
            JpmInfo.jars(p).forEach(j -> aux.add(j.toString()));
        }

        final Path report = root.resolve("fidorial-server/.cache/reports/spotbugs.html");
        Files.createDirectories(report.getParent());
        final Path auxFile = report.resolveSibling("spotbugs-auxclasspath.txt");
        Files.write(auxFile, aux);

        final List<String> command = new ArrayList<>(List.of(
                Path.of(System.getenv("JPM_JAVA_HOME"), "bin", "java").toString(),
                "-cp", System.getenv("JPM_CLASSPATH"),
                "edu.umd.cs.findbugs.FindBugs2",
                "-effort:max",
                "-high",
                "-progress",
                "-exitcode",
                "-html:fancy-hist.xsl=" + report,
                "-auxclasspathFromFile", auxFile.toString(),
                server.get("jar").getAsString()));
        final int code = new ProcessBuilder(command).directory(root.toFile()).inheritIO().start().waitFor();
        System.out.println("    SpotBugs report: " + root.relativize(report));
        if (code != 0) {
            System.err.println("error: SpotBugs found bugs (exit code " + code + "), see the report");
        }
        System.exit(code);
    }

    private SpotBugs() {
    }
}
