import com.diffplug.spotless.FormatterStep;
import com.diffplug.spotless.Provisioner;
import com.diffplug.spotless.generic.EndWithNewlineStep;
import com.diffplug.spotless.generic.ReplaceRegexStep;
import com.diffplug.spotless.generic.TrimTrailingWhitespaceStep;
import com.diffplug.spotless.java.ForbidWildcardImportsStep;
import com.diffplug.spotless.java.FormatAnnotationsStep;
import com.diffplug.spotless.java.ImportOrderStep;
import com.diffplug.spotless.java.RemoveUnusedImportsStep;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * {@code jpm run format} (in fidorial-build-tools, or from the root) formats Fidorial's Java sources;
 * {@code jpm run format --check} only reports, and fails when a file is not formatted (CI).
 *
 * <p>The same steps, in the same order, as the Spotless configuration of the Gradle build
 * (build-logic's fidorial-spotless plugin), run through spotless-lib directly. Sources listed in a
 * module's {@code generated-sources.txt} are left alone, as {@code src/generated/**} was.
 */
public final class Format {

    /** The modules that applied the fidorial-spotless plugin. */
    private static final List<String> MODULES = List.of(
            "fidorial-annotation-processor",
            "fidorial-api",
            "fidorial-auth",
            "fidorial-bootstrap",
            "fidorial-server",
            "fidorial-test-plugin");

    private interface Step {
        String apply(String source, File file) throws Exception;
    }

    public static void main(final String[] args) throws Exception {
        final boolean check = Arrays.asList(args).contains("--check");
        final Path root = Path.of(System.getenv("JPM_PROJECT_DIR")).getParent();

        // RemoveUnusedImports runs google-java-format, which spotless asks a Provisioner for: hand it our classpath.
        final Set<File> classpath = new LinkedHashSet<>();
        for (final String entry : System.getenv("JPM_CLASSPATH").split(File.pathSeparator)) {
            if (!entry.isEmpty()) {
                classpath.add(new File(entry));
            }
        }
        final Provisioner provisioner = (withTransitives, coordinates) -> classpath;

        final List<Step> steps = new ArrayList<>();
        for (final FormatterStep step : List.of(ForbidWildcardImportsStep.create(), FormatAnnotationsStep.create(),
                RemoveUnusedImportsStep.create(provisioner))) {
            steps.add(step::format);
        }
        steps.add((source, file) -> FinalizeVariables.apply(source));
        for (final FormatterStep step : List.of(
                ReplaceRegexStep.create("Expand empty code blocks", "(?m)^([ \\t]*)(.+) \\{\\}$", "$1$2 {\n$1}"),
                ReplaceRegexStep.create("Separate module requires static",
                        "(?m)^(    requires (?!static )[^\\n]+;\\n)(    requires static )", "$1\n$2"),
                ImportOrderStep.forJava().createFrom("", "javax|java", "\\#"),
                TrimTrailingWhitespaceStep.create(),
                EndWithNewlineStep.create())) {
            steps.add(step::format);
        }

        final List<Path> unformatted = new ArrayList<>();
        int files = 0;
        for (final String module : MODULES) {
            final Path dir = root.resolve(module);
            final Set<Path> generated = generated(dir);
            final List<Path> sources;
            try (Stream<Path> walk = Files.walk(dir.resolve("src"))) {
                sources = walk.filter(f -> f.toString().endsWith(".java") && !generated.contains(dir.relativize(f)))
                        .sorted().toList();
            }
            for (final Path file : sources) {
                files++;
                final String original = Files.readString(file, StandardCharsets.UTF_8);
                String formatted = original.replace("\r\n", "\n");
                try {
                    for (final Step step : steps) {
                        formatted = step.apply(formatted, file.toFile());
                    }
                } catch (final Exception e) {
                    throw new IllegalStateException(root.relativize(file) + ": " + e.getMessage(), e);
                }
                if (!formatted.equals(original)) {
                    unformatted.add(root.relativize(file));
                    if (!check) {
                        Files.writeString(file, formatted, StandardCharsets.UTF_8);
                    }
                }
            }
        }

        if (check && !unformatted.isEmpty()) {
            System.err.println("error: " + unformatted.size() + " file(s) are not formatted; run `jpm run format`:");
            unformatted.forEach(f -> System.err.println("    " + f));
            System.exit(1);
        }
        System.out.println("    " + (check ? "Checked " : "Formatted ") + files + " file(s)"
                + (check ? "" : ", " + unformatted.size() + " changed"));
    }

    private static Set<Path> generated(final Path module) throws Exception {
        final Path list = module.resolve("generated-sources.txt");
        final Set<Path> out = new HashSet<>();
        if (Files.exists(list)) {
            for (final String line : Files.readAllLines(list)) {
                if (!line.isBlank() && !line.startsWith("#")) {
                    out.add(Path.of(line.trim()));
                }
            }
        }
        return out;
    }

    private Format() {
    }
}
