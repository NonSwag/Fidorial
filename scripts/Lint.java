import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * {@code jpm run lint} (in fidorial-build-tools, or from the root): ErrorProne and NullAway over Fidorial's
 * modules, with the checks the Gradle build ran on every compile.
 *
 * <p>Why not in the build: jpm can now pass ErrorProne's {@code -J--add-exports} to javac (in {@code "javacArgs"}),
 * but ErrorProne would have to be a {@code dev} dependency of every module, and dev dependencies are resolved
 * with the server's: they would move error_prone_annotations and checker-qual on its runtime. So this script
 * builds the workspace, takes the exact arguments jpm compiled each module with
 * ({@code .cache/build/<module>/classes.args}), adds ErrorProne, and runs javac again into a scratch
 * directory. The build's output is not touched.
 *
 * <p>The ErrorProne and NullAway jars come from this script's classpath: fidorial-build-tools' runtime, an
 * isolated module, so they are resolved apart from the server.
 */
public final class Lint {

    /** The modules that applied the fidorial-build-conventions plugin with ErrorProne enabled. */
    private static final List<String> MODULES = List.of(
            "fidorial-annotation-processor",
            "fidorial-api",
            "fidorial-auth",
            "fidorial-bootstrap",
            "fidorial-server");

    /** A module's source folders: its own and the registry generator's output. */
    private static final List<String> SOURCE_DIRS = List.of("src/main/java", "src/generated/java");

    private static final List<String> ERROR_PRONE = List.of(
            "-XDcompilePolicy=simple",
            "--should-stop=ifError=FLOW",
            "-Xmaxwarns", "10000",
            "-Xmaxerrs", "10000",
            String.join(" ",
                    "-Xplugin:ErrorProne",
                    // mostly noise
                    "-Xep:MissingSummary:OFF", "-Xep:ReferenceEquality:OFF", "-Xep:AvoidCommonTypeNames:OFF",
                    "-Xep:SameNameButDifferent:OFF", "-Xep:IdentityHashMapUsage:OFF", "-Xep:ThreadLocalUsage:OFF",
                    "-Xep:UnnecessaryLambda:OFF", "-Xep:UnnecessaryMethodReference:OFF", "-Xep:NonApiType:OFF",
                    "-Xep:InlineFormatString:OFF", "-Xep:StringConcatToTextBlock:OFF", "-Xep:RefactorSwitch:OFF",
                    "-Xep:UnusedTypeParameter:OFF", "-Xep:ExposedPrivateType:OFF", "-Xep:LoopOverCharArray:OFF",
                    "-Xep:ArrayRecordComponent:OFF", "-Xep:EnumOrdinal:OFF", "-Xep:EmptyCatch:OFF",
                    "-Xep:FutureReturnValueIgnored:OFF",
                    "-XepOpt:NullAway:OnlyNullMarked=true",
                    "-XepOpt:NullAway:JSpecifyMode=true",
                    "-XepOpt:NullAway:JSpecifyExperimental=true",
                    // nullability checks that are off by default
                    "-Xep:RequireExplicitNullMarking:WARN", "-Xep:JSpecifyUnrecognizedAnnotationLocation:WARN",
                    // important checks, so they error for sure & to prevent regressions
                    "-Xep:EqualsIncompatibleType:ERROR", "-Xep:CollectionIncompatibleType:ERROR",
                    "-Xep:StringCaseLocaleUsage:ERROR", "-Xep:DefaultLocale:ERROR", "-Xep:DefaultCharset:ERROR",
                    "-Xep:UnsafeLocaleUsage:ERROR"));

    /** What ErrorProne needs of javac's internals, as documented at https://errorprone.info/docs/installation. */
    private static final List<String> JVM_EXPORTS = Stream.concat(
                    Stream.of("api", "file", "main", "model", "parser", "processing", "tree", "util")
                            .map(p -> "-J--add-exports=jdk.compiler/com.sun.tools.javac." + p + "=ALL-UNNAMED"),
                    Stream.of("code", "comp")
                            .map(p -> "-J--add-opens=jdk.compiler/com.sun.tools.javac." + p + "=ALL-UNNAMED"))
            .toList();

    public static void main(final String[] args) throws Exception {
        final Path root = Path.of(System.getenv("JPM_PROJECT_DIR"));
        final String jpm = System.getenv("JPM_EXE");
        final String javac = Path.of(System.getenv("JPM_JAVA_HOME"), "bin", "javac").toString();
        final List<String> modules = args.length > 0 ? List.of(args) : MODULES;

        if (run(root, List.of(jpm, "build")) != 0) {
            System.exit(1);
        }

        final String plugins = System.getenv("JPM_CLASSPATH");
        final Path scratch = root.resolve(".cache/lint");
        int failed = 0;
        for (final String module : modules) {
            final Path argFile = root.resolve(".cache/build").resolve(module).resolve("classes.args");
            if (!Files.exists(argFile)) {
                throw new IllegalStateException(argFile + " is missing: jpm wrote no javac arguments for " + module);
            }
            final List<String> javacArgs = rewrite(Files.readAllLines(argFile), root.resolve(module), scratch.resolve(module),
                    plugins);
            final Path lintArgs = scratch.resolve(module + ".args");
            Files.createDirectories(scratch);
            Files.write(lintArgs, javacArgs.stream().map(Lint::quote).toList());

            System.out.println("    Linting " + module);
            final List<String> command = new ArrayList<>();
            command.add(javac);
            command.addAll(JVM_EXPORTS);
            command.add("@" + lintArgs);
            if (run(root, command) != 0) {
                failed++;
            }
        }
        if (failed > 0) {
            System.err.println("error: ErrorProne failed in " + failed + " module(s)");
            System.exit(1);
        }
        System.out.println("    ErrorProne and NullAway passed for " + modules.size() + " module(s)");
    }

    /**
     * jpm's arguments with the output redirected to {@code scratch}, ErrorProne added to the processor path
     * and every source of the module listed (jpm lists only what changed, for an incremental compile).
     */
    private static List<String> rewrite(final List<String> lines, final Path module, final Path scratch, final String plugins)
            throws Exception {
        final List<String> in = lines.stream().filter(l -> !l.isEmpty()).map(Lint::unquote).toList();
        final List<String> out = new ArrayList<>();
        boolean processorPath = false;
        for (int i = 0; i < in.size(); i++) {
            final String arg = in.get(i);
            switch (arg) {
                case "-d" -> {
                    out.add(arg);
                    out.add(scratch.resolve("classes").toString());
                    i++;
                }
                case "-s" -> {
                    out.add(arg);
                    out.add(scratch.resolve("generated").toString());
                    i++;
                }
                case "-processorpath", "--processor-path" -> {
                    out.add(arg);
                    out.add(plugins + File.pathSeparator + in.get(++i));
                    processorPath = true;
                }
                default -> {
                    // the module's own sources are listed below, all of them; keep generated ones (BuildConfig)
                    if (!(arg.endsWith(".java") && SOURCE_DIRS.stream().anyMatch(d -> Path.of(arg).startsWith(module.resolve(d))))) {
                        out.add(arg);
                    }
                }
            }
        }
        if (!processorPath) {
            out.add("-processorpath");
            out.add(plugins);
        }
        out.addAll(ERROR_PRONE);
        for (final String dir : SOURCE_DIRS) {
            if (!Files.isDirectory(module.resolve(dir))) {
                continue;
            }
            try (Stream<Path> sources = Files.walk(module.resolve(dir))) {
                sources.filter(f -> f.toString().endsWith(".java")).sorted().forEach(f -> out.add(f.toString()));
            }
        }
        for (final String dir : List.of("classes", "generated")) {
            deleteTree(scratch.resolve(dir));
            Files.createDirectories(scratch.resolve(dir));
        }
        return out;
    }

    private static String unquote(final String line) {
        if (line.length() >= 2 && line.startsWith("\"") && line.endsWith("\"")) {
            return line.substring(1, line.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return line;
    }

    /** javac's @argfile quoting, as jpm writes it. */
    private static String quote(final String s) {
        if (s.isEmpty() || s.chars().anyMatch(c -> c == ' ' || c == '"' || c == '\'' || c == '#' || c == '\\')) {
            return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        return s;
    }

    private static int run(final Path dir, final List<String> command) throws Exception {
        return new ProcessBuilder(command).directory(dir.toFile()).inheritIO().start().waitFor();
    }

    private static void deleteTree(final Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (final Path p : walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).toList()) {
                Files.delete(p);
            }
        }
    }

    private Lint() {
    }
}
