# Architecture

`frmtr` is a Java formatter with a small public API, thin CLI and Gradle adapters, and a formatter engine built around
JavaParser plus an internal document IR. The project is split into focused Gradle modules so formatter internals and
build-tool integrations can evolve behind explicit dependency boundaries.

This file is the architecture overview. The [README](README.md) is the main source for installation, CLI recipes, Gradle
plugin usage, native-binary build commands, and user-facing option names. This file focuses on internal build shape,
module boundaries, data flow, and adapter contracts. Detailed formatter ownership, recovery behavior, and fixture
strategy live under `docs/` so the overview stays readable:

- [docs/java-formatter-internals.md](docs/java-formatter-internals.md) explains the formatter pipeline, printer graph,
  helper boundaries, comments, raw source handling, and guardrails.
- [docs/formatter-coverage.md](docs/formatter-coverage.md) maps JavaParser AST kinds to their current formatter owner
  and records intentional raw or compact fallback paths.
- [docs/error-recovery-behavior.md](docs/error-recovery-behavior.md) documents the implemented JavaParser parse-error
  recovery behavior and historical design decisions.
- [docs/testing-strategy.md](docs/testing-strategy.md) explains module-level coverage, golden fixtures, frmtr-owned
  fixture conventions, and native-image compatibility checks.

## Build

The Gradle build is the source of truth for exact toolchain versions, dependency versions, publication metadata, license
packaging, generated jars, task names, and CI publication wiring. This section records the architectural shape those
build files support.

### Module Boundaries

- Root: aggregates build conventions and repo-local helper tasks; it does not produce a runtime artifact.
- `:frmtr-core`: formatter public API, JavaParser integration, document IR, renderer, and Java printer.
- `:frmtr-tooling`: adapter-agnostic file runner, run summaries, diff rendering, and diagnostics; depends on
  `:frmtr-core`.
- `:frmtr-cli`: Picocli adapter, source discovery, terminal presentation, JVM app, and native executable entrypoint;
  depends on `:frmtr-core` and `:frmtr-tooling`.
- `:frmtr-gradle-plugin`: Gradle adapter, source-set integration, task wiring, and Gradle diagnostics; depends on
  `:frmtr-core` and `:frmtr-tooling`.
- `:frmtr-native-image-support`: native-image build-time metadata for JavaParser reflection; depends on JavaParser and
  GraalVM hosted APIs.
- `:site`: static onboarding site built with JBake.

### Shared Conventions

Root build logic applies shared Java conventions, test dependencies, JaCoCo wiring, publication metadata, license
packaging, and version propagation to Java subprojects. The version catalog owns external dependency and plugin
versions. Those values are intentionally not repeated here unless they explain a module boundary.

All Java subprojects compile their JVM bytecode with a Java 21 toolchain and `--release 21`. That includes the core
library, shared tooling, Gradle plugin, JVM CLI, and native-image support metadata module, so published artifacts and
the JVM CLI runtime stay loadable by Java 21 Gradle daemons. Native executable construction is the only Java 25 build
path: `:frmtr-cli:nativeCompile` invokes GraalVM native-image through a native-image-capable JDK 25 launcher while
consuming the same Java 21 CLI bytecode.

The root also owns repo-local helper tasks that run the current CLI over this checkout and exclude formatter fixture
corpora that contain formatter-sensitive or intentionally invalid Java samples.

### Distribution Channels

Only the library modules that external adapters need, `:frmtr-core` and `:frmtr-tooling`, are staged as Central-bound
`mavenJava` publications. The Gradle plugin uses Gradle's plugin development and publishing model for its plugin and
marker publications. The CLI is distributed as an application/native executable rather than as a Maven library, and
`:frmtr-native-image-support` stays a build-time companion visible only to native-image builds and native tests.

`:frmtr-cli` generates a small `BuildInfo` source file during compilation so JVM and native binaries report the same
project version, Git commit SHA, and build timestamp through Picocli's version provider.

## Package Layout

Package layout follows module ownership instead of sharing implementation packages across adapters:

```text
frmtr-core/src/main/java/dev/lanwen/frmtr
├── public API and configuration
├── doc
│   └── formatter document IR, renderer, width measurement, and debug/explain renderers
└── java
    └── JavaParser-backed parsing, syntax views, comments, pragmas, raw source helpers, and Java printers

frmtr-tooling/src/main/java/dev/lanwen/frmtr/tooling
└── file-oriented runner, summaries, per-file results, diffs, and diagnostic text

frmtr-cli/src/main/java/dev/lanwen/frmtr/cli
└── Picocli command adapter, selector discovery, ignore handling, stream routing, and output views

frmtr-gradle-plugin/src/main/java/dev/lanwen/frmtr/gradle
└── Gradle extension, source-set integration, and formatter tasks

frmtr-native-image-support/src/main/java/dev/lanwen/frmtr/nativeimage
└── native-image feature code that registers JavaParser AST node fields for hosted reflection

site/src/jbake
├── content, static assets, and Freemarker templates
└── documentation site source
```

## Formatting Pipeline

Single-source formatting starts at `Frmtr.format(...)`.

1. `Frmtr` applies default or caller-provided `FormatterOptions`, then uses a one-shot `FrmtrSession` for the call.
2. If `FormatterOptions.requirePragma` is enabled, `JavaFormatter` first checks the leading Javadoc comment for a
   recognized opt-in marker. The public marker is `@format`; source without an opt-in marker is returned unchanged.
3. `JavaFormatter` parses source with JavaParser using stored tokens and attributed comments, then wraps the raw parser
   response in an internal parse-result boundary.
4. Parse problems follow `FormatterOptions.ParseErrorBehavior`: `FAIL` rejects immediately with `FormatterException`,
   while default `RECOVER` enters the recovery boundary. See
   [docs/error-recovery-behavior.md](docs/error-recovery-behavior.md) for supported recovery slices and unsupported
   contexts.
5. The declared transform pipeline applies source-equivalent AST normalization before printing only when parsing
   completed without parse problems. Recovered parse trees skip transforms so partially recovered syntax is not
   reordered or mutated before raw-region printing.
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
- `Group` attempts flat rendering first and breaks when content does not fit. It carries an optional, nullable
  `groupId`: when set, the renderer records the group's chosen mode under that id so a dependent `IfBreak` can read it
  by name. The id never affects the group's own layout; the common, anonymous case (null id) is unchanged.
- `Fill` lays out an alternating `[content, separator, …]` list with greedy per-separator packing: each separator stays
  flat while the next content still fits on the current line and breaks only where it does not, so a fill keeps as many
  items per line as fit instead of being all-or-nothing like a `Group`. To an enclosing group it measures as the flat
  concatenation of all its parts (a safe over-estimate). Its first Java-printer consumer is the throws-clause printer,
  which greedily packs an overflowing `throws ...` exception list across continuation lines instead of leaving it as one
  unbreakable line (see `ThrowsClausePrinter`).
- `ConditionalGroup` holds an ordered list of layout alternatives and renders the first whose flat layout fits the space
  left on the current line (in flat mode), falling back to the last alternative in break mode when none fit. It is the
  IR form of the `Optional<Doc>` "try layout A, else B, else C" fallback chains printers hand-roll: ranking the
  alternatives (most compact first, an always-valid layout last) is the caller's job, while the renderer only picks among
  them using its own width measurement. To an enclosing group it measures as its first (most-flat) alternative. It is an
  additive primitive not yet adopted by any Java printer.
- `IfBreak` selects different output for flat versus broken groups. With a null `groupId` it follows the ambient
  surrounding group (the common case); with a non-null `groupId` it follows the mode the identified `Group` recorded
  when it rendered earlier, so a closing delimiter can mirror the break/flat decision of an opener group it does not
  enclose. The group-identity factories (`Doc.group(doc, groupId)` / `Doc.ifBreak(break, flat, groupId)`) are additive
  and not yet used by any Java printer; the existing `Doc.group(doc)` / `Doc.ifBreak(break, flat)` keep their signatures
  and pass a null id.
- `Label` attaches debug-only provenance to a subtree.
- `LineSuffix` defers its content to the next line break (or end of document), rendering nothing at its position and
  contributing zero flat width. It exists so trailing comments lay out after the code and separator on their line
  without the preceding code being measured against the line width as if the comment were inline. Content is
  single-line only; a `HardLine` inside it is rejected at render time.
- `BreakParent` (singleton `Doc.BREAK_PARENT`) is a zero-width marker that forces the nearest enclosing `Group` into
  break mode, the explicit form of the older "emit a `HardLine` to poison the fit measurement" trick but without
  printing a newline. It measures as a forced break (like `HardLine`) so any group containing it cannot stay flat,
  and renders nothing. Because group mode is decided top-down by flat measurement, it only affects groups whose
  measurement encounters it, so it is emitted at the point the breaking child is built.

Small factory helpers such as `Doc.delimited(...)`, `Doc.joinComma(...)`, `Doc.breakOnly(...)`, and
`Doc.flatOnly(...)` capture recurring document shapes so list-like Java printers share one spelling for common
break/flat envelopes while the renderer remains language-agnostic.

`DocRenderer` is language-agnostic. Java-specific choices belong in `JavaPrinter`, not in the renderer. Label nodes are
transparent to rendering, fitting, and width calculations. `DocRenderer` buffers `LineSuffix` content and flushes it,
in document order, immediately before each line break, so the deferred content prints at the visual end of its line. It
also keeps a per-render map from each identified group's `groupId` to the mode that group chose, populated as the group
renders and reset per render; a `groupId`-bound `IfBreak` reads that map instead of the ambient mode, so the identified
group must render before any `IfBreak` that targets it. For a `ConditionalGroup` it probes the alternatives in order with
the same `DocWidths` fit authority and renders only the chosen one, so exactly one alternative reaches the output.
`DocExplainRenderer` mirrors the suffix buffer, the group-mode map, and the conditional-group alternative selection so
its replayed column cursor stays identical to what `DocRenderer` emits.
`DocWidths` is the single flat-width authority: it owns the flat-width measurement and the fit test, so `DocRenderer`
and any observer of its decisions compute fit identically and a fit decision can never diverge from the width number
reported for it.

`DocDebugRenderer` provides a stable structural dump of the document tree so formatter maintainers can inspect break
opportunities, indentation scopes, groups, flat-vs-broken alternatives, and high-level formatter rule labels. Label
names are diagnostic formatter-internal names and may evolve when rule boundaries move. `Frmtr.debugDoc(...)` exposes
that view for one Java source string after parsing, transforms, and Java printing, without invoking width-based
rendering.

`DocExplainRenderer` re-walks a document with the same `DocWidths` fit logic and column accounting as `DocRenderer` to
trace why each line laid out as it did, producing the presentation-free `DocExplanation` model. It records the
renderer's own width-driven `Group` breaks (with flat width, columns available, and start column) and the forced hard
line breaks a Java printer emitted as policy, attributing each forced break to the nearest enclosing rule label.

The renderer trace alone, however, cannot honestly explain the wraps developers debug most. Method chains, argument
lists, ternaries, and control conditions are pre-measured by their Java printers and emitted as `Doc.HardLine`s, so the
renderer never width-fits them. By the time `DocExplainRenderer` walks the document, the deciding flat width and budget
are gone and only a forced break remains. To surface the real reason, those printers record their own decision into a
per-run `LayoutDecisionLog` on `JavaFormatContext` at the point they choose a broken layout because a measured flat
candidate exceeded a `LayoutWidth` budget, capturing the construct kind, rule label, flat width, available width, and
segment count as a `PrinterWrap`. This is a side channel only: it does not change the `Doc` IR or the rendered text
(`format(...)` never reads the log), so the full fixture suite proves output stays byte-for-byte identical, and explain
remains a pure observer. `JavaFormatter.explain(...)` reads the log after printing and merges it into `DocExplanation`
alongside the renderer trace, so "why it wrapped" can report true width arithmetic and a human construct name for the
constructs the renderer only sees as forced breaks.

`Frmtr.explain(...)` exposes this through an `ExplainResult` that pairs the explanation with formatted output identical
to `Frmtr.format(...)` for the same input, and the CLI surfaces it through explain mode. `Frmtr.debugDoc(...)` and
`Frmtr.explain(...)` are diagnostic observation surfaces, not formatting policy: surfacing them through the CLI exposes
the existing document view and its layout decisions without letting the CLI own or change formatting policy.

## File-Oriented Runs

`:frmtr-tooling` owns the adapter-neutral run model for checking or writing many source files.

### Runner Model

- `FormatterRunner.check(...)` formats selected files in memory and returns a `FormatRunResult` with per-file results
  and aggregate status helpers.
- `FormatterRunner.write(...)` writes changed formatter output back to disk, continues after per-file failures,
  distinguishes write-step failures as partially written results, and reports the full run summary.
- `FormatterRunner.write(...)` stages formatted text in a temp file in the same directory and renames it over the
  original (preserving POSIX mode), preferring atomic moves and falling back to a plain replace only when unsupported.
- `FormatterRunner.writeVerified(...)` uses the same runner and write pipeline as `write(...)`, but formats each file
  through `FrmtrSession.formatVerified(...)`; when verification fails the non-internal `FormatterException` is thrown
  before any write, so the file is left untouched and reported as `FAILED`. The default write path is unchanged and pays
  no verification cost.
- Multi-file `check` and `write` runs process selected files on an explicit fixed-size worker pool capped by available
  processors and file count. Results are collected into input-order slots before the `FormatRunResult` is exposed, so
  CLI and Gradle output remains deterministic even when files finish out of order.
- Each file-runner worker owns one `FrmtrSession` for the run's `FormatterOptions` and reuses it sequentially for files
  assigned to that worker. Formatter sessions are never shared across worker threads because their JavaParser instance
  is reusable only by one thread at a time.
- Runner progress is a side-channel callback that emits mandatory started, running, and finished snapshots from the
  coordinator thread. The tooling layer reports counters and active display paths only; adapters own presentation
  details such as stderr routing, spinners, and mode-specific labels.

### Shared Output Models

- `UnifiedDiffRenderer` renders the same patch-like unified diff format for CLI and Gradle check output, using `origin`
  and `frmtr` as diff-side labels because adapters already print the file path on the surrounding status line. It also
  owns an opt-in terminal decoration mode that marks nearby hunk source columns with a dotted line-width guide without
  changing the plain patch-like default.
- `FormatterFailureRenderer` turns structured formatter failures into adapter-facing messages, including parse context,
  declaration-line context, and caret placement, without making the core exception message own terminal formatting. It
  returns diagnostic text split into semantic spans so adapters can preserve the same plain text while applying their
  own presentation.
- `FormatterRunFailureRenderer` renders failed file results as outlined diagnostic blocks titled by the failure message
  while file identity stays with adapter status lines, using the same semantic diagnostic spans for outline glyphs,
  source line numbers, source text, pointer markers, gaps, and error text.

The runner owns deterministic path ordering and de-duplication for file lists supplied by adapters. Source discovery
remains adapter-specific: the CLI uses selectors and `.gitignore`; the Gradle plugin builds one canonical file
collection from Java source sets and Gradle-style source filters.

## Java Formatter

`JavaFormatter` owns JavaParser configuration, pragma gating, parse-error handling, and the declared transform stage
between parsing and printing. It enables token storage and comment attribution because formatter rules need
syntax-adjacent trivia. Public `FrmtrSession` instances wrap one `JavaFormatter` for sequential reuse when a caller
formats multiple sources with identical options; sessions are explicitly not thread-safe.

`FormatterOptions` is the core policy boundary. It exposes one canonical record constructor for fully specified
configuration, `defaults()` for the standard formatter policy, and focused withers for changing one policy from an
existing options value. It is intentionally formatter-wide: values can affect parsing, document rendering, raw-source
handling, or syntax-wide style decisions. Adapters translate their user-facing flags and DSL, which are documented in
the README, into this record and then call the same `JavaFormatter` path so CLI and Gradle behavior cannot drift into
separate formatting policies.

The policy surface currently covers target line width, indentation, emitted line ending and trailing newline,
raw-region trailing whitespace, require-pragma gating, single-parameter lambda parentheses, broken binary operator
placement, parse-error behavior, and parser language level. Node-specific heuristics remain internal Java printer policy
rather than public options.

Pragma handling is owned by core. The require-pragma gate checks the leading Javadoc `@format` marker before formatting,
and active formatter ignore pragmas are `frmtr-ignore`, `frmtr-ignore-start`, and `frmtr-ignore-end`.

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
golden fixture is also AST-checked. The `dev.lanwen.frmtr.debug.guardrails` comment-accounting check stays an opt-in
dev aid only: the durable "no comment is dropped" CI gate is instead `CommentPresenceDiagnosticTest`, which compares the
lexer comment-token multiset of each input against its formatted output over every golden fixture and every
collapsed/expanded perturbation, failing on any genuine drop (a documented exclusion list tracks the remaining S9
backlog). The stricter "each comment claimed at most once" invariant lives behind a separate off-by-default
`dev.lanwen.frmtr.debug.guardrails.strict-claims` toggle, deferred until comment ownership is deterministic. All toggles
live behind `FormatterGuardrails`; see [docs/java-formatter-internals.md](docs/java-formatter-internals.md) for details.

`JavaPrinter` creates one per-run `JavaFormatContext`, constructs shared type rendering, and coordinates the three
printer composer groups: `ExpressionPrinters`, `DeclarationPrinters`, and `StatementPrinters`. Formatter ownership then
narrows through envelope gates, dispatcher boundaries, and specialized declaration, statement, expression, type,
comment, raw-source, and recovery helpers. See [docs/java-formatter-internals.md](docs/java-formatter-internals.md) for
those collaborator boundaries and [docs/formatter-coverage.md](docs/formatter-coverage.md) for the AST ownership map.

Comment preservation is centralized through a per-run `JavaCommentMap`, read-only `JavaCommentPlacementPolicy` queries,
and stateful `CommentTracker` claims so adjacent leading clusters, line comments inside annotation arrays, and line
comments before fluent-chain segments are printed once while syntax-specific printers keep the surrounding layout.

Complex Java layout rules are factored into dedicated helpers rather than embedded in broad dispatchers. `LayoutWidth`
centralizes indentation baselines for width probes, source-shape helpers preserve meaningful existing multiline forms,
initializer helpers coordinate declaration-local wrapping, and chain helpers keep method-call source planning out of
ordinary argument dispatch. A per-run `SourceShapePolicy` on `JavaFormatContext` is the consolidating home for
"should the formatter respect the author's source shape here?" decisions, so printers ask one named question instead of
re-deriving those reads from raw token text or `getRange()` arithmetic. It owns one canonical definition of each
source-shape decision:

- whether a node was already multiline — `wasMultiline`, range-first with a raw-text fallback;
- whether the author left a blank line between two source-adjacent nodes — `hadBlankLineBetween`, plus a
  `hadBlankLineBefore` overload for callers that first resolve a comment-aware begin line;
- whether a node's source-equivalent compact text fits on one line at its call-site indentation — `fitsOnOneLine`,
  which applies a per-site indented-width function to `CompactSourceText` and owns the single `lineWidth()` comparison
  while leaving compact-text generation in that helper;
- whether a fluent-chain segment's selector began on a later source line than the previous segment ended —
  `selectorBrokeAfter`, the chain-split definition the method-call chain source planner consults instead of its own
  range arithmetic;
- whether a node encloses comments that make a compact or otherwise source-shaped layout unsafe — `hasContainedComments`,
  delegating containment itself to the run-indexed `JavaCommentPlacementPolicy.hasContainedComments` rather than
  re-scanning JavaParser (compact-source reconstruction that strips comments on clones keeps its own direct scan because
  the run index reports an unknown clone as comment-free); and
- the syntax-specific predicates built on `wasMultiline` (multiline argument lists, same-line starts, throws-clause and
  try-with-resources shape, method-call operand and logical-condition shape), so a printer asks one source-shape object
  rather than reaching for the same multiline answer two different ways.

Raw recovery/fallback text generation is not a source-shape decision and is not funneled through the policy: a printer
that must emit a node's raw source for recovery or a fallback reads it straight from `RawSource` (the `raw` /
`rawWithoutOwnComment` string forms), while genuine raw-output passes that must account for the comments they emit use
`RawPreservedSource`, and source-equivalent compact text keeps using `RawSource`/`CompactSourceText`.

`SourceShapeCouplingGuardTest` keeps the boundary from eroding: it fails if a printer outside the policy and the
slicing/raw-output/compact/recovery helpers re-introduces either consolidated pattern — a `rawSource....contains("\n")`
multiline probe or blank-line gap arithmetic in either spelling (`previous.end.line + 1` or the subtraction form
`next.begin.line - previous.end.line`). The broader "no `getRange().*.line` layout arithmetic outside the policy" rule
remains a documented review checklist in
[docs/java-formatter-internals.md](docs/java-formatter-internals.md) rather than a test, because that arithmetic
legitimately remains in the recovery and source-slicing helpers. Shared method-call argument helpers keep over-wide and
source-multiline argument policies
consistent when method calls appear in direct calls, initializers, and try resources. Expression-lambda helpers share
width plans across call contexts, and expression tails thread statement terminators or separators through expression
rendering before trailing line comments are placed. See
[docs/java-formatter-internals.md](docs/java-formatter-internals.md) for the detailed helper map.

## CLI

The CLI is a Picocli adapter over the public formatter API and the file-oriented tooling runner. The README documents
the supported invocation modes and option names; architecturally, the CLI owns argument validation, source discovery,
stream routing, terminal presentation, application packaging, and process exit mapping.

CLI behavior should not own formatting policy. New formatting behavior belongs in the API and Java formatter pipeline
first, then the CLI may expose it by translating arguments into `FormatterOptions` or by selecting tooling-runner
presentation. The CLI currently exposes formatter policy for line width, indentation width, parser language level, and
parse-error behavior.

The `--verify` flag (off by default, rejected unless `--write` is present) exposes the API's `formatVerified(...)`
safety valve through `FormatterRunner.writeVerified(...)`. It does not own the equivalence check; it only selects the
verified write path, which fails closed with a non-internal diagnostic instead of overwriting a non-equivalent result.
Default `--write`, stdin, explain, check mode, and the `dev.lanwen.frmtr.debug.verify` toggle are unchanged. A
Gradle-plugin equivalent is a planned follow-up.

Selector discovery is CLI-local because it depends on command-line concepts: default selectors, explicit files,
directories, globs, comma-separated selector groups, `.gitignore`, and CLI excludes. Discovery uses selector-scoped,
context-carrying directory jobs on a bounded executor with a bounded shared directory queue, then sorts selected,
ignored, and excluded paths so results stay deterministic regardless of worker completion order.

The CLI keeps formatted source, status lines, diffs, summaries, and diagnostic reports on stdout when those values are
part of command output; stderr is reserved for tool errors, write-mode per-file failure grouping, and live progress.
Colorization and in-place progress repainting are adapter presentation layers on top of plain `:frmtr-tooling` diff,
diagnostic, and progress models.

Explain mode is also an adapter surface over core diagnostics. `JavaFormatter.explain(...)` and `DocExplanation` own the
layout-decision data; the CLI maps those decisions to terminal text in `ExplainView`, validates that explain receives
one source, and preserves the formatted output byte-for-byte with a normal format run.

The CLI module owns application packaging and Gradle `run` wiring. The `run` task launches with the Java 21 toolchain,
uses the root project as its working directory, and forwards `System.in` so selectors, default discovery, and stdin mode
behave like the native binary during local development.

The root build exposes `frmtrSelfCheck` and `frmtrSelfFormat` as shared `JavaExec` wrappers over the current
`:frmtr-cli` runtime classpath. They also launch with the Java 21 toolchain, providing a one-invocation dogfood path for
the formatter engine, tooling runner, and CLI over this checkout while excluding `frmtr-core/src/test/resources/format` and
`frmtr-core/src/test/resources/unsupported`, whose fixture corpora contain formatter-sensitive or intentionally invalid
Java samples. `frmtrSelfCheck` enables CLI unified diffs so reviewers can inspect drift directly from the check output,
and `frmtrSelfFormat` runs `--write --verify` so repo self-formatting exercises the verified write path and refuses
non-equivalent rewrites. Gradle plugin behavior remains covered by
`:frmtr-gradle-plugin` functional tests.

## Gradle Plugin

The Gradle plugin is the build-tool adapter over the same formatter API and tooling runner used by the CLI. The README
documents the plugin ID, task names, and DSL options; architecturally, the plugin owns Gradle model wiring, task
registration, source-set discovery, incremental input declarations, cache semantics, and Gradle-native diagnostics.

Applying the plugin creates project-local aggregate tasks and an extension. In multi-project builds, root application
recursively applies the plugin to child projects so every module gets its own extension and aggregate tasks, while root
aggregates depend on Java-capable child aggregates. Child extension values inherit parent conventions until a child sets
its own value, and disabling a child keeps aggregation intact while skipping that child's Java formatter tasks.

When the Gradle Java plugin is present, Java formatting is enabled by convention. The plugin builds one canonical file
collection from Java source sets plus Gradle-style include/exclude filters, de-duplicates normalized paths, excludes the
project build directory, and intentionally avoids implicit dependencies on source-generation tasks.

The Gradle Java tasks use the same selected file collection for task inputs and task actions. Check tasks are cacheable
verification tasks with deterministic success markers, while format tasks stay non-cacheable because they rewrite source
files in place and do not claim build-cache or up-to-date behavior for source mutation.

Gradle parser-level choices are semantic DSL values rather than a mirror of every core Java release. The plugin maps
those DSL values into `FormatterOptions.JavaLanguageLevel` before calling the runner. Stacktrace behavior stays
delegated to Gradle's native `--stacktrace` support instead of introducing a formatter-specific Gradle switch.

## Native Binary

Native-image support is split so JVM runtime dependencies stay clean:

- `:frmtr-cli` owns the executable entrypoint and native binary configuration.
- `:frmtr-native-image-support` is visible only to native-image builds and native tests, not normal JVM runtime
  classpaths.
- `dev.lanwen.frmtr.nativeimage.JavaParserReflectionFeature` registers JavaParser AST node fields for hosted reflection.

Picocli's annotation processor generates CLI reflection and resource metadata during `:frmtr-cli:compileJava`. Proxy
metadata generation is disabled because the CLI does not require dynamic proxy entries and GraalVM 25 deprecates
`proxy-config.json` files discovered under `META-INF/native-image`.

Build host and container choices are operational details owned by the README, Dockerfile, and Gradle native-image
configuration. Architecturally, native-image targets the build operating system and uses a native-image-capable JDK 25
launcher; it does not raise the bytecode level of the JVM artifacts consumed by the image.

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
