# Architecture

`frmtr` is a Java formatter with a small public API, thin CLI and Gradle adapters, and a formatter engine built around JavaParser plus an internal document IR. The project is split into focused Gradle modules so formatter internals and build-tool integrations can evolve behind explicit dependency boundaries.

## Build

The project is a Gradle multi-module build:

- Group: `dev.lanwen.frmtr`
- Java toolchain: Java 25
- Root project: aggregator only; it does not produce the formatter library or CLI artifact.
- `:frmtr-core`: formatter library and engine.
- `:frmtr-tooling`: reusable file-oriented runner, result summaries, and diff rendering for adapters.
- `:frmtr-cli`: Picocli application and native executable entrypoint that depends on `:frmtr-core` and `:frmtr-tooling`.
- `:frmtr-gradle-plugin`: Gradle plugin with project-local formatting tasks that depends on `:frmtr-core` and `:frmtr-tooling`.
- `:frmtr-native-image-support`: GraalVM native-image build-time companion module for JavaParser reflection metadata. It is wired only through native-image configurations, not normal JVM runtime classpaths.

Shared subproject conventions configure Java 25, UTF-8 compilation, `-Xlint:all`, JUnit Platform, and JaCoCo. External dependency versions and the GraalVM Native Build Tools plugin are managed through the Gradle version catalog in `gradle/libs.versions.toml`.

`:frmtr-cli` generates a small `BuildInfo` source file during compilation. It embeds the project version, current Git commit SHA, and build timestamp so JVM and native CLI binaries report the same build identity through `--version`.

## Package Layout

- `frmtr-core/src/main/java/dev/lanwen/frmtr`: public API and configuration.
- `frmtr-core/src/main/java/dev/lanwen/frmtr/doc`: formatter document IR and renderer.
- `frmtr-core/src/main/java/dev/lanwen/frmtr/java`: JavaParser-backed parser, syntax view, comment handling, and Java-specific printer.
- `frmtr-tooling/src/main/java/dev/lanwen/frmtr/tooling`: reusable file-oriented check/write runner, run summaries, per-file results, and unified diff rendering shared by adapters.
- `frmtr-cli/src/main/java/dev/lanwen/frmtr/cli`: Picocli command-line adapter, selector discovery, ignore handling, and output modes.
- `frmtr-gradle-plugin/src/main/java/dev/lanwen/frmtr/gradle`: Gradle extension, Java source-set integration, and formatter tasks.
- `frmtr-native-image-support/src/main/java/dev/lanwen/frmtr/nativeimage`: native-image feature code that registers JavaParser AST node fields for hosted reflection.

## Formatting Pipeline

Single-source formatting starts at `Frmtr.format(...)`.

1. `Frmtr` applies default or caller-provided `FormatterOptions`.
2. `JavaFormatter` parses source with JavaParser using stored tokens and attributed comments.
3. Syntactically invalid Java is rejected with `FormatterException`; v1 formats parseable compilation units only.
4. The parsed tree is adapted into `SyntaxNodeView` to keep formatter-owned syntax metadata separate from JavaParser APIs.
5. `JavaPrinter` walks JavaParser declarations and statements and emits `Doc` values.
6. `DocRenderer` renders the document IR using line width, indentation, line ending, and trailing-newline options.

JavaParser printers are not the formatter engine. They may be useful as references, but final formatting is owned by the `Doc` pipeline.

## Document IR

`Doc` is the intermediate representation between Java syntax rules and text output. It models formatting decisions instead of building strings directly:

- `Text` emits literal text.
- `Concat` joins documents.
- `Line`, `SoftLine`, and `HardLine` express break opportunities and required breaks.
- `Indent` increases indentation after line breaks.
- `Group` attempts flat rendering first and breaks when content does not fit.
- `IfBreak` selects different output for flat versus broken groups.

`DocRenderer` is language-agnostic. Java-specific choices belong in `JavaPrinter`, not in the renderer.

## File-Oriented Runs

`:frmtr-tooling` provides reusable file-oriented support for adapters that need to check or write many source files:

- `FormatterRunner.check(...)` formats selected files in memory and returns a `FormatRunResult` with per-file results and aggregate status helpers.
- `FormatterRunner.write(...)` writes changed formatter output back to disk, continues after per-file failures, distinguishes write-step failures as partially written results, and reports the full run summary.
- `UnifiedDiffRenderer` renders the same unified diff format for CLI and Gradle check output.

The runner owns deterministic path ordering and de-duplication for file lists supplied by adapters. Source discovery remains adapter-specific: the CLI uses selectors and `.gitignore`; the Gradle plugin builds one canonical file collection from Java source sets and Gradle-style source filters, then uses that same collection for task inputs and task actions.

## Java Formatter

`JavaFormatter` owns JavaParser configuration and parse-error handling. It enables token storage and comment attribution because formatter rules need syntax-adjacent trivia. `FormatterOptions.JavaLanguageLevel` is the public parser-level setting; `JavaFormatter` converts it to JavaParser's own language-level enum internally. The default is `LATEST_AVAILABLE`, which resolves through JavaParser's latest available stable alias at runtime, while `UNSET` deliberately selects JavaParser raw mode. Parse failures are reported with nearby source lines and a caret marker at JavaParser's reported line and column.

The public `Frmtr` API wraps recoverable internal formatter failures, including parser dependency linkage failures and assertions, as `FormatterException.internal(...)` so adapters can report concise failures without treating them as VM-level crashes.

`JavaPrinter` contains the current Java formatting rules for packages, imports, common type declarations, fields, methods, constructors, blocks, and basic statements. It keeps the v1 style deliberately opinionated and sparse on options.

`CommentTracker` preserves comments currently exposed by JavaParser as leading or orphan comments. Comment handling is expected to become more precise as the formatter grows.

## CLI

The CLI is an adapter over the public formatter API:

- No selectors: discover `./**/*.java` and check formatting by default.
- `--stdin`: read Java source from stdin and write formatted source to stdout; when combined with `--check` or `--diff`, compare stdin against formatter output using `stdin` as the display path. This mode is separate from file selectors and `--write`.
- `--check`: report each checked Java file with a status marker and exit non-zero when changes are needed. `✓` means already formatted, `✗` means formatting would change, and `!` means parsing or reading failed.
- `--diff`: in check mode, print unified diffs for sources marked `✗`; passed sources and parse/read failures do not produce diff blocks. With no selectors or `--stdin`, `--diff` implies check mode.
- `--write`: rewrite files in place.
- `--version`: print the project version, Git commit SHA, and build timestamp.
- `--java-level`: select the core Java parser language level; accepts enum names such as `LATEST_AVAILABLE` and `UNSET`, plus release shorthands such as `21` or `JAVA_21`.
- `--stacktrace`: include formatter or I/O stack traces in failure output; default CLI failures stay concise. Internal formatter failures are reported as internal bugs with the original failure summary and a stacktrace hint.
- Selectors may be repeated, comma-separated, files, directories, or glob patterns.
- Directory and glob traversal formats `.java` files, skips unknown extensions silently, and respects `.gitignore`.
- Multiple matched files without `--write` or `--check` are printed to stdout with filename headers.

CLI behavior should not own formatting policy. New formatting behavior belongs in the API and Java formatter pipeline first.

The CLI module owns application packaging and Gradle `run` wiring. Local execution uses `./gradlew :frmtr-cli:run --args='...'`; the `run` task uses the root project as its working directory and forwards `System.in` so selectors, default discovery, and `--stdin` behave like the native binary during local development.

## Gradle Plugin

The Gradle plugin ID is `dev.lanwen.frmtr`. Applying it creates project-local aggregate tasks:

- `frmtrFormat`: mutating formatter task aggregate, never wired into lifecycle tasks.
- `frmtrCheck`: verification aggregate wired into Gradle's `check` lifecycle.

When the Gradle Java plugin is present, Java formatting is enabled by convention without requiring a `frmtr {}` block. The plugin registers:

- `frmtrJavaFormat`: formats selected Java source-set files in place.
- `frmtrJavaCheck`: checks selected Java source-set files, suppresses unchanged-file output by default, prints `✗` and `!` status lines, and prints unified diffs for changed files by default.

The plugin is project-local. Applying it to a root project does not automatically reach into subprojects; users should apply it in each project or through a convention plugin.

Default Java source selection covers all Java source sets, de-duplicates files by normalized path, and excludes files under the project's Gradle build directory. `frmtr { java { include(...) exclude(...) } }` narrows source-set selection using source-root-relative Gradle patterns. Formatter tasks do not depend on source-generation tasks by default.

Gradle exposes parser language level as semantic DSL choices instead of mirroring every core Java release. `AUTO` infers from the Java toolchain language version first, then `sourceCompatibility`, and otherwise falls back to `LATEST_AVAILABLE`. `LATEST_AVAILABLE` explicitly ignores the Gradle project target and uses the newest stable JavaParser level. `UNDEFINED` maps to the core `UNSET` raw parser mode. Gradle stack trace output is controlled by Gradle's native `--stacktrace`; the plugin does not define a formatter-specific stacktrace switch.

## Native Binary

`frmtr-cli` applies GraalVM Native Build Tools and configures the native executable name as `frmtr`. It adds `:frmtr-native-image-support` to `nativeImageCompileOnly` and `nativeImageTestCompileOnly` so the companion module is visible to native-image builds and native tests without becoming part of `implementation` or ordinary JVM runtime classpaths.

`:frmtr-native-image-support` contributes `dev.lanwen.frmtr.nativeimage.JavaParserReflectionFeature` through native-image metadata. The feature iterates `JavaParserMetaModel.getNodeMetaModels()` and registers every declared field on each JavaParser AST node type with GraalVM hosted reflection APIs.

Docker is the default Linux native build path. `Dockerfile.native` builds inside `ghcr.io/graalvm/native-image-community:25` and emits a glibc-linked Linux binary. Docker on macOS still produces a Linux binary because native-image targets the build operating system and toolchain.

Local host-native builds use SDKMAN-managed GraalVM from `.sdkmanrc`, then `./gradlew :frmtr-cli:nativeCompile`.

## Tests

The test suite covers:

- `:frmtr-core`: Doc rendering behavior, formatter output, idempotence, reparse validity, comments, parse errors, and fixture corpus checks.
- `:frmtr-tooling`: file-oriented run summaries, deterministic ordering, de-duplication, diffs, write behavior, and per-file failure handling.
- `:frmtr-cli`: CLI selector parsing, glob/directory discovery, ignore handling, stdout/write/check behavior, option validation, and exit codes.
- `:frmtr-gradle-plugin`: TestKit functional coverage for task registration, zero-configuration Java defaults, `check` lifecycle wiring, no-op non-Java projects, Gradle and source-set source filters, build-directory exclusion, check diff output, Java language-level inference, and explicit Gradle language-level overrides.
- `:frmtr-native-image-support`: JavaParser metamodel coverage for native-image reflection registration, including known-risk AST fields used by field and variable declarations.
- `:frmtr-cli:nativeTest`: native-image compatibility coverage for JavaParser reflection-sensitive syntax. It is explicit native coverage and is not wired into the default JVM `check` lifecycle.
- Golden resources under `frmtr-core/src/test/resources/format`.
- A representative active subset of upstream `prettier-java` fixtures under `frmtr-core/src/test/resources/format/prettier-java`.
- The full upstream `prettier-java` fixture corpus under `frmtr-core/src/test/resources/upstream/prettier-java`.

New formatter rules should include golden coverage plus idempotence and reparse checks where practical.
