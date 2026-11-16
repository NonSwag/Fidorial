# Handoff: what jpm needs to build Fidorial without workarounds

Fidorial moved from Gradle to [jpm](https://github.com/java-package-manager/jpm) 0.7.1 (branch `feat/jpm`). Everything
the Gradle build did works, but a dozen jpm gaps forced workarounds in this repository. This document lists each
one: what breaks, why (with the place in jpm's source, as of tag `0.7.1`, commit `5ee87f7`), how to fix it in jpm or
which extra tool would cover it, and what Fidorial does meanwhile. The last section says what to delete from
Fidorial once a fix ships.

Priorities: **P0** blocks the build, **P1** forced a non-trivial workaround or changes the product, **P2** papercut.

| # | Problem | Priority | Fidorial workaround |
|---|---|---|---|
| 1 | Native binary cannot read Java 22+ module descriptors | P0 | `tools/jpm/` builds a patched jpm |
| 2 | Patches cannot add dependencies (nor use a newer release, nor patch `module-info`) | P1 | `fidorial-brigadier` module, `--add-exports` |
| 3 | No relocation in bundles; Git dependencies that shade cannot be built | P1 | Maven dependency for the configurate fork |
| 4 | One version per package across the workspace, tools included | P1 | `fidorial-build-tools` and the generator are separate projects |
| 5 | `javacArgs` go into an argfile: no `-J` options, so no ErrorProne | P1 | `jpm run lint` re-runs javac itself |
| 6 | Workspace modules do not inherit `"java"` (or anything else) | P1 | `"java": "25"` repeated in every module |
| 7 | No way for scripts to get the resolved classpath without `--ide` | P1 | `jpm info --json --ide`, `project.lock` parsed by regex |
| 8 | Scripts cannot declare their own dependencies | P2 | helpers kept JDK-only; build-tools project for the classpath |
| 9 | Scripts and hooks do not get `JPM_EXE` | P2 | scripts fall back to `jpm` on `PATH` |
| 10 | One source directory per module | P2 | generated sources merged into `src/main/java` + `generated-sources.txt` |
| 11 | Transient download failures are cached as "missing" | P2 | delete `~/.jpm/repository/**/*.missing` by hand |
| 12 | Compile-only annotations of Gradle-published libraries end up at runtime | P2 | none; 4 extra jars downloaded at boot |
| 13 | Native binary ignores `HTTPS_PROXY` and the system trust store | P2 | `-Dhttps.proxyHost=...` / `-Djavax.net.ssl.trustStore=...` |
| 14 | `jpm import --static` misreads this Gradle build | P2 | manifests written by hand |
| 15 | Smaller gaps: root scripts per module, resource filtering, javadoc options | P2 | see section |

---

## 1. Native binary cannot read Java 22+ module descriptors (P0)

**Symptom.** Compiling a named module that `requires` another module compiled for Java 25 fails with
`module not found: fr.fidorial` (also for `io.github.classgraph`, built for 25). jpm put those jars on `-cp`
instead of `--module-path`.

**Cause.** `src/dev/jpm/build/Modules.java`, `moduleNameFromBytes` (line 101) calls
`java.lang.module.ModuleDescriptor.read`, which rejects class files newer than the running JDK. The release binary is
a GraalVM native image built with JDK 21 (`project.jsonc` of jpm: `"java": "21"`; the binary reports
`21.0.2+13-jvmci-23.1-b30`), so any `module-info.class` of major version 66+ reads as "not a module". The same
applies to workspace modules' `classes/module-info.class` (`declaredName` on a directory).

**Fix.** The `Module` attribute has not changed since class file 53 (Java 9), so read a copy with the version
lowered — `tools/jpm/0001-read-module-descriptors-of-any-class-file-version.patch` does exactly that (10 lines) and is
what Fidorial builds with. Better still: parse the constant pool and the `Module` attribute directly (only the name
is needed), so jpm never depends on the JDK it was built with. Add a test with a hand-written `module-info.class` of
major version 69 and one of version 100. Separately, building the native image with the newest GA JDK would have hidden
this until Java 26.

**Workaround.** `tools/jpm/install.sh` clones jpm `0.7.1`, applies `tools/jpm/*.patch`, compiles it with `javac` and
installs a launcher as `~/.jpm/bin/jpm` (needs JDK 25). CI uses it.

## 2. Patches cannot add dependencies (P1)

**Symptom.** Fidorial patches Mojang's Brigadier so that `Message` extends Adventure's `ComponentLike`. Through
`"patches"`, compiling the patched classes fails with `cannot find symbol: Component`. Two more failures of the same
mechanism:

- the brigadier patch adds a `record`, and the configurate patch uses `_` (Java 22): `records are not supported in
  -source 8`, `unnamed variables are not supported in -source 17`;
- a patch that only changes adventure-api's `module-info.java` (one `exports ... to fr.fidorial.server`) fails with
  `package is empty or does not exist` for every exported package.

**Cause.** `src/dev/jpm/build/Builder.java`, `mavenJar` (line 513):

- the classpath is the original jar plus `graph.closure(self, ALL)` of the patched package — only what the library
  itself depends on (line 553);
- the release is `max(8, min(jdk, classRelease(jar)))` (line 557), the library's own bytecode level;
- only the changed `.java` files are compiled (`srcRoot` holds them alone), so `module-info.java` is compiled without
  the module's packages.

The Gradle plugin Fidorial used before (`fr.fidorial.dependency-patcher`) let every patch set declare extra
`compileOnly` dependencies and rebuilt the patched module as a whole.

**Fix.** Let a patch be an object as well as a path:

```jsonc
"patches": {
  "com.mojang:brigadier": {
    "file": "patches/com.mojang_brigadier.patch",
    // compile-only for the patched classes; not added to anyone's runtime graph
    "dependencies": { "net.kyori:adventure-api": "5.2.0", "net.kyori:adventure-text-serializer-plain": "5.2.0" },
    "release": 25          // optional; default stays the library's own level
  }
}
```

- Resolve `dependencies` like `dev` dependencies of the owning module (so they share the workspace's versions and the
  lockfile records them), and append their jars to `cp` in `mavenJar`. Include them in the cache key (today the key
  is only jar + patch, line 518).
- `release`: use it instead of `classRelease(jar)`; it must not be lower than the library's.
- For modular jars (the original has a `module-info.class`), compile the changed files with
  `--patch-module <name>=<original jar>` so `module-info.java` and package-private references resolve. That alone fixes
  the adventure-api case.
- Error message: when a patched class references a type that is not on the patch classpath, say so and point at
  `"dependencies"`.

**Workaround.**

- `fidorial-brigadier/`: an internal workspace module whose prebuild hook (`scripts/PatchedSources.java`) unpacks
  brigadier's sources jar, applies `patches/com.mojang_brigadier.patch` with `git apply`, and lets jpm compile the result
  with Adventure as `dev` dependencies. fidorial-server depends on it and excludes `com.mojang:brigadier` from
  fidorial-api. `jpm run diff` there writes a new patch.
- The adventure-api patch became `--add-exports net.kyori.adventure.api/net.kyori.adventure.internal.properties=fr.fidorial.server`
  in fidorial-server's `javacArgs` (the release launcher runs on the class path, so only compilation needed it).
- The configurate patch stays a jpm patch, with `_` renamed.

## 3. No relocation; Git dependencies that shade cannot be built (P1)

**Symptom.** `"github:GeyserMC/Configurate/yaml": "4.3.0-GeyserMC"` (the fork Fidorial uses) fails with
`invalid name "$prefix-parent"`. Even past that, the result would be wrong: `configurate-yaml` shades a patched
snakeyaml from a git submodule (`vendor/`, an `includeBuild`) and relocates it to
`org.spongepowered.configurate.yaml.internal.snakeyaml`; `configurate-core` uses AutoValue and multi-release source
sets (`java10`, `java16`).

**Cause / fix, in `src/dev/jpm/importer/GradleImport.java` (static import of Git dependencies):**

1. **Interpolation.** Evaluate simple Groovy/Kotlin string templates from variables assigned in the same settings
   file (`def prefix = "configurate"`; `rootProject.name = "$prefix-parent"`). When a name still is not a valid jpm
   name, fall back to the directory name with a warning rather than failing the whole import.
2. **Submodules.** Run `git submodule update --init --recursive` for Git dependencies (into the bare mirror's
   worktree), so `includeBuild 'vendor'` has sources.
3. **Multi-release sources.** Recognise `src/main/javaN` (and the `multirelease` convention) and compile them into
   `META-INF/versions/N/`.
4. **Relocation** — see below; without it, a shading build cannot be reproduced at all.

**Relocation in jpm.** Fidorial itself does not need to relocate today, but plugins commonly do, and building forks
from source needs it. Proposal:

```jsonc
"package": "bundle",
"relocate": { "org.yaml.snakeyaml": "org.spongepowered.configurate.yaml.internal.snakeyaml" }
```

Applied when writing a flat bundle (and when building a Git dependency whose import found a relocation). Without
ASM (jpm has no dependencies), relocation is a constant-pool rewrite: in every `.class`, rewrite `CONSTANT_Utf8`
entries that are internal names (`org/yaml/snakeyaml/...`), descriptors and signatures (`Lorg/yaml/snakeyaml/...;`),
and string constants that equal a dotted class name; rename the entries; rewrite `META-INF/services/*` file names and
contents. This covers what Shadow's relocator does in practice; reflection on computed strings stays the author's
problem, as with Shadow. A standalone **`jpm-shade` extension** would also work, but bundles are core, so core is the
better place.

**Workaround.** The fork stays a Maven dependency from `repo.opencollab.dev` (its published jars).

## 4. One version per package across the workspace (P1)

**Symptom.** Adding ErrorProne/NullAway/Spotless as dependencies of a workspace module (for the lint and format
scripts) changed fidorial-server's runtime: `error_prone_annotations` 2.50.0 → 2.52.0, `checker-qual` 3.51.1 → 4.3.1.
The tools' dependencies would have shipped in the release jar's `libraries.list`.

**Cause.** By design: one graph, one lockfile, highest satisfying version wins (README, "Resolution").

**Fix.** A way to keep tools out of the product's resolution, any of:

- `"isolated": true` on a module: resolved in its own graph (its own section of `project.lock`), never a dependency of
  other modules; meant for tooling and code generators.
- Or resolve the annotation-processor path (`dev`) separately from compile/runtime. ErrorProne, NullAway and most
  processors never need to agree with the runtime on versions.

**Workaround.** `fidorial-build-tools/` and `fidorial-registry-generator/` are standalone jpm projects (their own
`project.jsonc` and `project.lock`), not workspace modules. CI runs `jpm install --frozen` in all three.

## 5. `javacArgs` cannot carry `-J` options: no ErrorProne in the build (P1)

**Symptom.** The Gradle build ran ErrorProne + NullAway on every compile. With jpm, `-J--add-exports=...` in
`javacArgs` fails: `invalid flag: -J--add-exports...`. `@file` in `javacArgs` fails too (`javac` has no nested
argfiles), `JDK_JAVAC_OPTIONS` rejects `-J`, and `.env` does not reach `javac`.

**Cause.** `src/dev/jpm/build/Compiler.java` line 277: all arguments, `javacArgs` included, are written to
`classes.args` and `javac` runs as `javac @classes.args`; `javac` refuses `-J` inside argfiles.

**Fix.** Add `"javacJvmArgs": [...]` (or let `javacArgs` entries starting with `-J` be passed on the command line,
before the `@argfile`). That is all ErrorProne needs:

```jsonc
"dev": { "com.google.errorprone:error_prone_core": "2.52.0", "com.uber.nullaway:nullaway": "0.14.3" },
"javacJvmArgs": ["-J--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED", "..."],
"javacArgs": ["-XDcompilePolicy=simple", "--should-stop=ifError=FLOW", "-Xplugin:ErrorProne ..."]
```

Better: first-class compiler plugins (`"compilerPlugins": { "errorprone": { ... } }`) that know the exports, or a
**`jpm-errorprone` extension** generalising `scripts/Lint.java`. Note #4 also applies: as `dev` dependencies, ErrorProne
moves the runtime's versions, so this needs #4's separate processor-path resolution to be usable. Also: inheritance
(#6), so the 30-line ErrorProne configuration is written once.

**Workaround.** `jpm run lint` (`scripts/Lint.java`): builds, then for each module takes jpm's own `classes.args`,
redirects the output to `.cache/lint`, adds ErrorProne to the processor path and every module source, and runs `javac`
with the `-J` flags itself. It depends on jpm's internal argfile name and format.

## 6. Workspace modules inherit nothing (P1)

**Symptom.** With `"java": "25"` on the workspace root only, `jpm info` shows `"java": null` for every module, and jpm
compiled and ran hooks with whatever JDK it found first — JDK 21 on a machine with both — so Java 22+ multi-file
source scripts failed (`cannot find symbol: LibraryLists`). It only worked while `JAVA_HOME` pointed at 25.

**Cause.** `Builder.jdk(Manifest m)` uses the module's own `m.java`; null means `JavaSpec.any()`. Nothing is read from
the workspace root.

**Fix.** Modules inherit `java`, `target`, `javacArgs`, `nativeArgs`, `layout`, `group`, `license`, `url` (and
`repositories`, already root-only) from the workspace root unless they set them; `javacArgs` could append (or offer
`"javacArgs": ["...inherited", ...]`). At minimum, warn when a module has no `java` and the root has one.

**Workaround.** `"java": "25"` in every module manifest; the `--add-reads` flags repeated per module.

## 7. Scripts cannot get the resolved classpath (P1)

**Symptom.** The server's hooks need, per module, the resolved jars with their Maven coordinates (API package index,
bootstrap payload: which jars are downloaded at boot, which are bundled) and the dependency edges (plugin library
lock: the closure of one dependency). `JPM_CLASSPATH` has paths only, includes the module's classes dir, and mixes
workspace modules (as `.cache/build/<m>/classes`) with jars.

**Cause.** `jpm info --json` has dependencies but no jar paths; `--ide` adds `ideDependencies` with jars, but also
downloads every sources jar (slow, and a missing sources jar can fail the hook) and gives no edges.

**Fix.** `jpm info --json --classpath` (no sources), with, per module and scope (compile, runtime, processor):
`id`, `version`, `classifier`, `jar`, `kind` (maven, workspace, patched, git) and `dependencies` (edges with scope
and exclusions, as in `project.lock`). Also expose `define` values and `extensions` per module (extensions already
are). Then `scripts/JpmInfo.java`, `scripts/PluginLibraries.java` (which parses `project.lock` lines by regex) and the
path-based coordinate guessing in `scripts/LibraryLists.java` can go.

## 8. Scripts cannot declare dependencies (P2)

**Symptom.** A `.java` script runs with its module's runtime classpath. The test plugin's classpath is empty (all `dev`),
so its hook cannot use Gson; helper classes shared between scripts had to be split into JDK-only and Gson-using files.

**Fix.** JBang-style `//DEPS group:artifact:version` at the top of a script, or
`"scripts": { "lock": { "file": "scripts/Lock.java", "dependencies": { ... } } }`, resolved in an isolated graph (#4).

## 9. Scripts and hooks do not get `JPM_EXE` (P2)

**Symptom.** Hooks that call back into jpm (`jpm info --json`, `jpm build fidorial-test-plugin`) fail with
`Cannot run program "jpm"` when jpm was started by path.

**Cause.** Extensions get `JPM_EXE` (`src/dev/jpm/cli/Commands.java` line 979); `Scripts.info`
(`src/dev/jpm/build/Scripts.java` line 80) does not set it.

**Fix.** Put `JPM_EXE` (and `JPM_VERSION_OF_JPM`) in `Scripts.info`. One line.

**Workaround.** Scripts use `JPM_EXE` when set, else `jpm` on `PATH`.

## 10. One source directory per module (P2)

**Symptom.** Fidorial commits generated registry sources in `src/generated/java` beside `src/main/java`; jpm reads one.

**Fix.** `"sources": ["src/main/java", "src/generated/java"]` (and the same for resources), or a single
`"generatedSources"` directory that is compiled but which formatters and IDEs can tell apart.

**Workaround.** Generated files moved into `src/main/java`; each module lists them in `generated-sources.txt`, which
`jpm run format` skips and the generator rewrites (deleting files it no longer generates).

## 11. Transient download failures are cached as "missing" (P2)

**Symptom.** After Maven Central answered HTTP 429 once, `com.palantir.javapoet:javapoet:0.18.0` stayed
`Could not find ... in https://repo.maven.apache.org/maven2/` until `javapoet-0.18.0.pom.missing` was deleted by hand.

**Cause.** `src/dev/jpm/maven/MavenRepo.java` line 78/97: `fetch` writes `<file>.missing` whenever no repository
returned the file, whatever the reason (`download` returns false on 429 and on connection errors too).

**Fix.** Write `.missing` only when every repository answered 404 (or 410). Retry 429/5xx with backoff, honouring
`Retry-After`; report a network error as a network error, not as "not found".

## 12. Compile-only annotations end up at runtime (P2)

**Symptom.** Compared with Gradle, the server's runtime (and the release jar's `libraries.list`) gains
`org.jetbrains:annotations`, `org.checkerframework:checker-qual` and `com.google.auto.service:auto-service-annotations`,
e.g. through `net.kyori:adventure-api` and `configurate-core`.

**Cause.** These libraries publish them as `compileOnlyApi` in their Gradle Module Metadata, which the POM can only
express as `compile`. jpm's README says it reads module metadata for this, but the lock records them as runtime edges
(`"net.kyori:adventure-api": "compile !*:adventure-bom !*:annotations ..."` and plain `compile` elsewhere) — worth
checking against `adventure-api-5.2.0.module`.

## 13. Native binary and proxies (P2)

**Symptom.** Behind an HTTPS proxy with its own CA (corporate networks, sandboxes), the native binary fails with
`PKIX path building failed` and `ConnectException`, while every JVM tool works.

**Cause.** A native image does not read `JAVA_TOOL_OPTIONS`; `Http.client()` uses `ProxySelector.getDefault()`, which
only knows `-Dhttps.proxyHost`; the trust store is the one baked in at build time.

**Fix.** Honour `HTTPS_PROXY`/`HTTP_PROXY`/`NO_PROXY` in `Http.client()`; load the system trust store
(`/etc/ssl/certs/ca-certificates.crt`, `SSL_CERT_FILE`, the macOS keychain via `KeychainStore`) in addition to the
baked-in one.

## 14. `jpm import --static` on this build (P2)

Running it on the Gradle build gave unusable manifests. Each is a small fix in `GradleImport.java`:

- `repositories` picked up fragments of the next statement (`"https:\n    maven(/"`) from `maven("https://...")` calls;
- `group` taken from `group = "verification"` inside a task's configuration block (and `"build"` from another);
- typesafe project accessors not understood: `projects.fidorialApi`, `annotationProcessor(projects.fidorialAnnotationProcessor)`;
- `variantOf(libs.netty.epoll) { classifier(platform) }` and dependencies inside `for` loops skipped;
- a dependency with a configuration block (`api(projects.fidorialApi) { exclude(...) }`) read as `api ")`;
- included builds (`pluginManagement { includeBuild("build-logic") }`) imported as workspace modules;
- version catalogs of included builds (`../gradle/build-libs.versions.toml`) not found.

## 15. Smaller gaps (P2)

- **Scripts of other modules from the root.** `jpm run` only looks in the current directory's manifest; the root
  scripts are `cd fidorial-build-tools && jpm run lint`. Proposal: `jpm run <module>:<script>`.
- **Resource filtering.** Defines cover Java constants (`VersionConstants` now reads `BuildConfig`), but
  `spark-internal.properties` needed the spark version substituted into a resource; it is now a static file to keep in
  sync. Proposal: `"define"` values available to resources listed in `"filter": ["spark-internal.properties"]`.
- **Javadoc options.** The Gradle build set links, a stylesheet, a header and `-Xdoclint` for fidorial-api's javadoc;
  jpm-publish builds plain javadoc. Proposal: `"javadocArgs"` (read by jpm-publish), with paths relative to the module.
- **Version strings.** Fidorial's published version is `<minecraft>-build.<n>+<fidorial>`, computed in CI. jpm has one
  static `version`, so CI rewrites `project.jsonc` before `jpm publish`. Proposal: `"version"` overridable by
  `JPM_VERSION_OVERRIDE` (or `--version`) for publishing.
- **Workspace excludes.** Worth keeping: `{ "version": "*", "exclude": [...] }` on a `workspace:` dependency works and
  is what made the patched Brigadier possible.

## Additional tooling worth building

Generalised from Fidorial's `scripts/`, each could be a jpm extension (`jpm-<name>`):

| Extension | Replaces | Source to start from |
|---|---|---|
| `jpm-errorprone` (or core #5) | Gradle's `net.ltgt.errorprone` | `scripts/Lint.java` |
| `jpm-format` | Spotless (import order, unused imports, annotations, regex steps, custom steps) | `scripts/Format.java`, `scripts/FinalizeVariables.java` |
| `jpm-spotbugs` | `com.github.spotbugs` | `scripts/SpotBugs.java` |
| `jpm-shade` (or core #3) | Shadow's relocation | — |
| `jpm-patch-module` (or core #2) | `fr.fidorial.dependency-patcher` | `scripts/PatchedSources.java` |

## Removing Fidorial's workarounds

| When jpm has | Delete / change in Fidorial |
|---|---|
| #1 released | `tools/jpm/`; CI's "Install jpm" step becomes the official install script; readme instructions |
| #2 | `fidorial-brigadier/`, `scripts/PatchedSources.java`; patch brigadier in fidorial-server's `"patches"` with `"dependencies"`; restore the adventure-api module-info patch instead of `--add-exports` if wanted |
| #4 | fold `fidorial-build-tools` and `fidorial-registry-generator` back into the workspace as isolated modules; one `jpm install --frozen` in CI |
| #5 | `scripts/Lint.java`; ErrorProne in each module's `dev` + `javacJvmArgs` (with #6, once on the root) |
| #6 | `"java": "25"` and the repeated `--add-reads` in module manifests |
| #7 | `scripts/JpmInfo.java`; the regex parsing in `scripts/PluginLibraries.java`; `LibraryLists.coordinatesOf` |
| #9 | the `JPM_EXE` fallback in scripts |
| #10 | `generated-sources.txt`; move generated files back to `src/generated/java` |
