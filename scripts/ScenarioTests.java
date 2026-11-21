import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * {@code jpm run test-scenarios} in fidorial-server, also run after {@code jpm test} (posttest hook): runs
 * the scenario tests against a real server, with the test plugin loaded, in a fresh
 * {@code fidorial-server/.cache/scenario-tests}.
 */
public final class ScenarioTests {

    public static void main(final String[] args) throws Exception {
        final Path workDir = Path.of(System.getenv("JPM_MODULE_DIR"), ".cache", "scenario-tests");
        RunServer.deleteTree(workDir);
        Files.createDirectories(workDir);
        RunServer.installTestPlugin(workDir);
        System.exit(RunServer.launch(workDir, "fr.euphyllia.fidorial.server.testing.ScenarioTestMain",
                List.of("-DFidorial.logLevel=DEBUG"),
                List.of("fr.euphyllia.fidorial.server.tests", "fr.euphyllia.fidorial.testplugin.tests")));
    }

    private ScenarioTests() {
    }
}
