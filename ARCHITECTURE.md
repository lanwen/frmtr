# Architecture

`frmtr` is a Java formatter with a small public API, a thin CLI, and a formatter engine built around JavaParser plus an internal document IR. The current implementation is intentionally single-module until there is a concrete reason to split release or dependency boundaries.

## Build

The project is a Gradle Java application/library:

- Group: `dev.lanwen.frmtr`
- Java toolchain: Java 25
- Runtime entry point: `dev.lanwen.frmtr.cli.Main`
- Core parser dependency: `com.github.javaparser:javaparser-core`

The build produces both a library API and an application distribution. Tests run with JUnit Jupiter and coverage is wired through JaCoCo.

## Package Layout

- `dev.lanwen.frmtr`: public API and configuration.
- `dev.lanwen.frmtr.cli`: Picocli command-line adapter.
- `dev.lanwen.frmtr.doc`: formatter document IR and renderer.
- `dev.lanwen.frmtr.java`: JavaParser-backed parser, syntax view, comment handling, and Java-specific printer.

## Formatting Pipeline

Formatting starts at `Frmtr.format(...)`.

1. `Frmtr` applies default or caller-provided `FormatterOptions`.
2. `JavaFormatter` parses source with JavaParser using stored tokens and attributed comments.
3. Invalid Java is rejected with `FormatterException`; v1 formats valid compilation units only.
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

`JavaFormatter` owns JavaParser configuration and parse-error handling. It enables token storage and comment attribution because formatter rules need syntax-adjacent trivia.

`JavaPrinter` contains the current Java formatting rules for packages, imports, common type declarations, fields, methods, constructors, blocks, and basic statements. It keeps the v1 style deliberately opinionated and sparse on options.

`CommentTracker` preserves comments currently exposed by JavaParser as leading or orphan comments. Comment handling is expected to become more precise as the formatter grows.

## CLI

The CLI is an adapter over the public formatter API:

- No paths: read Java source from stdin and write formatted source to stdout.
- `--check`: report files that would change and exit non-zero when changes are needed.
- `--write`: rewrite files in place.
- Paths may be files or directories; directory traversal formats `*.java` files.

CLI behavior should not own formatting policy. New formatting behavior belongs in the API and Java formatter pipeline first.

## Tests

The test suite covers:

- Doc rendering behavior and line-breaking.
- Formatter output, idempotence, reparse validity, comments, and parse errors.
- CLI stdout, option validation, and exit codes.
- Golden resources under `src/test/resources/format`.

New formatter rules should include golden coverage plus idempotence and reparse checks where practical.
