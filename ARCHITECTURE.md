# Architecture

`frmtr` is a Java formatter with a small public API, thin CLI and Gradle adapters, and a formatter engine built around
JavaParser plus an internal document IR. The project is split into focused Gradle modules so formatter internals and
build-tool integrations can evolve behind explicit dependency boundaries.

This file is the architecture overview. Detailed formatter ownership, recovery behavior, and fixture strategy live under
`docs/` so the overview stays readable:

- [docs/java-formatter-internals.md](docs/java-formatter-internals.md) explains the formatter pipeline, printer graph,
  helper boundaries, comments, raw source handling, and guardrails.
- [docs/formatter-coverage.md](docs/formatter-coverage.md) maps JavaParser AST kinds to their current formatter owner
  and records intentional raw or compact fallback paths.
- [docs/error-recovery-behavior.md](docs/error-recovery-behavior.md) documents the implemented JavaParser parse-error
  recovery behavior and historical design decisions.
- [docs/testing-strategy.md](docs/testing-strategy.md) explains module-level coverage, golden fixtures, adopted
  `prettier-java` fixtures, and native-image compatibility checks.

## Build

The project is a Gradle multi-module build:

- Group: `dev.lanwen.frmtr`
- Java toolchain: Java 25
- Root project: aggregator only; it does not produce the formatter library or CLI artifact.
- `:frmtr-core`: formatter library and engine.
- `:frmtr-tooling`: reusable file-oriented runner, result summaries, and diff rendering for adapters.
- `:frmtr-cli`: Picocli application and native executable entrypoint that depends on `:frmtr-core` and
  `:frmtr-tooling`.
- `:frmtr-gradle-plugin`: Gradle plugin with project-local formatting tasks that depends on `:frmtr-core` and
  `:frmtr-tooling`.
- `:frmtr-native-image-support`: GraalVM native-image build-time companion module for JavaParser reflection metadata. It
  is wired only through native-image configurations, not normal JVM runtime classpaths.

Shared subproject conventions configure Java 25, UTF-8 compilation, `-Xlint:all`, JUnit Platform, and JaCoCo. External
dependency versions and the GraalVM Native Build Tools plugin are managed through the Gradle version catalog in
`gradle/libs.versions.toml`.

`:frmtr-cli` generates a small `BuildInfo` source file during compilation. It embeds the project version, current Git
commit SHA, and build timestamp so JVM and native CLI binaries report the same build identity through `--version`.

## Package Layout

```text
frmtr-core/src/main/java/dev/lanwen/frmtr
|-- public API and configuration
|-- doc
|   `-- formatter document IR and renderer
`-- java
    `-- JavaParser-backed parser, syntax view, comment handling,
        formatter pragma state, raw source text helpers, and Java-specific printer

frmtr-tooling/src/main/java/dev/lanwen/frmtr/tooling
`-- reusable file-oriented check/write runner, run summaries, per-file results,
    and unified diff rendering shared by adapters

frmtr-cli/src/main/java/dev/lanwen/frmtr/cli
`-- Picocli command-line adapter, selector discovery, ignore handling, and output modes

frmtr-gradle-plugin/src/main/java/dev/lanwen/frmtr/gradle
`-- Gradle extension, Java source-set integration, and formatter tasks

frmtr-native-image-support/src/main/java/dev/lanwen/frmtr/nativeimage
`-- native-image feature code that registers JavaParser AST node fields for hosted reflection
```

## Formatting Pipeline

Single-source formatting starts at `Frmtr.format(...)`.

1. `Frmtr` applies default or caller-provided `FormatterOptions`.
2. If `FormatterOptions.requirePragma` is enabled, `JavaFormatter` first checks the leading Javadoc comment for a
   recognized opt-in marker. The public marker is `@format`; source without an opt-in marker is returned unchanged.
3. `JavaFormatter` parses source with JavaParser using stored tokens and attributed comments, then wraps the raw parser
   response in an internal parse-result boundary.
4. Parse problems follow `FormatterOptions.ParseErrorBehavior`: `FAIL` rejects immediately with `FormatterException`,
   while default `RECOVER` enters the recovery boundary. See
   [docs/error-recovery-behavior.md](docs/error-recovery-behavior.md) for supported recovery slices and unsupported
   contexts.
5. The declared transform pipeline applies source-equivalent AST normalization before printing only when parsing
   completed without parse problems. Recovered parse trees skip transforms so partially recovered syntax is not reordered
   or mutated before raw-region printing.
6. The printable tree is adapted into `SyntaxNodeView` to keep formatter-owned syntax metadata separate from JavaParser
   APIs.
7. `JavaPrinter` walks JavaParser declarations and statements and emits `Doc` values.
8. `DocRenderer` renders the document IR using line width, indentation, line ending, and trailing-newline options.

JavaParser printers are not the formatter engine. They may be useful as references, but final formatting is owned by the
`Doc` pipeline.

## Document IR

`Doc` is the intermediate representation between Java syntax rules and text output. It models formatting decisions
instead of building strings directly:

- `Text` emits literal text.
- `Concat` joins documents.
- `Line`, `SoftLine`, and `HardLine` express break opportunities and required breaks.
- `Indent` increases indentation after line breaks.
- `Group` attempts flat rendering first and breaks when content does not fit.
- `IfBreak` selects different output for flat versus broken groups.
- `Label` attaches debug-only provenance to a subtree.

`DocRenderer` is language-agnostic. Java-specific choices belong in `JavaPrinter`, not in the renderer. Label nodes are
transparent to rendering, fitting, and width calculations.

`DocDebugRenderer` provides a stable structural dump of the document tree so formatter maintainers can inspect break
opportunities, indentation scopes, groups, flat-vs-broken alternatives, and high-level formatter rule labels. Label names
are diagnostic formatter-internal names and may evolve when rule boundaries move. `Frmtr.debugDoc(...)` exposes that view
for one Java source string after parsing, transforms, and Java printing, without invoking width-based rendering. It is a
core debug API only, not a formatting policy surface or CLI hook.

## File-Oriented Runs

`:frmtr-tooling` provides reusable file-oriented support for adapters that need to check or write many source files:

- `FormatterRunner.check(...)` formats selected files in memory and returns a `FormatRunResult` with per-file results
  and aggregate status helpers.
- `FormatterRunner.write(...)` writes changed formatter output back to disk, continues after per-file failures,
  distinguishes write-step failures as partially written results, and reports the full run summary.
- `UnifiedDiffRenderer` renders the same patch-like unified diff format for CLI and Gradle check output, using `origin`
  and `frmtr` as diff-side labels because adapters already print the file path on the surrounding status line. It also
  owns an opt-in terminal decoration mode that marks nearby hunk source columns with a dotted line-width guide without
  changing the plain patch-like default.
- `FormatterFailureRenderer` turns structured formatter failures into adapter-facing messages, including parse context,
  declaration-line context, and caret placement, without making the core exception message own terminal formatting.
- `FormatterRunFailureRenderer` renders failed file results as outlined diagnostic blocks titled by the failure message
  while file identity stays with adapter status lines.

The runner owns deterministic path ordering and de-duplication for file lists supplied by adapters. Source discovery
remains adapter-specific: the CLI uses selectors and `.gitignore`; the Gradle plugin builds one canonical file collection
from Java source sets and Gradle-style source filters, then uses that same collection for task inputs and task actions.

## Java Formatter

`JavaFormatter` owns JavaParser configuration, pragma gating, parse-error handling, and the declared transform stage
between parsing and printing. It enables token storage and comment attribution because formatter rules need
syntax-adjacent trivia.

`FormatterOptions` exposes one canonical record constructor for fully specified configuration, named static factories
for common partial configurations that keep the remaining formatter policy at defaults, and focused withers such as
`withParseErrorBehavior(...)` for changing one policy from a factory result.

Current public formatter policy includes:

- `FormatterOptions.JavaLanguageLevel`: the public parser-level setting. `LATEST_AVAILABLE` maps to JavaParser's
  bleeding-edge parser mode, while `UNSET` deliberately selects JavaParser raw mode.
- `FormatterOptions.ParseErrorBehavior`: the public parse-problem policy. The default is `RECOVER`; `FAIL` preserves
  strict fail-on-any-problem behavior.
- `FormatterOptions.requirePragma`: an opt-in setting that formats only files whose leading Javadoc comment contains the
  public `@format` marker.
- `FormatterOptions.LambdaArrowParens`: controls whether single-parameter lambdas preserve, avoid, or always emit
  parentheses.
- `FormatterOptions.BinaryOperatorPosition`: controls whether broken binary continuation lines keep operators at the end
  of the previous line or move operators to the start of continuation lines.

Parse failures are reported with structured `SourceProblem` entries on `FormatterException`: parser message, one-based
location when known, nearest enclosing declaration source line when detected, and source context around the failure. CLI
and Gradle rendering is handled outside core through `FormatterFailureRenderer` for single failures and
`FormatterRunFailureRenderer` for outlined per-file run failures.

The public `Frmtr` API wraps recoverable internal formatter failures, including parser dependency linkage failures and
assertions, as `FormatterException.internal(...)` so adapters can report concise failures without treating them as
VM-level crashes. `Frmtr.debugDoc(...)` shares that wrapping and the same parser, transform, and Java printing path as
formatting, but returns `DocDebugRenderer` output instead of rendered source.

`JavaPrinter` creates one per-run `JavaFormatContext`, wires formatter collaborators, and keeps the v1 style
deliberately opinionated and sparse on options. Formatter ownership then narrows through envelope gates, dispatcher
boundaries, and specialized declaration, statement, expression, type, comment, raw-source, and recovery helpers. See
[docs/java-formatter-internals.md](docs/java-formatter-internals.md) for those collaborator boundaries and
[docs/formatter-coverage.md](docs/formatter-coverage.md) for the AST ownership map.

Comment preservation is centralized through a per-run `JavaCommentMap`, read-only `JavaCommentPlacementPolicy` queries,
and stateful `CommentTracker` claims so adjacent leading clusters, line comments inside annotation arrays, and line
comments before fluent-chain segments are printed once while syntax-specific printers keep the surrounding layout.

Variable initializer wrapping is owned by the declaration printers that know the full declaration prefix. Direct
block-lambda initializers and object-creation-root method-call initializers keep the assignment with the opener while it
fits before falling back to a break after `=`, method-call initializers preserve huggable block-lambda call shapes before
falling back to hard-broken argument lists, and switch expression initializers keep their multiline body on the equals
line. Source-shape helpers and shared constructor layout policy preserve existing multiline call, field-root fluent-chain,
ternary, callable-header, constructor-root, builder-root, and try-resource forms using JavaParser ranges and bounded
source slices before printers assemble equivalent docs. Record headers try the full header first, then open component
lists before moving implemented types to their own continuation.

## CLI

The CLI is an adapter over the public formatter API:

- No selectors: discover `./**/*.java` and check formatting by default.
- `--stdin`: read Java source from stdin and write formatted source to stdout; when combined with `--check` or `--diff`,
  compare stdin against formatter output using `stdin` as the display path. This mode is separate from file selectors and
  `--write`.
- `--check`: report each checked Java file with a status marker and exit non-zero when changes are needed. `✓` means
  already formatted, `✗` means formatting would change, and `!` means parsing or reading failed. Non-stacktrace file-run
  failures are printed on stdout immediately after the failed file status line, and file check runs end with a concise
  stdout summary counting unchanged, would-change, and failed files.
- `--diff`: in check mode, print patch-like unified diffs for sources marked `✗`; passed sources and parse/read
  failures do not produce diff blocks. With no selectors or `--stdin`, `--diff` implies check mode. Diff output uses
  `origin` and `frmtr` labels instead of repeating the file path, and failure diagnostics follow their file status lines
  before the same check summary.
- `--render-line-width`: print terminal-only diff output for sources marked `✗` with a numbered width guide on each hunk
  header, plus vertical-ellipsis guide lines around hunk lines that approach or cross the configured source line width.
  Diff prefixes are metadata and do not count toward the guide column. Nearby and overflowing source lines use the same
  vertical-ellipsis cutoff marker; lines that cross the guide preserve their full text and receive a marker-prefixed
  overflow count.
- `--write`: rewrite files in place, group file-run failures on stderr by display path, and print a concise stdout
  processed summary counting formatted, failed, ignored, and unchanged files. Ignored files are `.java` files excluded by
  `.gitignore` during selector discovery.
- `--version`: print the project version, Git commit SHA, and build timestamp.
- `--java-level`: select the core Java parser language level; accepts enum names such as `LATEST_AVAILABLE` and `UNSET`,
  plus release shorthands such as `21` or `JAVA_21`.
- `--parse-error-behavior`: select the core parse-error policy; defaults to `recover` and accepts `fail` for strict parse
  failures.
- `--stacktrace`: include formatter or I/O stack traces in failure output; default CLI failures stay concise. Internal
  formatter failures are reported as internal bugs with the original failure summary and a stacktrace hint.
- Selectors may be repeated, comma-separated, files, directories, or glob patterns.
- Directory and glob traversal formats `.java` files, skips unknown extensions silently, and respects `.gitignore`.
- Missing explicit `.java` file selectors are tool errors reported on stderr with exit code 2. Empty glob or directory
  matches are not tool errors, but the CLI reports `No Java files matched.` on stderr instead of exiting silently.
- Multiple matched files without `--write` or `--check` are printed to stdout with filename headers. Because stdout is
  formatted source in this mode, the final processed summary is printed to stderr and counts printed, failed, and ignored
  files.

CLI behavior should not own formatting policy. New formatting behavior belongs in the API and Java formatter pipeline
first.

The CLI module owns application packaging and Gradle `run` wiring. Local execution uses
`./gradlew :frmtr-cli:run --args='...'`; the `run` task uses the root project as its working directory and forwards
`System.in` so selectors, default discovery, and `--stdin` behave like the native binary during local development.

## Gradle Plugin

The Gradle plugin ID is `dev.lanwen.frmtr`. Applying it creates project-local aggregate tasks:

- `frmtrFormat`: mutating formatter task aggregate, never wired into lifecycle tasks.
- `frmtrCheck`: verification aggregate wired into Gradle's `check` lifecycle.

When the Gradle Java plugin is present, Java formatting is enabled by convention without requiring a `frmtr {}` block.
The plugin registers:

- `frmtrJavaFormat`: formats selected Java source-set files in place.
- `frmtrJavaCheck`: checks selected Java source-set files, suppresses unchanged-file output by default, prints `✗`
  status lines, groups failed files by display path, and prints unified diffs with `origin` and `frmtr` side labels for
  changed files by default.

The plugin is project-local. Applying it to a root project does not automatically reach into subprojects; users should
apply it in each project or through a convention plugin.

Default Java source selection covers all Java source sets, de-duplicates files by normalized path, and excludes files
under the project's Gradle build directory. `frmtr { java { include(...) exclude(...) } }` narrows source-set selection
using source-root-relative Gradle patterns. Formatter tasks do not depend on source-generation tasks by default.

Gradle exposes parser language level as semantic DSL choices instead of mirroring every core Java release. `AUTO` infers
from `sourceCompatibility` first, then the Java toolchain language version, and otherwise falls back to
`LATEST_AVAILABLE`. `LATEST_AVAILABLE` explicitly ignores the Gradle project target and uses the core bleeding-edge
JavaParser mode. `UNDEFINED` maps to the core `UNSET` raw parser mode. Gradle stack trace output is controlled by
Gradle's native `--stacktrace`; the plugin does not define a formatter-specific stacktrace switch.

## Native Binary

`frmtr-cli` applies GraalVM Native Build Tools and configures the native executable name as `frmtr`. It adds
`:frmtr-native-image-support` to `nativeImageCompileOnly` and `nativeImageTestCompileOnly` so the companion module is
visible to native-image builds and native tests without becoming part of `implementation` or ordinary JVM runtime
classpaths.

Picocli's annotation processor generates CLI reflection and resource metadata during `:frmtr-cli:compileJava`. Proxy
metadata generation is disabled because the CLI does not require dynamic proxy entries and GraalVM 25 deprecates
`proxy-config.json` files discovered under `META-INF/native-image`.

`:frmtr-native-image-support` contributes `dev.lanwen.frmtr.nativeimage.JavaParserReflectionFeature` through native-image
metadata. The feature iterates `JavaParserMetaModel.getNodeMetaModels()` and registers every declared field on each
JavaParser AST node type with GraalVM hosted reflection APIs.

Docker is the default Linux native build path. `Dockerfile.native` builds inside
`ghcr.io/graalvm/native-image-community:25` and emits a glibc-linked Linux binary. Docker on macOS still produces a Linux
binary because native-image targets the build operating system and toolchain.

Local host-native builds use SDKMAN-managed GraalVM from `.sdkmanrc`, then `./gradlew :frmtr-cli:nativeCompile`.

## Tests

The test suite is module-scoped:

- `:frmtr-core`: formatter engine, document rendering, parser behavior, Java output, and formatter fixtures.
- `:frmtr-tooling`: file-oriented runs, diffs, ordering, de-duplication, write behavior, and per-file failure handling.
- `:frmtr-cli`: selector parsing, discovery, ignore handling, stdin/stdout/write/check modes, summaries, diagnostics,
  option validation, and exit codes.
- `:frmtr-gradle-plugin`: TestKit functional coverage for task registration, Java defaults, lifecycle wiring, source
  filters, build-directory exclusion, diff output, and Java language-level inference.
- `:frmtr-native-image-support`: JavaParser metamodel coverage for native-image reflection registration.
- `:frmtr-cli:nativeTest`: explicit native-image compatibility coverage outside the default JVM `check` lifecycle.

Golden fixture strategy, the adopted upstream `prettier-java` corpus, and new-rule coverage expectations are documented
in [docs/testing-strategy.md](docs/testing-strategy.md).
