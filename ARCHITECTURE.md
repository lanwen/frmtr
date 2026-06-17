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
- [docs/testing-strategy.md](docs/testing-strategy.md) explains module-level coverage, golden fixtures, frmtr-owned
  fixture conventions, and native-image compatibility checks.

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
- `:site`: JBake-backed static onboarding site. Source lives under `site/src/jbake` and the generated GitHub Pages
  artifact is `site/build/jbake`.

Shared subproject conventions configure Java 25, UTF-8 compilation, `-Xlint:all`, JUnit Platform, and JaCoCo. The root
project version comes from `gradle.properties`. External dependency versions, the JBake runtime, and the GraalVM Native
Build Tools plugin are managed through the Gradle version catalog in `gradle/libs.versions.toml`.

Java-producing modules also share Maven publication metadata. The root build applies `maven-publish`, sources jars, and
javadoc jars to Java subprojects, then configures generated POMs with the MIT license, project URL, GitHub SCM, issue
tracker, developer identity, and module descriptions. Only `:frmtr-core` and `:frmtr-tooling` register a Central-bound
`mavenJava` publication because those are the runtime dependencies that published adapters need from Maven repositories.
Those same publications can be staged into `build/staging-deploy` for JReleaser, which signs, checksums, verifies, and
deploys the release bundle through the Central Publisher API. The Central Portal snapshot repository is also attached to
`:frmtr-gradle-plugin` so snapshot plugin marker and plugin artifact publications can be resolved through Gradle's
`plugins {}` DSL. `:frmtr-gradle-plugin` lets Gradle's plugin development and Plugin Publish plugins own the plugin and
marker publications while inheriting the same POM metadata. `:frmtr-cli` is distributed as an application/native
executable rather than a Maven Central library artifact, and `:frmtr-native-image-support` stays a repo-internal
build-time companion for native-image builds. Every Java subproject jar also embeds the root MIT license as
`META-INF/LICENSE` so binary, sources, and javadoc artifacts carry the license text with the published files. The static
`:site` module is documentation-only and is not published as a Maven artifact.

The Gradle plugin publication declares Plugin Portal compatibility metadata. It currently marks configuration-cache
support as unsupported until the plugin tasks have a dedicated configuration-cache validation gate.

The `Snapshots` GitHub Actions workflow publishes Maven Central snapshots from `main` when the root version remains a
`-SNAPSHOT`, reusing the same Central Portal credentials and Gradle publication tasks as local snapshot publishing.

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

site/src/jbake
|-- assets
|   `-- static CSS and copied site assets
|-- content
|   `-- JBake content entries and metadata
`-- templates
    `-- Freemarker page shell and reusable landing-page components
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
6. `JavaPrinter` walks JavaParser declarations and statements and emits `Doc` values.
7. `DocRenderer` renders the document IR using line width, indentation, line ending, and trailing-newline options.

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

Small factory helpers such as `Doc.delimited(...)`, `Doc.joinComma(...)`, `Doc.breakOnly(...)`, and
`Doc.flatOnly(...)` capture recurring document shapes so list-like Java printers share one spelling for common
break/flat envelopes while the renderer remains language-agnostic.

`DocRenderer` is language-agnostic. Java-specific choices belong in `JavaPrinter`, not in the renderer. Label nodes are
transparent to rendering, fitting, and width calculations. `DocWidths` is the single flat-width authority: it owns the
flat-width measurement and the fit test, so `DocRenderer` and any observer of its decisions compute fit identically and a
fit decision can never diverge from the width number reported for it.

`DocDebugRenderer` provides a stable structural dump of the document tree so formatter maintainers can inspect break
opportunities, indentation scopes, groups, flat-vs-broken alternatives, and high-level formatter rule labels. Label names
are diagnostic formatter-internal names and may evolve when rule boundaries move. `Frmtr.debugDoc(...)` exposes that view
for one Java source string after parsing, transforms, and Java printing, without invoking width-based rendering.

`DocExplainRenderer` re-walks a document with the same `DocWidths` fit logic and column accounting as `DocRenderer` to
trace why each line laid out as it did, producing the presentation-free `DocExplanation` model. It records the
renderer's own width-driven `Group` breaks (with flat width, columns available, and start column) and the forced hard
line breaks a Java printer emitted as policy, attributing each forced break to the nearest enclosing rule label.

The renderer trace alone, however, cannot honestly explain the wraps developers debug most. Method chains, argument
lists, ternaries, and control conditions are pre-measured by their Java printers and emitted as `Doc.HardLine`s, so the
renderer never width-fits them — by the time `DocExplainRenderer` walks the document, the deciding flat width and budget
are gone and only a forced break remains. To surface the real reason, those printers record their own decision into a
per-run `LayoutDecisionLog` on `JavaFormatContext` at the point they choose a broken layout because a measured flat
candidate exceeded a `LayoutWidth` budget, capturing the construct kind, rule label, flat width, available width, and
segment count as a `PrinterWrap`. This is a side channel only: it does not change the `Doc` IR or the rendered text
(`format(...)` never reads the log), so the full fixture suite proves output stays byte-for-byte identical, and explain
remains a pure observer. `JavaFormatter.explain(...)` reads the log after printing and merges it into `DocExplanation`
alongside the renderer trace, so "why it wrapped" can report true width arithmetic and a human construct name for the
constructs the renderer only sees as forced breaks.

`Frmtr.explain(...)` exposes this through an `ExplainResult` that pairs the explanation with formatted output identical
to `Frmtr.format(...)` for the same input, and the CLI surfaces it through `--explain`. `Frmtr.debugDoc(...)` and
`Frmtr.explain(...)` are diagnostic observation surfaces, not formatting policy: surfacing them through the CLI exposes
the existing document view and its layout decisions without letting the CLI own or change formatting policy.

## File-Oriented Runs

`:frmtr-tooling` provides reusable file-oriented support for adapters that need to check or write many source files:

- `FormatterRunner.check(...)` formats selected files in memory and returns a `FormatRunResult` with per-file results
  and aggregate status helpers.
- `FormatterRunner.write(...)` writes changed formatter output back to disk, continues after per-file failures,
  distinguishes write-step failures as partially written results, and reports the full run summary.
- Multi-file `check` and `write` runs process selected files on an explicit fixed-size worker pool capped by available
  processors and file count. Results are collected into input-order slots before the `FormatRunResult` is exposed, so CLI
  and Gradle output remains deterministic even when files finish out of order.
- Runner progress is a side-channel callback that emits mandatory started, running, and finished snapshots from the
  coordinator thread. The tooling layer reports counters and active display paths only; adapters own presentation details
  such as stderr routing, spinners, and mode-specific labels.
- `UnifiedDiffRenderer` renders the same patch-like unified diff format for CLI and Gradle check output, using `origin`
  and `frmtr` as diff-side labels because adapters already print the file path on the surrounding status line. It also
  owns an opt-in terminal decoration mode that marks nearby hunk source columns with a dotted line-width guide without
  changing the plain patch-like default.
- `FormatterFailureRenderer` turns structured formatter failures into adapter-facing messages, including parse context,
  declaration-line context, and caret placement, without making the core exception message own terminal formatting. It
  returns diagnostic text split into semantic spans so adapters can preserve the same plain text while applying their own
  presentation.
- `FormatterRunFailureRenderer` renders failed file results as outlined diagnostic blocks titled by the failure message
  while file identity stays with adapter status lines, using the same semantic diagnostic spans for outline glyphs,
  source line numbers, source text, pointer markers, gaps, and error text.

The runner owns deterministic path ordering and de-duplication for file lists supplied by adapters. Source discovery
remains adapter-specific: the CLI uses selectors and `.gitignore`; the Gradle plugin builds one canonical file collection
from Java source sets and Gradle-style source filters, then uses that same collection for task inputs and task actions.

## Java Formatter

`JavaFormatter` owns JavaParser configuration, pragma gating, parse-error handling, and the declared transform stage
between parsing and printing. It enables token storage and comment attribution because formatter rules need
syntax-adjacent trivia.

`FormatterOptions` exposes one canonical record constructor for fully specified configuration, `defaults()` for the
standard formatter policy, `withJavaLanguageLevel(...)` for adapters that expose parser level alongside layout options,
and instance withers such as `withLineWidth(...)` and `withParseErrorBehavior(...)` for changing one policy from an
existing options value.

Current public formatter policy includes:

- `FormatterOptions.lineWidth`: the public target maximum rendered column width. The default is 120 columns, and the CLI
  and Gradle adapters use the same core default unless users override it.
- `FormatterOptions.JavaLanguageLevel`: the public parser-level setting. `LATEST_AVAILABLE` maps to JavaParser's
  bleeding-edge parser mode, while `UNSET` deliberately selects JavaParser raw mode.
- `FormatterOptions.ParseErrorBehavior`: the public parse-problem policy. The default is `RECOVER`; `FAIL` preserves
  strict fail-on-any-problem behavior.
- `FormatterOptions.requirePragma`: an opt-in setting that formats only files whose leading Javadoc comment contains the
  public `@format` marker.
- Active formatter ignore pragmas are `frmtr-ignore`, `frmtr-ignore-start`, and `frmtr-ignore-end`.
- `FormatterOptions.LambdaArrowParens`: controls whether single-parameter lambdas preserve, avoid, or always emit
  parentheses.
- `FormatterOptions.BinaryOperatorPosition`: controls whether broken binary continuation lines keep operators at the end
  of the previous line or move operators to the start of continuation lines. The default is `START`.

Parse failures are reported with structured `SourceProblem` entries on `FormatterException`: parser message, one-based
location when known, nearest enclosing declaration source line when detected, and source context around the failure. CLI
and Gradle rendering is handled outside core through `FormatterFailureRenderer` for single failures and
`FormatterRunFailureRenderer` for outlined per-file run failures.

The public `Frmtr` API wraps recoverable internal formatter failures, including parser dependency linkage failures and
assertions, as `FormatterException.internal(...)` so adapters can report concise failures without treating them as
VM-level crashes. `Frmtr.debugDoc(...)` shares that wrapping and the same parser, transform, and Java printing path as
formatting, but returns `DocDebugRenderer` output instead of rendered source.

Two opt-in, off-by-default debug toggles guard formatter correctness during development without affecting normal output.
`dev.lanwen.frmtr.debug.guardrails` enables comment-accounting and per-transform identity checks; the newer
`dev.lanwen.frmtr.debug.verify` enables an AST-equivalence verify mode that re-parses the formatter output and asserts
it is structurally equivalent to the input (modulo comments, whitespace, and the deliberate import reorder), catching a
meaning-changing printer bug in any construct. Verification is enabled across the `frmtr-core` test suite so every
golden fixture is also AST-checked. Both toggles live behind `FormatterGuardrails`; see
[docs/java-formatter-internals.md](docs/java-formatter-internals.md) for details.

`JavaPrinter` creates one per-run `JavaFormatContext`, constructs shared type rendering, and coordinates the three
printer composer groups: `ExpressionPrinters`, `DeclarationPrinters`, and `StatementPrinters`. Formatter ownership then
narrows through envelope gates, dispatcher boundaries, and specialized declaration, statement, expression, type,
comment, raw-source, and recovery helpers. See [docs/java-formatter-internals.md](docs/java-formatter-internals.md) for
those collaborator boundaries and [docs/formatter-coverage.md](docs/formatter-coverage.md) for the AST ownership map.

Comment preservation is centralized through a per-run `JavaCommentMap`, read-only `JavaCommentPlacementPolicy` queries,
and stateful `CommentTracker` claims so adjacent leading clusters, line comments inside annotation arrays, and line
comments before fluent-chain segments are printed once while syntax-specific printers keep the surrounding layout.

Variable initializer wrapping is coordinated through `VariableInitializerLayout`, with declaration printers supplying the
full declaration prefix and keeping ownership of variable sequencing. Direct block-lambda initializers and
object-creation-root method-call initializers keep the assignment with the opener while it fits before falling back to a
break after `=`, method-call initializers preserve huggable block-lambda call shapes before falling back to hard-broken
argument lists, and switch expression initializers keep their multiline body on the equals line. `LayoutWidth`
centralizes the indentation baselines used by these width probes. Source-shape helpers and shared constructor layout
policy preserve existing multiline call, field-root fluent-chain, ternary, callable-header, constructor-root,
builder-root, and try-resource forms using JavaParser ranges and bounded source slices before printers assemble
equivalent docs. Method-call chain doc assembly is split from ordinary method-call argument dispatch so source-chain
planning, root promotion, final-segment tails, and chain comments stay in the chain helper. Record headers try the full
header first, then open component lists before moving implemented types to their own continuation. Expression-lambda
argument planning is split into `ExpressionLambdaArgumentLayout` so call and chain printers share one typed width plan
instead of rebuilding partial lambda text independently. Broken expression-lambda bodies and source-multiline expression
lambda arguments keep an over-wide binary's method-call operand attached to the lambda opener when splitting that call is
enough, keep a compact binary tail on the call's closing line when it fits, then fall back to the shared binary
continuation renderer so the body cannot stay flat past the configured line width.

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
- Multi-file `--check` and `--write` runs can render progress to stderr as an in-place status. `--progress=auto` enables
  progress when the CLI process has an attached console, `--progress=always` forces it for captured launchers such as
  Gradle `JavaExec`, and `--progress=never` keeps stderr append-only for logs and scripts. When progress is enabled, the
  CLI emits an immediate `Discovering Java files...` status before selector traversal when discovery may walk directories
  or globs, then replaces it with the runner's initial `0/N` snapshot, current counters, and one active display path when
  the runner reports active work. Check progress labels changed files as `would change`; write progress labels them as
  `formatted`. stdout remains reserved for formatted source, check status/diffs/summary, and write summaries.
- `--diff`: in check mode, print patch-like unified diffs for sources marked `✗`; passed sources and parse/read
  failures do not produce diff blocks. With no selectors or `--stdin`, `--diff` implies check mode. Diff output uses
  `origin` and `frmtr` labels instead of repeating the file path, and failure diagnostics follow their file status lines
  before the same check summary.
- `--render-line-width`: print terminal-only diff output for sources marked `✗` with a numbered width guide on each hunk
  header, vertical-ellipsis markers on hunk lines that approach or cross the configured source line width, and markers on
  the nearest neighboring hunk rows, including blank rows, so guide continuity stays on existing diff lines. Diff
  prefixes are metadata and do not count toward the guide column. Lines that cross the guide preserve their full text and
  receive a marker-prefixed overflow count.
- `--color`: controls ANSI presentation for status markers, CLI-printed diffs, and CLI-rendered failure diagnostics.
  The default `auto` uses Picocli's
  terminal detection, `always` forces color for captured or redirected runs, and `never` keeps output plain for logs,
  scripts, or patch consumers. Colorization happens after adapter diff and diagnostic rendering, so formatted source
  stdout and `:frmtr-tooling` diff and diagnostic strings remain uncolored. Diagnostic semantic spans come from
  `:frmtr-tooling`; the CLI only maps those roles to terminal colors.
- `--progress`: controls stderr progress rendering independently of `--color`, so callers can force ANSI progress
  repainting for interactive build-tool launches without forcing colored status or diff output.
- `--write`: rewrite files in place, group file-run failures on stderr by display path, and print a concise stdout
  processed summary counting formatted, failed, ignored, excluded, and unchanged files. Ignored files are `.java` files
  excluded by `.gitignore` during selector discovery; excluded files are `.java` files matched by `--exclude`.
- `--explain`: its own diagnostic mode, mutually exclusive with `--check`, `--write`, `--diff`, and `--render-line-width`.
  It reads one source — `--stdin`, or exactly one file selector — and prints the formatted result followed by a structured
  explanation of the layout: a "why it wrapped" list, a pruned decision tree of rule labels and break/flat decisions, and
  a legend. The "why it wrapped" list leads with the printer-recorded width wraps (`PrinterWrap`): each names the
  construct the way a developer reads it (method chain, argument list, ternary, if condition), shows a short preview, and
  reports the real `flat width N > W available` arithmetic the printer measured, plus segment count where it applies. It
  then shows any width breaks the renderer itself decided. Structural body declarations and the statements that merely
  host a wrapped expression are filtered out so only causal wraps appear, each reported once; a forced break with no
  width measurement behind it is shown under a muted note, and "Nothing wrapped" is reachable for real, non-overflowing
  code. The formatted result is identical to a normal format run, because explain only observes the render. `--verbose`/
  `-v` keeps every group in the tree (pruning only decision-less leaf labels) and also surfaces the raw `java.*:` rule
  labels next to each friendly construct name. Output respects `--color` and is plain when piped, so the report is
  copy-pasteable into a bug report. Combining `--explain` with the check/write/diff flags, or giving more than one file
  selector, is a tool error reported on stderr with exit code 2. The CLI maps `DocExplanation`'s decisions to terminal
  presentation in `ExplainView`; it does not own any formatting policy.
- `--version`: print the project version, Git commit SHA, and build timestamp.
- `--java-level`: select the core Java parser language level; accepts enum names such as `LATEST_AVAILABLE` and `UNSET`,
  plus release shorthands such as `21` or `JAVA_21`.
- `--parse-error-behavior`: select the core parse-error policy; defaults to `recover` and accepts `fail` for strict parse
  failures.
- `--stacktrace`: include formatter or I/O stack traces in failure output; default CLI failures stay concise. Internal
  formatter failures are reported as internal bugs with the original failure summary and a stacktrace hint.
- Selectors and `--exclude` patterns may be repeated, comma-separated, files, directories, or glob patterns. Directory
  excludes apply recursively.
- Directory and glob traversal formats `.java` files, skips unknown extensions silently, respects `.gitignore`, and
  removes files matched by `--exclude`. CLI discovery uses selector-scoped, context-carrying directory jobs on a
  per-discovery bounded executor with a bounded shared directory queue, then sorts selected, ignored, and excluded paths
  so results stay deterministic regardless of worker completion order.
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

The root build exposes `frmtrSelfCheck` and `frmtrSelfFormat` as shared `JavaExec` wrappers over the current
`:frmtr-cli` runtime classpath. They provide a one-invocation dogfood path for the formatter engine, tooling runner, and
CLI over this checkout while excluding `frmtr-core/src/test/resources/format` and
`frmtr-core/src/test/resources/unsupported`, whose fixture corpora contain formatter-sensitive or intentionally invalid
Java samples. `frmtrSelfCheck` enables CLI unified diffs so reviewers can inspect drift directly from the check output.
Gradle plugin behavior remains covered by
`:frmtr-gradle-plugin` functional tests.

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

Golden fixture strategy, frmtr-owned fixture conventions, glob-discovered JUnit fixture sources, and new-rule coverage
expectations are documented in [docs/testing-strategy.md](docs/testing-strategy.md). Option-specific snapshots use
fixture-owned output variants with sidecar option properties rather than Java test lists.
