# Architecture

`frmtr` is a Java formatter with a small public API, a thin CLI, and a formatter engine built around JavaParser plus an internal document IR. The project is split into focused Gradle modules so formatter internals, command-line integration, and future wrappers can evolve behind explicit dependency boundaries.

## Build

The project is a Gradle multi-module build:

- Group: `dev.lanwen.frmtr`
- Java toolchain: Java 25
- Root project: aggregator only; it does not produce the formatter library or CLI artifact.
- `:frmtr-core`: formatter library and engine.
- `:frmtr-cli`: Picocli application and native executable entrypoint that depends on `:frmtr-core`.

Shared subproject conventions configure Java 25, UTF-8 compilation, `-Xlint:all`, JUnit Platform, and JaCoCo. External dependency versions and the GraalVM Native Build Tools plugin are managed through the Gradle version catalog in `gradle/libs.versions.toml`.

## Package Layout

- `frmtr-core/src/main/java/dev/lanwen/frmtr`: public API and configuration.
- `frmtr-core/src/main/java/dev/lanwen/frmtr/doc`: formatter document IR and renderer.
- `frmtr-core/src/main/java/dev/lanwen/frmtr/java`: JavaParser-backed parser, syntax view, comment handling, and Java-specific printer.
- `frmtr-cli/src/main/java/dev/lanwen/frmtr/cli`: Picocli command-line adapter, selector discovery, ignore handling, and output modes.

## Formatting Pipeline

Formatting starts at `Frmtr.format(...)`.

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

## Java Formatter

`JavaFormatter` owns JavaParser configuration and parse-error handling. It enables token storage and comment attribution because formatter rules need syntax-adjacent trivia. JavaParser is configured for the project Java 25 toolchain so grammar feature gates, such as switch-expression `yield`, match source accepted by the build.

`JavaPrinter` contains the current Java formatting rules for packages, imports, common type declarations, fields, methods, constructors, blocks, and basic statements. It keeps the v1 style deliberately opinionated and sparse on options.

`CommentTracker` preserves comments currently exposed by JavaParser as leading or orphan comments. Comment handling is expected to become more precise as the formatter grows.

## CLI

The CLI is an adapter over the public formatter API:

- No paths: read Java source from stdin and write formatted source to stdout.
- `--check`: report each checked Java file with a status marker and exit non-zero when changes are needed.
- `--write`: rewrite files in place.
- `--stacktrace`: include formatter or I/O stack traces in failure output; default CLI failures stay concise.
- Selectors may be repeated, comma-separated, files, directories, or glob patterns.
- Directory and glob traversal formats `.java` files, skips unknown extensions silently, and respects `.gitignore`.
- Multiple matched files without `--write` or `--check` are printed to stdout with filename headers.

CLI behavior should not own formatting policy. New formatting behavior belongs in the API and Java formatter pipeline first.

The CLI module owns application packaging and Gradle `run` wiring. Local execution uses `./gradlew :frmtr-cli:run --args='...'`.

## Native Binary

`frmtr-cli` applies GraalVM Native Build Tools and configures the native executable name as `frmtr`.

Docker is the default Linux native build path. `Dockerfile.native` builds inside `ghcr.io/graalvm/native-image-community:25` and emits a glibc-linked Linux binary. Docker on macOS still produces a Linux binary because native-image targets the build operating system and toolchain.

Local host-native builds use SDKMAN-managed GraalVM from `.sdkmanrc`, then `./gradlew :frmtr-cli:nativeCompile`.

## Tests

The test suite covers:

- `:frmtr-core`: Doc rendering behavior, formatter output, idempotence, reparse validity, comments, parse errors, and fixture corpus checks.
- `:frmtr-cli`: CLI selector parsing, glob/directory discovery, ignore handling, stdout/write/check behavior, option validation, and exit codes.
- Golden resources under `frmtr-core/src/test/resources/format`.
- A representative active subset of upstream `prettier-java` fixtures under `frmtr-core/src/test/resources/format/prettier-java`.
- The full upstream `prettier-java` fixture corpus under `frmtr-core/src/test/resources/upstream/prettier-java`.

New formatter rules should include golden coverage plus idempotence and reparse checks where practical.
