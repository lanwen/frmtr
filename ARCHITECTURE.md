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
consuming the same Java 21 CLI bytecode. Release automation may pass `-Pfrmtr.native.useEnvironmentHome=true` to let
the GraalVM native-image plugin use the `GRAALVM_HOME`/`JAVA_HOME` installation prepared by CI when Gradle's
native-image-capable toolchain detection does not recognize that installation.

The root also owns repo-local helper tasks that run the current CLI over this checkout and exclude formatter fixture
corpora that contain formatter-sensitive or intentionally invalid Java samples.

### Distribution Channels

Only the library modules that external adapters need, `:frmtr-core` and `:frmtr-tooling`, are staged as Central-bound
`mavenJava` publications. The Gradle plugin uses Gradle's plugin development and publishing model for its plugin and
marker publications. The CLI is distributed as an application/native executable rather than as a Maven library, and
`:frmtr-native-image-support` stays a build-time companion visible only to native-image builds and native tests.
JReleaser owns the GitHub release and binary-distribution metadata in `jreleaser.yml`, including the project-level
copyright that mirrors the root MIT license. The release publish job stages Central artifacts and runs
`jreleaserConfig` before creating the Git tag, so configuration validation happens before remote release state changes.
Homebrew publication runs through the reusable `Publish Homebrew` workflow and uses the separate `jreleaser-brew.yml`
config so the tap job validates only GitHub release and Homebrew packaging metadata, not Maven Central deploy or PGP
signing secrets.
Release preparation updates the Gradle project version, README Gradle plugin snippets, and the JBake site version
property so published documentation shows the same released coordinate as the published artifacts.

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
  items per line as fit instead of being all-or-nothing like a `Group`. The list starts and ends with content, so a
  well-formed list is empty or odd-length; the factory rejects a non-empty even-length list, whose trailing separator the
  pairwise render walk would otherwise silently drop. To an enclosing group it measures as the flat concatenation of all
  its parts (a safe over-estimate). Its first Java-printer consumer is the throws-clause printer, which greedily packs an
  overflowing `throws ...` exception list across continuation lines instead of leaving it as one unbreakable line (see
  `ThrowsClausePrinter`).
- `ConditionalGroup` holds an ordered list of layout alternatives and renders the first whose flat layout fits the space
  left on the current line (rendered flat), falling back to the last alternative in break mode when none fit. This is the
  Prettier-style conditional group: ranking the alternatives (narrowest flat layout first, an always-valid layout last)
  is the caller's job, while the renderer only picks among them by its own flat-fit measurement. Because every non-last
  alternative is selected purely by flat fit, **only the last alternative may be a broken/multi-line layout** — a
  non-last alternative carrying a forced break (`HardLine`/`BreakParent`) can never fit flat and is therefore dead. The
  first alternative must be the narrowest flat layout, because an enclosing group sizes the whole conditional group by
  its first alternative (a safe over-estimate only when the first is narrowest). The factory rejects an empty list
  ("render nothing" is not a layout choice) and treats a singleton as an unconditional flat-or-break fallback. It does
  **not** encode predicate-gated selection or ranking among multiple broken layouts, so it does not subsume the
  source/structural-predicate-gated `Optional<Doc>` layout dispatch in `MethodCallChainPrinter`. It is an additive
  primitive; `MethodCallChainPrinter` adopts `BestFitting` (below), not `ConditionalGroup`.
- `BestFitting` holds an ordered, flattest-first list of layout alternatives and renders the one that **fits, then
  minimizes rendered line count** at the live output column, with a deterministic tie-break. It is the capability `ConditionalGroup`
  structurally lacks (layout-decision-model rule B8): a conditional group offers N flat candidates plus exactly one
  broken fallback and chooses purely by flat fit, so it cannot rank two *broken* shapes against each other; a
  best-fitting node can, which is why — unlike a conditional group — **a non-first alternative MAY contain a forced
  break**. The last alternative must be renderable at any width, and the first (flattest) is the representative width an
  enclosing group sizes the node by. The factory rejects an empty list; the "may contain a forced break" freedom is
  intentionally not asserted (mirroring `ConditionalGroup`'s inverse note, to avoid inverting the `Doc` → renderer
  layering). The tie-break (rule D16) is strict and layered — a layout that fits (no line exceeds the width) beats any
  that overflows regardless of line count; then, **among the fitting candidates, a strictly higher per-alternative
  `priority` wins** (convergence-redesign Mechanism 2); then within one fit-and-priority class fewer lines, then less
  overflow, then the earliest (flattest) index — so it is deterministic and therefore idempotent. The `priority` key sits
  deliberately between the fit gate and line count: after fit so it can never rescue an overflowing alternative (the
  overflow gate stays primary), before line count so a caller can prefer a more-broken shape (e.g. an opener-attached
  argument-break) over a fewer-lines collapse when both fit. It is carried as a parallel `int[] priorities` on the node
  (`Doc.bestFitting(alternatives, priorities)`); the existing `Doc.bestFitting(alternatives)` factory defaults it to
  all-zero, which makes the key a no-op (every candidate ties on priority) and reduces the ranking to the fewest-lines
  metric — so the key stays dormant for every caller that sets no priority. Its first consumer is the initializer
  single-call convergence (`VariableInitializerLayout.rankedSimpleRootSingleCallConvergence`, #191), which emits
  `bestFitting([argument-break@1, collapse@0])` so the opener-attached argument-break is preferred over the fewer-lines
  collapse whenever it fits. The overflow gate is what lets a printer route a fan-out-versus-argument-break choice through
  `bestFitting`: a fitting fan-out can never be outranked by an argument-break whose opener overruns the width. Ranking is
  bounded for linear time and native-image safety: only the first `DocWidths.MAX_BEST_FITTING_ALTERNATIVES` (8)
  alternatives are measured, and a best-fitting node nested past `DocWidths.MAX_BEST_FITTING_DEPTH` (4) collapses to its
  first alternative instead of being ranked. `MethodCallChainPrinter`
  is the first Java printer to emit it (layout-decision-model milestone LDM-3): a comment-free, width-driven single-segment
  chain whose final segment carries breakable arguments emits `bestFitting([compact-with-broken-segment, one-per-line
  fan-out])` and lets the renderer rank the two broken shapes at the real output column instead of committing to one via a
  fixed-column `LayoutWidth` probe. The emission is gated to width-driven, source-shape-neutral chains only (see
  `MethodCallChainPrinter` below), and because the ranking agrees with the retained probes at the real column the fixture
  corpus stays byte-identical.
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
the same `DocWidths` fit authority and renders only the chosen one, so exactly one alternative reaches the output. For a
`BestFitting` it delegates the winner choice to `DocWidths.Measurement.chooseBestFitting`, which ranks the alternatives by
rendered line count (via `measureLineCount`) at the live output column, then renders only the winner once — the ranking
probes are side-effect-free, so they never touch the running output, column, group-mode map, or line-suffix buffer.
`DocExplainRenderer` mirrors the suffix buffer, the group-mode map, the conditional-group alternative selection, and the
best-fitting ranking (through the same `chooseBestFitting`) so its replayed column cursor and chosen alternative stay
identical to what `DocRenderer` emits.
`DocWidths` is the single flat-width authority: it owns the flat-width measurement and the fit test, so `DocRenderer`
and any observer of its decisions compute fit identically and a fit decision can never diverge from the width number
reported for it. It also owns `measureLineCount`, a side-effect-free replay of the rendering walk that counts the
newlines and overflow a document would render into — the metric for ranking `BestFitting` alternatives. `render`, the
explain trace, and `measureLineCount` are separate walks that must agree; a congruence test pins
`measureLineCount(doc).lines()` to the number of newlines `render(doc)` emits so the mirror cannot silently drift, and
line counting is never written into the flat-width cache.

`DocDebugRenderer` provides a stable structural dump of the document tree so formatter maintainers can inspect break
opportunities, indentation scopes, groups, flat-vs-broken alternatives, and high-level formatter rule labels. Label
names are diagnostic formatter-internal names and may evolve when rule boundaries move. `Frmtr.debugDoc(...)` exposes
that view for one Java source string after parsing, transforms, and Java printing, without invoking width-based
rendering.

`DocExplainRenderer` re-walks a document with the same `DocWidths` fit logic and column accounting as `DocRenderer` to
trace why each line laid out as it did, producing the presentation-free `DocExplanation` model. It records the
renderer's own width-driven `Group` breaks (with flat width, columns available, and start column) and the forced hard
line breaks a Java printer emitted as policy, attributing each forced break to the nearest enclosing rule label. It
records the other two width-deciding primitives with the same arithmetic: a `Fill`'s greedy per-separator FLAT/BREAK
choices as a `FillDecision` (one entry per separator, each carrying the flat width of `separator + next content`, the
columns left, and the column the separator started at), and a `ConditionalGroup`'s alternative selection as a
`ConditionalGroupDecision` (the chosen index, whether it was the break-mode fallback, and each probed alternative's flat
width and fit so the report can show why earlier alternatives were skipped), and a `BestFitting`'s ranked-alternative
selection as a `BestFittingDecision` (the chosen index and each measured alternative's rendered line count, overflow, and
per-alternative priority, marking the winner, so the report can show why the flatter alternatives lost — and, when a
consumer set a preference, why a higher-line alternative won on priority). The CLI prints a non-zero priority alongside
the line count and omits it for the common all-zero default. The CLI surfaces broken
fills, break-mode conditional groups, and wrapping best-fitting layouts in the same "why it wrapped" section as group
breaks.

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
- `FormatterRunner.checkVerified(...)` is the only path that scans formatted output for breakable over-width lines. It
  threads a `reportOverWidth` flag into the shared `checkFile` step; when set, the formatted result is walked through
  `dev.lanwen.frmtr.OverWidthLines`, whose `Scanner` masks literals/comments, carries text-block/block-comment state,
  and tracks formatter-off / `frmtr-ignore` pragma ranges so lines the formatter emitted verbatim are never flagged; the
  findings are attached to each `FormatFileResult.overWidthLines()`. The
  findings are purely informational — they never touch a result's `changed`/`failed` status — and `check(...)`,
  `write(...)`, and `writeVerified(...)` pass `false`, so only `--check --verify` populates them. `FormatFileResult`
  gained the `overWidthLines` component additively (a back-compat constructor delegates with an empty list), so the
  Gradle plugin and all existing call sites are unaffected.
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
collapsed/expanded perturbation, failing on any genuine drop. The exclusion list it carries for known, tracked drops is
currently empty, and the net also fails on a stale (no-longer-needed) exclusion. The stricter "each comment claimed at
most once" invariant lives behind a separate `dev.lanwen.frmtr.debug.guardrails.strict-claims` toggle. The B2 ownership
consolidation migrated *every* comment family (trailing, leading, adjacent, own, orphan, interleaved) to the explicit
ownership pre-pass, and the comment-claim enabler then moved all comment rendering onto the claim-neutral
`CommentTracker.ownedComment` rail (described below), so a comment renders only from its single recorded owner slot and
can no longer be claimed twice; the invariant now holds structurally across the whole `frmtr-core` suite — golden
fixtures, comment-presence diagnostics, and the whitespace-perturbed idempotence property test. The toggle is **on by
default** in `frmtr-core/build.gradle.kts`, making "each comment is claimed at most once" a CI gate alongside the
AST-equivalence verify mode. (The separate `dev.lanwen.frmtr.debug.guardrails` comment-*drop* check is still off in the build: a residual
set of raw-text-embedded comments — multi-catch union alternatives, for-loop variable comments, switch labels, labeled
statements, unnamed-variable patterns — reaches output as raw token text without being raw-accounted, so enabling that
check is a separate follow-up.) All toggles live behind `FormatterGuardrails`; see
[docs/java-formatter-internals.md](docs/java-formatter-internals.md) for details.

`JavaPrinter` creates one per-run `JavaFormatContext`, constructs shared type rendering, and coordinates the three
printer composer groups: `ExpressionPrinters`, `DeclarationPrinters`, and `StatementPrinters`. Formatter ownership then
narrows through envelope gates, dispatcher boundaries, and specialized declaration, statement, expression, type,
comment, raw-source, and recovery helpers. See [docs/java-formatter-internals.md](docs/java-formatter-internals.md) for
those collaborator boundaries and [docs/formatter-coverage.md](docs/formatter-coverage.md) for the AST ownership map.

Comment preservation is centralized through a per-run `JavaCommentMap`, read-only `JavaCommentPlacementPolicy` queries,
and a `CommentTracker` ownership pre-pass so adjacent leading clusters, line comments inside annotation arrays, and line
comments before fluent-chain segments are printed once while syntax-specific printers keep the surrounding layout.
Comment ownership is decided in a record-only dry-run pre-pass before the real render, replacing the old build-order
"first-claim-wins" race with a single owner settled up front per comment. `JavaPrinter.print` calls
`CommentTracker.beginRecording()`, runs the same print traversal once with each comment's owner recorded but nothing
committed, then `CommentTracker.endRecordingAndReset(...)` discards that scratch document and resets the per-render
mutable state. In record mode the first `(anchor, OwnerSlot)` that offers a comment is written into the `ownership` map
as its single owning `OwnerKey` (anchor compared by identity), using the map itself as the once-only set, so the recorded
owner is exactly the emergent first claimant of the forward print traversal — which a pure source-order rule provably
diverges from on the contested families, so the traversal is reproduced rather than approximated. The pre-pass records,
and `CommentTracker.ownsHere` then gates, *every* family (trailing, leading, adjacent, own, orphan, interleaved), so a
comment renders only from the slot recorded as its owner. `endRecordingAndReset` clears exactly the state that
accumulates during the traversal — `CommentTracker.printed`/`rawRendered`, the `LayoutDecisionLog`, and the
`FormatterPragmas` enabled/disabled range state — while keeping the `JavaCommentPlacementPolicy` comment map and its pure
AST-derived caches, so the dry-run costs roughly a second print but never a second parse.

All comment rendering then flows through the claim-neutral `CommentTracker.ownedComment` rail: a comment renders iff the
dry-run recorded *this* `(node, slot)` as its owner (`ownsHere`), and every non-owner slot renders `Doc.EMPTY`. Emptiness
is a pure function of the recorded ownership, not of a build-time `printed`-set mutation — the real render never claims a
comment into `printed`, so the old `.filter(ownsHere).filter(claim)` render pattern and its `printed` claim side-effect
are gone, and `CommentTracker.claim()` / `isPrinted()` are left orphaned. Because the rail mutates no claim state, an
owner may emit the same comment Doc in more than one eagerly-built ranked layout arm (a `Doc.bestFitting` /
`Doc.conditionalGroup` alternative) without dropping or duplicating it; the renderer keeps only the arm it picks. Two
paths that legitimately co-offer the same comment are kept claim-once by giving each a distinct `OwnerSlot` on a shared
anchor node (or by anchoring to the container rather than an element node that may coincide with an inner render's
anchor), so the pre-pass records one owner and every other slot renders empty by ownership.

Width-deciding printers (`MethodCallPrinter`, `MethodCallChainPrinter`, and their helpers) choose a layout by eagerly
building an `Optional<Doc>` candidate to measure its fit. While comment rendering was still a build-time claim, building
such a candidate claimed any comments inside it, so a *discarded* candidate left those comments claimed for a render that
never reached output; `CommentTracker.speculatively(...)` wrapped each probe in a re-entrant scope that snapshotted and,
on an empty probe, rolled back the comment-claim state (plus the `LayoutDecisionLog` length and the `FormatterPragmas`
range flag), so a discarded probe contributed no claim, no phantom `--explain` width record, and no stray open
`@formatter:off` range. That rollback is redundant now that rendering is claim-neutral — an eagerly-built, discarded
candidate commits no claim through `ownedComment`, so there is nothing to roll back — so every `speculatively` scope was
inlined and the method deleted. The benign neighbor re-offers that are not discarded probes (a chain segment's name
comment vs. its leading/trailing slot, an if/else between-clause block comment vs. the else-leading slot, an argument's
inline trailing comment vs. its enclosing list or assignment, a comment shared by two adjacent empty blocks under
whitespace perturbation) are kept claim-once by the distinct-`OwnerSlot` rule above, never by a speculative rollback.

Comment *text* rendering lives in `JavaFormatter.commentDoc`, which routes each comment by parser kind. Javadoc is
canonically reflowed through `JavadocComment.toString()` so authors' `*` continuation rows re-align consistently — except
for decorative banner dividers. JavaParser classifies any `/*`-comment whose opener carries three or more stars (e.g.
`/*****`) as Javadoc, so a multi-line banner whose opener/closer rows are pure asterisk-art reaches the Javadoc branch;
reflowing it would mutate those rows (a `*****` row becomes `* ***`), which is byte-invisible to AST-equivalence but
registers as a dropped comment under the lexer-multiset comment-presence net. `commentDoc` therefore detects banners with
the narrow `isBannerComment` predicate (opener is `/*` plus three-or-more bare stars, or closer is a bare three-or-more
star run before the delimiter) and preserves them verbatim through the same star-aligned `normalizeBlockComment` path used
for ordinary block comments, leaving normal Javadoc reflow unchanged. Single-line Javadoc (including single-line banners)
is already emitted verbatim from its raw token text.

Complex Java layout rules are factored into dedicated helpers rather than embedded in broad dispatchers. `LayoutWidth`
centralizes indentation baselines for width probes, source-shape helpers preserve meaningful existing multiline forms,
initializer helpers coordinate declaration-local wrapping, and chain helpers keep method-call source planning out of
ordinary argument dispatch. When a construct shares its first line with a prefix that the construct cannot see — an
assignment `target op `, a declarator `name = ` — the caller threads that prefix into the construct's width probe as a
`firstLineWidth` closure rather than measuring the bare construct at the block indent. The method-call chain stay-flat
gate (`MethodCallChainPrinter`) measures with that in-scope `firstLineWidth` (defaulting to the prefix-blind
`lineWidth(lineBudget)` for prefix-less callers), and both the assignment value path (`MethodCallPrinter`'s broken
method-call assignment) and the variable initializer path (`VariableInitializerLayout`) build the prefix-aware closure,
so a chain that fits at the block indent but overflows once the assignment/initializer prefix is counted breaks instead
of being emitted flat over width. The same `lineBudget`/`firstLineWidth` channel also carries nesting depth: when a
chain is rendered as a wrapped call argument or a nested initializer it sits at its enclosing argument list's
continuation indentation, deeper than the current-member baseline the AUTO entry assumes, so the argument-list dispatcher
(`MethodCallPrinter.methodCallArgumentDoc`) threads the continuation baseline (`layoutWidth::continuationStatement`) into the chain. When the resulting probe
shows the chain over width but its short final segment (`.toRetry()`, `.build()`) has no arguments to wrap, the chain
printer breaks the root's own argument list and glues the segment to its close, the same shape a source-multiline root
already produces. Layout-decision-model milestone LDM-3 routes one slice of this single-segment cluster through the
`BestFitting` primitive instead of that fixed-column probe: `MethodCallChainPrinter.rankedSingleSegmentChain` emits
`bestFitting([compact-with-broken-segment, one-per-line fan-out])` and lets the renderer rank the two broken shapes by
rendered line count at the real output column. Two invariants keep the emission safe. First, **source-shape eligibility
runs before ranking** — the ranker fires only for the width-driven, source-neutral case (root rendered through ordinary
expression dispatch, chain and root arguments not split across source lines, a final segment with breakable non-lambda
arguments); a promoted/builder/broken-object-creation root or a deliberately-multiline chain is a source-preserved shape
selected by the gates above and is never a width-ranked alternative, so ranking cannot override it. Second, both ranked
arms render the root and selector through the same shared renderers the imperative deferral uses, so a comment an admitted
chain carries is preserved identically whichever arm the renderer keeps (guarded by `CommentPresenceDiagnosticTest`); the
`!MethodCallChainAnalysis.hasComments()` bail these rankers once carried — added when a discarded eager candidate could
double-claim comments — became redundant once every comment moved onto the claim-neutral rail and was lifted (comment-claim
enabler, Phase D), so a comment-bearing single-segment chain now ranks like any other. Because the ranking agrees with the
retained probe at the real column, the fixture corpus is byte-identical. Milestone LDM-3g (#210) adds the
object-creation-rooted sibling `MethodCallChainPrinter.rankedObjectRootSingleSegmentChain`, which ranks the same two
broken shapes for a source-compact constructor root (`new Type(args).selector(...)`) under the identical
source-shape gates (its `!hasComments` bail lifted alongside the method-root ranker's) and reuses the same
`compactRootWithBrokenFinalSegment` for the compact alternative
(it already builds that shape for object-creation roots). It is reached in the forced-chain single-segment branch, so
`ReturnExpressionPrinter` drops the object-creation-rooted-chain pre-empt from `preemptedReturnValue`: an
object-creation-rooted return chain now falls through to the return's flat-versus-broken `conditionalGroup`, whose broken
arm is this ranked `bestFitting` (the return `conditionalGroup` measures flat-versus-broken at the real column, then the
nested `bestFitting` ranks the two broken shapes). This is again byte-identical on the corpus because the ranking agrees
with the retired first-line probe. The `one-per-line fan-out` alternative both rankers rank against the compact shape is
built by the shared `MethodCallChainPrinter.chainFanOut(root, calls, tail, layout)` helper (convergence-redesign
Mechanism 1, slice 2): it constructs the fan-out — `root` then each selector on its own dotted continuation line via
`chainContinuation`, each segment — including the lone tail of a single-selector fan (`ChainFanLayout.fanSingleSelectorLayout`),
now rendered through the same on-own-line `methodCallChainSegments` group as the multi-selector fan — measured at its
continuation column, so a single-simple-argument tail stays compact and a non-simple / multi-argument tail
(`CONFIG_MAPPER.readValue(node, T.class)` ⏎ `.withSourceKey(section.getKey())`) breaks by width there rather than by its
stale beside-a-token source column; the earlier source-column measurement made such a tail explode from a flat source and
collapse on the re-format, the testcontainers `RegistryAuthLocator` one-pass idempotence break (fixture
`single-selector-fan-nonsimple-argument`) — **from the AST alone, never gating on `openerFits` or `sourceMultilineChain`**. That source-neutrality
is invisible to these two callers (they only reach the builder for width-driven, source-neutral single-segment chains, and
it reproduces the exact `Doc` they built inline before the extraction, so the corpus stays byte-identical), but it is the
point of the extraction: it is the builder the initializer's collapse arm will route through in convergence-redesign
slice 3, where the fan-out must exist on every input regardless of source shape (the `field-init-typelike-root-idempotence`
Blocker 1). Chain-path-unification slice U1 (#190) routes the **multi-segment fall-through** of
`MethodCallChainPrinter.methodCallChain` — the imperative one-segment-per-line tail that renders `root` then every
selector on its own dotted continuation line — through this same `chainFanOut` builder rather than reconstructing that
`Doc.concat(rootDoc, chainContinuation(root, segments))` shape inline, consolidating the fan-out onto the single builder
so the later slices' rankers can list it as a named arm. The delegation fires only when the fall-through's `rootDoc` is
still the plain `expressionRenderer.format(root, root())` doc `chainFanOut` rebuilds — a comment-free
`EXPRESSION_RENDERER` root that did not fall to the broken-multi-argument shape (`expressionRenderedChainRoot`); a
promoted / grouped / broken-object-creation root, a first-segment-attached root, or a root-trailing-comment-wrapped root
produces a different `rootDoc` and keeps the inline construction. The comment gate admits a comment-free chain, or one
whose only comment is a last-selector trailing line comment (`chainCommentsAreOnlyTrailingLine`) — the shape `chainFanOut`
preserves because `methodCallChainSegments` re-emits that trailing slot (comment-claim enabler, Phase D). Every other
comment family keeps the inline construction: the fall-through re-renders the root a second time inside `chainFanOut`
(discarding the `rootDoc` it built earlier), and re-rendering a root / segment / between-selector comment there would drop
or destabilize it. This mirrors the trailing-comment relaxation `chainFansByCanonicalRuleAdmittingTrailingComment` already
applies at the caller-level `canonicalFanChain` route. Because the delegated shape is byte-identical to the discarded
inline shape, the corpus stays byte-identical (U1 is a pure consolidation, not a decision change). Chain-path-unification slice U2 (#190)
then routes the **return chain's general width-driven broken arm** through the same engine:
`ReturnExpressionPrinter.returnWithForcedMethodCallChain` used to hand-pick the compact-root-with-broken-final-segment
(CRBFS) shape first (its `compactRootWithBrokenFinalChainSegment` pre-empt) before falling to the forced one-per-line
fan-out, so the CRBFS-versus-fan verdict for a name-rooted return chain — single-segment method root or promoted
type-root (`Optional.ofNullable(x).orElse(y)`, `PipelineFactory.wrap(p).withFailureRecovery(...)`) — was chosen
imperatively rather than at the rendered column. It now emits, for a comment-free chain whose compact shape exists, a
single `Doc.bestFitting([CRBFS, forced-fan-out], new int[] {1, 0})`: the CRBFS arm carries priority 1 so that **among the
arms that fit** it is kept regardless of line count, reproducing the imperative pre-empt byte-identically (the pre-empt
returned CRBFS exactly when its opener fit, i.e. exactly when the priority-1 arm now fits). The genuine multi-segment
name-root chain (three or more selectors) has no compact competitor — `compactRootWithBrokenFinalChainSegment` returns
empty — so it keeps falling to the fan-out, now the `chainFanOut` arm U1 named. The only place the ranked node departs
from the old pre-empt is when the compact shape overflows at the rendered column (a chain co-located after an unbroken
`if (...) ... else return `, whose deep first line no shape can rescue): priority never rescues an overflowing arm, so
`bestFitting` falls to the fan-out, which uses fewer lines for the same unavoidable overflow — a strict improvement, and
idempotent because the renderer re-ranks the same two AST-built candidates every pass. Only comment-free chains build both
arms eagerly; a comment-bearing return value stays on the imperative cascade upstream (this return arm still gates
comment-free — unlike the single-segment rankers, whose `!hasComments` bail the comment-claim enabler lifted in Phase D).
The two source-multiline return branches that still pre-empt — the enclosed binary and
the source-multiline object creation — stay imperative pending LDM-4 (the binary/object-creation printers exposing their
own ranked candidates); the ranker cannot override those source-preserved shapes today. The single-attachable-argument
hug gates (`MethodCallPrinter.singleMethodCallArgument` /
`singleObjectCreationArgument`, which keep `outer(inner(` on one opener when the author broke a lone inner call or
constructor argument) apply the same prefix-aware width rule through `attachedOpenerOverflows`: rather than measuring the
hugged opener at the bare call indent, they add the value prefix that shares the call's first line, recovered as the
call's source start column minus its enclosing statement's start column (a reindentation-invariant delta) over the
statement's rendered indentation (`LayoutWidth.nodeIndentWidth`). An attached opener that fits at the assignment column is
hugged; one that overflows once the prefix is counted breaks instead of being frozen over width. The expression-lambda
packed-body opener gates (`ExpressionLambdaArgumentLayout`'s `packedBodyCall*` / `packedObjectCreation*` shapes, which
keep `call(() -> inner(` on one opener when the author broke a lambda body) apply the same depth-aware rule through
`openerOverflows`: they measure the opener at the lambda's rendered indentation (`LayoutWidth.nodeIndentWidth`, which
counts every enclosing type and block) and take the wider of that and the historical shallow baseline, so a hug nested
inside `if`/`for` bodies that overflows at its true depth breaks instead of being frozen over width while shallow fitting
hugs stay unchanged. When the lambda body is instead a **method-call chain** that reaches the End-state A canonical-fan
threshold (`MethodCallChainPrinter.lambdaBodyChainFansByCanonicalRule`, the U7 lambda-body position of the canonical-fan
cutover), `ExpressionLambdaArgumentLayout` fans it by dots — the chain root on the lambda-header line, each
`.selector(...)` on its own dotted continuation line, a single-simple-argument tail kept compact, and the **enclosing
call's `)` dedented to its own line at the opener's column**
(`someCall(x -> assertThat(x)`⏎`.extracting(...)`⏎`.containsOnly("v")`⏎`)`; `verifier.each(h -> journalWriter`⏎`.atInfo()`⏎`.addValue(...)`⏎`.log(...))`)
— rather than packing the flat chain and opening only the tail argument (`…containsOnly(`⏎`"v"`⏎`)`). This is the same
source-neutral fan every other chain position cuts over to: a chain that structurally fans by link count / root kind fans
here **even when the flat body would fit**, matching gjf/prettier's "one segment per line once the chain is a builder"
convention. The dedented close is the same shape a broken argument list renders (`foo(`⏎`arg`⏎`)`, close back at the
statement-start column) and the packed lambda-body shapes' `PackedLambdaBody.CLOSING_ON_OWN_LINE` produce: the fanned
chain carries its own continuation indent while the trailing `HARD_LINE` + `)` stay outside any extra indent, so they land
at the enclosing statement's column; a header opening several calls before the break stacks their closes on that one
dedented line. The branch runs before the compact-flat shape (`compactBodyWithClosingLine`) — so a fan-threshold chain
fans instead of staying flat — and before the packed-opener shapes (`packedLambdaBody`).

This lambda-body fan is one host position of the **End-state A canonical-fan cutover**, the formatter's chain-wrapping
policy: a multi-link chain that meets the structural fan threshold (`MethodCallChainSourcePlanner.chainBreaksByRule` —
a call/factory/constructor root at two or more selectors, a plain receiver `NameExpr`/`FieldAccessExpr`/`this`/`super`
at three or more) fans one selector per dotted line **even when the flat form would fit**, matching google-java-format /
prettier-java's "one segment per line once the chain is a builder" convention. #163 tried this rule imperatively and
regressed idempotence (source-shape-sensitive per-printer decisions oscillated between passes); the cutover instead makes
each enclosing attach/break/dispatch verdict **renderer-resolved** — it emits the one source-neutral fan Doc from
`MethodCallChainPrinter.chainFanOut` (a pure function of the AST, built once at `LayoutContext.root()` and shared across
arms) and ranks it against the attached/compact alternative with `Doc.bestFitting`/`Doc.conditionalGroup` at the true
output column, so the shape is a fixpoint by construction. The fan-position decision — whether a chain fans at all — is
expressed as the first named `BreakRule` (`canonical-fan`), resolved through a `BreakRuleRegistry` in
`ChainFanLayout` (reached via the `MethodCallChainPrinter.canonicalFanChain` delegate): the Stage-0 seam of the
reprint-by-default break-rule model (`docs/proposals/reprint-by-default-break-rules.md`). A `BreakRule<C>` pairs a pure
predicate over a construct-specific candidate (here a `ChainFanRequest` carrying the chain plus the caller's
final-segment suffix and `LayoutContext`) with the source-neutral `Doc` it emits, resolved first-match-wins — distinct
from the type-dispatched `JavaFormatRule` node→`Doc` handoff. The `chainFanOut` fan sub-shapes (factory-root fold,
single-selector, trivial-receiver attach, fanned selectors) are a second `BreakRuleRegistry`, also in `ChainFanLayout`:
the break-rules Stage-1b extraction relocated both registries, the source-neutral fan builders, and the AST-only
fan-admission predicates out of `MethodCallChainPrinter`, which keeps thin delegates for the fan entry points other
printers still call. The same seam is replicated at every chain host: local-variable
and field initializers (`VariableInitializerLayout.variableInitializerFanBestFitting`), assignment RHS, `return`,
method-call and object-creation arguments (`MethodCallPrinter`), binary / logical / string-concat operands
(`BinaryExpressionPrinter` + `BreakableArgumentExpressionPrinter`), statement expressions, the expression-lambda body
(above), and the factory- and object-creation chain root itself (rendered as a width-driven `Doc.group` so its argument
list breaks at the true column). Most comment-bearing chains are withheld from the fan and kept on the imperative
comment-preserving cascade — including a trailing line comment in the root→first-selector gap
(`MethodCallChainPrinter.rootHasTrailingLineCommentBeforeFirstSegment`, folded into `hasComments`), whose omission would
otherwise drop the comment when the source-neutral root re-render discards it (comments are not AST nodes, so
`--verify`/AST-equivalence cannot catch such a drop — `CommentPresenceDiagnosticTest` is the gate). The one exception is a
chain whose only comment is a last-selector trailing line comment: the fan-route gate
(`chainFansByCanonicalRuleAdmittingTrailingComment`, via `chainCommentsAreOnlyTrailingLine`) admits it, because the fan
renders the whole chain once and `methodCallChainSegments` re-emits that trailing slot through the claim-neutral rail
(comment-claim enabler, Phase D). The cutover is
idempotence net-positive on the corpus — apache/kafka 179→151, apache/camel 193→186 non-idempotent files, AST-equivalent
throughout — and its residual per-file oscillations are tracked as follow-ups: the chain-selector lambda-body hug
(`huggableExpressionLambdaArguments` source-neutrality), a condition-path binary re-break, and a trailing-comment reflow.

The same **structural fan-at-three** convention governs array initializers: `ArrayExpressionPrinter` breaks an
`ArrayInitializerExpr` with three or more elements one-element-per-line **even when the compact `{a, b, c}` form would
fit**, whether the array was introduced by a bare `= {...}` initializer or a `new T[] {...}` creation (both compact gates
consult `arrayInitializerFansOnePerLine`). One- and two-element arrays stay compact and still break by width when they
overflow; empty and comment-only initializers are unaffected. Like the chain rule this is a pure element-count decision
on the AST with no source-shape read, so it is a fixpoint by construction. Annotation member arrays keep their own
width-driven shape (`AnnotationExpressionPrinter`) and are not routed through this rule.

Three google-java-format / prettier-java **attach nuances** refine that one-selector-per-line default, each keyed only on
AST structure (never width or source shape) so they stay fixpoints:

- **Trivial-receiver first-selector attach.** When the fan root is a *trivial receiver* — a bare
  `NameExpr`/`FieldAccessExpr`/`this`/`super` (`MethodCallChainPrinter.chainRootIsTrivialReceiver`), not a
  call/factory/constructor root — `chainFanOut` keeps `root.firstSelector()` on the opening line and fans from the SECOND
  selector: `return argument.getRange()`⏎`.map(...)`, `((OffsetFetchRequestData) response.unsentRequests.get(0)`⏎
  `.requestBuilder()`…, and the lambda-body form `dispatchJob -> orderEvent.validateOrder()`⏎`.deliveryPlan()`… (anchored on
  the `->` line via `LambdaExpressionPrinter.parenthesizedLambdaBreak` / `lambdaBodyChainArrowBestFitting`, not broken after
  the arrow). Gated to `calls.size() >= 2` — PR #279 review (#2) extended it from the canonical fan (three or more
  selectors) down to a width-driven **two-selector** chain (`entry.state()`⏎`.shouldPrioritize(...)`, `items.stream()`⏎
  `.anyMatch(...)`, "can the receiver and the first selector stick together until there is a space?"), to a first selector
  that is ATTACH-SAFE (`firstSelectorAttachesSafely` — no type arguments and only simple leaf arguments, so it is an atomic
  token that never opens its own broken argument list), and to a root at least one indent unit wide
  (`rootAvoidsShortRootPadding`, so `chainContinuation`'s short-root padding branch — which diverges from the imperative
  fall-through's fan-from-first shape — never fires). Extending below the canonical threshold stays a fixpoint because both
  remaining gates are structural: the attach-safe selector renders as atomic text that cannot re-break, and the fan/no-fan
  decision itself is a width probe on the invariant flat compact form that attaching cannot change (apache/kafka and
  apache/camel idempotence unchanged). A call/factory/constructor root keeps the fan-from-first shape. A two-selector chain
  whose only comment is a last-selector trailing line comment (`chainCommentsAreOnlyTrailingLine`) now also reaches this
  attach: the relaxed width-driven two-selector fan gate routes it through `chainFanOut` instead of the imperative
  root-alone fan it fell to while the fan withheld comment-bearing chains, so its comment-free attach-safe first selector
  stays on the receiver line exactly like the comment-free sibling (fixture
  `two-selector-trailing-comment-receiver-attach`, comment-claim enabler Phase D).
- **Single expression-lambda argument hugs its call opener.** A fanned chain selector whose sole argument is an expression
  lambda keeps the lambda opener glued to the selector rather than breaking the selector parenthesis onto its own line,
  restoring the `huggableExpressionLambdaArguments` hug the one-per-line fan over-broke. Review round 2 broadened this from
  the round-1 object-creation-only hug to the SOURCE-NEUTRAL hug shapes (`ChainSelectorLambdaLayout.expressionBodyOpenerHug`):
  an OBJECT CREATION (`.reduce((left, right) -> new ImageCounter(`⏎…), an object-creation-rooted chain with a non-empty
  outermost call (`.map(listener -> new VotersEndpoint().setName(…).setHost(`⏎…), and a TERNARY
  (`.onErrorResume(ex -> cond`⏎`? then`⏎`: else`⏎`)`, review comment #2, whose shared `packedConditionalBody` hug is a pure
  width function of the AST). Review round 3 adds a LOGICAL BINARY body (`&&`/`||`,
  `.map(region -> region.beginOffset() == expected.beginOffset()`⏎`&& region.endOffset() == expected.endOffset()`⏎`)`),
  hugging the first operand on the selector line, stacking each following operand one per line, and dedenting the close.
  Unlike the object-creation/ternary hug it is built through the DIRECT source-neutral
  `ExpressionLambdaArgumentLayout.logicalBinaryLambdaBodyOpenerHug` (a pure `nestedLines` AST render with a fixed dedented
  close), NOT the shared `huggableExpressionLambdaArguments` `plan` path — whose `sourceMultilineBody` entry gate and
  source-shaped close placement (`ExpressionLambdaClosingLayout.callClosingStaysOnLambdaBodyLine`) flip the shape across
  passes, which is why round 2 dropped the binary hug. The separately-gated `binaryMethodCallBodyWithOpener`
  (`sourceMultilineBinaryMethodCallBody`, which oscillated `.map(x -> x.f(a) == ALLOWED)` in `AuthHelper`) is never touched;
  a top-level RELATIONAL body (`x -> f(...) == ALLOWED`) is not a logical binary, so it stays on the source-neutral
  broken-segment shape and does not oscillate. A SINGLE-method-call body whose
  source lambda body started on the selector line — for which the shared renderer hands back only a degenerate flat
  one-liner — hugs its opener through a direct, source-neutral `ChainSelectorLambdaLayout.singleCallLambdaBodyOpenerHug`
  (`.forEach((tp, partitionData) -> replicaBuffer.addFetchedData(`⏎…⏎`))`, review comment #3), which wraps
  `ExpressionLambdaArgumentLayout.methodCallBodyWithOpener` in the selector parenthesis. Scoped to a FANNED selector
  (`segmentOnOwnLine`, a stable continuation column) and to an object-creation-rooted chain whose outermost call carries
  arguments (an empty trailing `.build()` has no argument list to break and would malform); a single-selector tail
  (`spanFor(x).orElseThrow(() -> new X(…))`) keeps the broken-segment shape.
- **PR #279 review — expression-lambda argument-opener cluster.** Three width-driven refinements keep the lambda header
  (`params ->`) on the selector/call line and lay the body out by width instead of dropping the arrow alone or collapsing
  the body onto one over-wide line:
  - *Fanned-selector true column.* A fanned chain selector whose lambda body is a CHAIN or a call carrying its own
    argument-lambda (a body that is NOT `bodyIsSingleCallSafeForBrokenSegment`) has its hug admitted at the selector's real
    continuation column (`MethodCallChainPrinter.fannedSelectorColumnWidth` = `nodeIndentWidth(selector) + indentUnit * 2`,
    widened with `Math.max` against the fixed `CONTINUATION` budget so it is monotone). The fixed budget under-counts that
    column by one indent level for a chain nested below a top-level statement, so a flat selector that overflows the real
    column but fits the budget read as "fits" and the shared `huggableExpressionLambdaArguments` renderer withheld the hug —
    breaking `.flatMap(`/`.map(` onto its own line or dropping the arrow. Single-call-safe bodies keep the fixed budget so
    their established `singleCallLambdaBodyOpenerHug` shapes do not churn. (The general case — a selector nested several
    argument levels deep, `.filter(param -> param.a().equals(b))` — still under-counts and is the `leftEdgePrefix` follow-up.)
  - *Block-lambda-in-chain-root receiver.* `LambdaExpressionPrinter.brokenNonBinaryLambdaBody` now guards its
    `brokenMethodCallRenderer` call with `brokenMethodCallReceiverCompactsCleanly` (the mirror of the identical
    `ExpressionLambdaArgumentLayout` guard). A `ctor -> Try.of(a, () -> { … }).getOrElseThrow(…)` return-lambda body whose
    chain receiver carries a BLOCK lambda no longer compacts that block onto one over-wide line; it falls through to the full
    chain printer, which renders the block multi-line.
  - *Single-selector object-creation-rooted chain.* `ExpressionLambdaArgumentLayout.overflowingHuggedObjectCreationRootChainBody`
    now admits a single-selector chain (`() -> new SessionReader(…).findSessions(…)`, scope is the `new X()` directly), routing
    it through `huggedLambdaBodyChain` so the constructor arguments break at the real column instead of collapsing the whole
    `new X(…).selector(` onto one over-wide line.

- **PR #279 review round 2 — lambda arrow-hug rule.** A lambda header (`params ->` / `() ->`) must never sit alone at the
  end of a line with its body dumped on the next line; when the body breaks, the body's first line hugs the arrow. Two
  width-driven generalizations close the round-1 residuals:
  - *Chain-body hug for call/chain-selector arguments.* `ExpressionLambdaArgumentLayout.huggableMethodCallArguments`, before
    its arrow-alone fallback, routes a lambda body that is a method-call CHAIN (its scope is itself a call) through
    `huggedLambdaBodyChain` — `forcedMethodCallChain` with `firstLine + " "` threaded as `leftEdgePrefix` — so the chain root
    hugs the arrow (`.map(rows -> receiver.stream()`⏎`.map(…)`… ; `.orElseGet(() -> WindowUsage.builder()`⏎`.tenantId(…)`… ;
    `withAuditMode("allow", () -> verifyNoFailure(() -> RouteLayout.render(`…) and every selector fans below. Admitted only
    when the chain genuinely must break — its root is NOT a bare call (a name/field/type/object-creation-rooted fluent chain
    that stays fanned once broken), OR its flat compact overflows even at the lower-bound threaded `columnWidth` (a bare-call
    root whose lambda/text-block argument forces multi-line). A bare-call-rooted body whose compact FITS
    (`.untilAsserted(() -> assertThat(chain).isTrue())`, fits flat on its own dedented line) matches neither signal and stays
    on the arrow-alone-with-flat-body fallback, so it does not oscillate flat⇄fanned. This subsumes the round-1
    bare-call (`overflowingHuggedBareRootChainBody`) and object-creation-root hugs for the clean-chain shapes those
    `chainCallsCanStayFlat` gates decline (name/field-rooted or lambda-selector-carrying chains).
  - *Standalone-lambda block-in-chain-root receiver.* `LambdaExpressionPrinter`, before its broken-after-arrow fallback, hugs
    a method-call chain body whose receiver carries a BLOCK lambda (`return ctor -> Try.of(a, () -> { … }).getOrElseThrow(…)`)
    onto the arrow line, letting only the contained block break. The body is rendered through the same
    `expressionRenderer.format` the fallback (via `brokenNonBinaryLambdaBody`'s `brokenMethodCallReceiverCompactsCleanly`
    guard) already uses, so the hugged body is byte-identical — the change is purely the arrow attach, a fixpoint.

  Residual foundation gap (idempotent, not over-width, but not the ideal shape): the return-position chain
  `probe.withVirtualTime(() -> new SessionReader(…)).expectSubscription()` still breaks `probe`⏎`.withVirtualTime` — the
  receiver+first-selector attach (`fanAttachesTrivialReceiverFirstSelector` / `firstSelectorAttachesSafely`) refuses a
  lambda-carrying first selector because attaching it renders the lambda body's fan at a shifted column that oscillates
  without the `leftEdgePrefix` threaded through the trivial-receiver attach. The arrow+opener there are already together
  (`() -> new SessionReader(`); only the receiver attach needs the `leftEdgePrefix` foundation. The #190 F2 segment-column
  slice retired the last source-shape gate (`lambdaBodyStartsAfterHeader`) on the sibling method-call-body variant
  (`probe.withVirtualTime(() -> sessionReader.findSessions(…))`), so it now fans to this same `probe`⏎`.withVirtualTime`
  fixpoint on both passes — an idempotent fanned shape, no longer `KNOWN_NON_IDEMPOTENT`; the compact receiver-attach
  remains the deferred `leftEdgePrefix` ideal. The deeply-argument-nested `.filter(param -> param.a().equals(b))` selector
  stays over-width for the same under-counted-column reason.
- **Binary / string-concat operand break.** A binary whose fan-chain operand is NOT the last operand
  (`MethodCallChainPrinter.binaryNonFinalOperandFansChain`) renders one operand per line — each operator-led operand on its
  own line — instead of the flat operators-inline shape, so a following operator no longer glues onto the previous operand's
  fanned tail (`.orElse("")`⏎`+ routeAssemblyStep.templateTypeArguments()`…, `ReturnExpressionPrinter`). A binary whose only
  fan-chain operand is the last one keeps the flat commit.
- **Factory / type-like root keeps `Type.factory` glued (review round 2).** A `promotesFirstCall` root (an uppercase
  `NameExpr`/type `FieldAccessExpr`) folds its first call onto the root/continuation line so the type qualifier never splits
  from its selector — "class + method should not break until there is a space left". `ChainFanLayout.promotedFactoryRootDoc`
  renders a ZERO-ARGUMENT factory call (`CacheFactory.newBuilder()`) as ATOMIC text rather than the `softChainContinuation`
  group that would split `CacheFactory`⏎`.newBuilder()`, and folds a MULTI-ARGUMENT lambda-carrying factory call
  (`Flux.usingWhen(a, connection -> …, Connection::close)`, `expressionLambdaFactoryCallFoldsAsMultiArgGroup`) through the
  width-driven multi-argument group (`Flux.usingWhen(` on the root line, arguments fanned, `)` dedented) instead of leaving
  `Flux` on its own line. In the initializer, this makes the break-after-`=` verdict a pure fit-gate decision in
  `VariableInitializerLayout.variableInitializerFanBestFitting`: both `bestFitting` arms share one prefix-agnostic fan Doc, so
  a long-typed declaration whose attached first line (`NAME = CacheFactory.newBuilder()`) overflows drops the attached arm and
  breaks after `=` (`NAME =`⏎`CacheFactory.newBuilder()`⏎`.maximumSize(…)`…), while a short LHS (`Number result = Flux.usingWhen(`)
  keeps the factory root attached because the atomic opener fits there.

A call whose **sole argument is a fan-threshold chain** breaks right after the call's own `(` rather than hugging the chain
root onto the opener line. `MethodCallPrinter.singleFanChainArgumentBestFitting` ranks two arms that wrap the same
source-neutral `chainFanOut` — a `hugged` arm (`Response.listUsers(members.reversed()`⏎`.subList(…)`⏎`.toArray(…))`, the
chain glued to the opener and the `)` dangling on the final selector) and an `exploded` arm (`Response.listUsers(`⏎ chain
one indent deeper ⏎`)`, the `)` dedented to the opener's column via `Doc.indent` nesting) — with `Doc.bestFitting(…, {1, 0})`
so the **exploded arm wins whenever it fits** (PR #279 review #3/#4: `Response.listUsers(`/`buffer.append(` break after `(`
with the closing `)` aligned to the opener). Because the exploded first line is a strict prefix of the hugged one and both
wrap one AST-derived fan, the exploded arm fits whenever the hugged one does, so the verdict is a fixpoint; the hugged arm
is a fitting-fallback only. Scoped (as before) to a standalone host call whose own scope is a non-call receiver and that is
not itself a chain segment (`hostIsChainSegment`), so a chain-selector-hosted single-argument call keeps the enclosing
chain's fan.

The gate is the shared `chainFansByCanonicalRule` (structural fan threshold; comment / block-lambda / lambda-arrow chains
withheld) scoped further for the lambda-body position: **object-creation-rooted chains are deferred**
(`!(root instanceof ObjectCreationExpr)`). The lambda-body fan renders the chain root through `chainFanOut` at
`LayoutContext.root()` (column zero) regardless of the header's real column — sound for a column-invariant root (a bare
`NameExpr`/`FieldAccessExpr`/`this` receiver, or an unscoped bare call whose flat form is atomic) but not for
`new X()`, which hugs its first selector on a flat-source pass and breaks onto its own line on a source-multiline pass, so
a `.map(x -> new Record().setA(...).setB(...))` body fanned here would oscillate `new Record().setA(` ⇄
`new Record()`⏎`.setA(` forever. Object-creation-rooted lambda-body chains therefore stay on the packed / opener-breaking
shapes below (already source-shape-stable for them) and remain the deferred slice of this cutover — the nested-root gap
the chain-path-unification plan names for `chainFanOut` rendering a non-name root at `root()`. The narrower legacy gate
`overflowingHuggedBareRootChainBody` (unscoped bare-call root, every call flat, overflows at its real rendered column, and
`huggedFanFits`) is retained as a subsumed fallback after `compactBodyWithClosingLine`. Both triggers render through the
shared `huggedLambdaBodyChain` → `forcedMethodCallChain(expr, layout.withLeftEdgePrefix(firstLine + " "))`, the
forced-chain entry that (unlike `brokenMethodCall`, which for a source-single-line chain opens the tail argument) reaches
the chain printer's segment fan-out directly; the `leftEdgePrefix` conveys the header column to the forced chain's own
width gates.
The first-line hug gate that decides whether that plan is built at all
(`ExpressionLambdaArgumentLayout.expressionLineWidth`, which measures the call prefix, leading arguments, and lambda
header up to `->`) uses the same rendered-column rule: it previously reconstructed the prefix's start column from the
lambda's `range.begin.column` and so, once the source column understated the rendered column (a reindented or shallowly
indented call), let an over-width header hug and flip-flop on the next pass (#217); it now takes the wider of the shallow
baseline and `LayoutWidth.nodeIndentWidth`. The method-call and chain printers' own root-width probes join the same rule:
`MethodCallPrinter.methodCallRootLineWidth` (the source-multiline expression-lambda hug gate) and
`MethodCallChainPrinter.compactRootLineWidth` / `rootLineWidth` / `selectorLineWidth` (the compact-root, promoted-root,
and broken-selector chain gates) each reconstructed the root/selector column from `range.begin.column` and now take the
wider of that reconstruction and `LayoutWidth.nodeIndentWidth`, so a chain reindented shallower than its true block/type
depth is no longer measured as fitting at its stale column. Unlike `expressionLineWidth`, these keep the source column as
the *floor* rather than replacing it: a chain root usually renders after a same-line leading prefix — a `NAME … = `, a
`return `, or an enclosing argument list's continuation indent — that `nodeIndentWidth` (nesting depth only) does not
carry but the source column does, so a bare `nodeIndentWidth` swap under-measured such prefixed roots and regressed the
initializer/return chain fixtures into over-width flip-flops. Flooring by the source column keeps the probe monotone
(it can only ever measure wider), so the migration is regression-free and byte-identical on the corpus; fully attributing
that leading prefix at the rendered column — rather than leaning on the source column to supply it — is the
`LayoutContext.leftEdgePrefix` follow-up to C10-6's trailing-content (#190). The byte-identical structural prerequisite
for that follow-up (LDM-2f) gave the method-call printers a `LayoutContext`:
`MethodCallPrinter.methodCall(MethodCallExpr, LayoutContext)` is the context-carrying entry (the no-context overload
defaults to `LayoutContext.root()`), and the context is threaded through the chain entries and their helpers down to all
four gates above.

The first activation slice (LDM-2f, #190) then populated `leftEdgePrefix` for **one** caller/gate pair: the `return`
chain and `MethodCallChainPrinter.compactRootLineWidth`. `ReturnExpressionPrinter.returnWithForcedMethodCallChain` threads
`layout.withLeftEdgePrefix("return ")` through every forced-chain callback that can reach that gate
(`compactRootWithBrokenFinalChainSegment` and the two `forcedMethodCallChain` overloads, each given a
`LayoutContext`-carrying variant on `MethodCallPrinter`/`MethodCallChainPrinter`). When `compactRootLineWidth` sees a
non-empty prefix it measures the compact chain root's first line at the exact rendered column
`nodeIndentWidth(root) + leftEdgePrefix.length() + firstLine.length()` and **drops the source-column floor** — the floor
was only ever a stand-in for that prefix. This fixes a reindented-flat returned object-root chain whose compact first line
fit by the stale source column but overran the width once `return ` was added (a genuine over-width line, covered by the
`return-chain-root-prefix-width` fixture).

The same slice also refines the **broken shape** an over-width object-creation-rooted chain takes. When such a
chain breaks and its final segment is a call whose argument list is exactly one *simple* argument
(`NameExpr | FieldAccessExpr | ThisExpr | SuperExpr | LiteralExpr`, mirroring
`ControlConditionMethodCallLayout.hasComplexArgument`'s inverse), the tail renders **compact on its own dotted continuation
line** (`new X(...)` ⏎ `.selector(arg)`) rather than opening the single argument (`new X(...).selector(` ⏎
`arg` ⏎ `)`). Both broken-chain entry points converge on this: `compactRootWithBrokenFinalSegment` refuses the arg-opening
shape (`refuseOpeningSingleSimpleObjectRootChainTail`, gated only on an `ObjectCreationExpr` root plus the single-simple
argument), so the direct forced-single-segment call and the compact alternative of `rankedObjectRootSingleSegmentChain`
both fall through to `objectRootSingleSegmentChain`, whose fan-out branch renders the single-simple-argument selector
through the ordinary segment renderer (a `Doc.group` that stays flat when it fits at the continuation column and still opens
the argument only if it genuinely overruns). #236/LDM-2f first scoped this to the `return` chain (a non-empty
`leftEdgePrefix`); PR #279 review (#1) generalized it to **every** caller — a statement chain
`new ProfileRequest(...).submit(10);` reaches the fan-out through the same refusal and wraps as
`new ProfileRequest(...)` ⏎ `.submit(10);` rather than opening the single argument. The verdict is a pure function of the
AST (an `ObjectCreationExpr` root and a single simple selector argument), a fixpoint regardless of the leading prefix, and
the width probe in `objectRootSingleSegmentChain` still decides flat-versus-fan. PR #279 review (#7) then made the
**multi-argument fanned tail width-driven** too: when the compact-attached form overflows and the tail is not a single
simple argument, the selector fans onto its own continuation line through the ordinary `segmentOnOwnLine` segment renderer
(measured at the continuation indent, like every segment of the multi-selector `methodCallChainSegments` fan) instead of
being force-broken one-argument-per-line via `brokenMethodCallChainSegment`. The compact-overflow probe measures the whole
compact chain (constructor plus attached selector), which overflows whenever the constructor root itself will break onto its
own lines; but once it does, the selector lands at the shallow post-`)` column where a fitting argument list
(`.findSessions(principal.groupId(), Source.REMOTE, principal, null)` below a broken `new SessionReader(...)`) stays on one
line, and only genuinely over-wide tails still open. Covered by the `lambda-expression-argument-opener`
`keepsConstructorLambdaBodyPacked` case, the `return-object-root-chain-ranking` `fanOutWhenSelectorOpenerOverflows` and
`initializer-chain-root-prefix-width` `buildConditionalStrategy` goldens, the two-plus-two `return-chain-root-prefix-width`
fixture, the `return-chain-final-argument` nested-return cases, and the `binary-operator-position-*` statement chain. The other three
gates (`rootLineWidth`,
`selectorLineWidth`, `MethodCallPrinter.methodCallRootLineWidth`) and every non-`return` caller still pass `root()` (empty
prefix) and keep the wider-of source-column floor at this point. `withLeftEdgePrefix` mirrors
`withTrailingContent`/`withLeadingBreak` (fresh value, all other components preserved). The main expression-dispatch seam
(`ExpressionPrinters`) forwards the real outer `layout`; the with-tail and other call/chain seams still pass `root()`
until later activation slices extend them.

The second activation slice (LDM-2f, #190) then populated `leftEdgePrefix` for the **variable-initializer** chain.
`VariableInitializerLayout.forcedMethodCallChain(variable, methodCall, flatName)` — the seam
`variableWithForcedMethodCallChain` uses — threads `LayoutContext.root().withLeftEdgePrefix(flatName + " = ")` through a
new `ForcedChainWithLayout` callback (the initializer analogue of `ReturnExpressionPrinter.ChainWithLayout`; wired
`ExpressionPrinters`/`MethodCallPrinter`→`MethodCallChainPrinter.forcedMethodCallChain(expr, firstLineWidth, layout)`),
so `compactRootLineWidth` measures the initializer chain root at `nodeIndentWidth(root) + "NAME = ".length() + firstLine`
and drops the source-column floor. Because the initializer's own opener gates (`variableInitializer`, #216/#222) already
measured the `NAME = ` prefix at the rendered column, this arm reaches **measurement parity** — byte-identical on the
corpus — so the slice is a determinism hardening (a reindented initializer value is now measured at its true rendered
column rather than its stale source column) rather than a golden-moving change. The dot-split tail
(`refuseOpeningSingleSimpleReturnChainTail`, still gated on an `ObjectCreationExpr` root) is thereby **reachable** from the
object-creation-rooted chain shapes this forced path renders, and stays consistent with the `return` chain. It is
deliberately **not generalized** to non-object-creation roots.

PR #279 review then made **break-after-`=` a last resort** for two more initializer shapes, both decided by pure width
probes (no source-shape read). A **conditional initializer** whose `NAME = <whole ternary>` overflows now keeps its
condition on the `NAME = <condition>` line and lets the ternary own its `?`/`:` break whenever that condition fits:
`conditionalInitializer` tries the condition-stays shapes (the condition fits after `=`, or a parenthesized condition whose
opener fits) ahead of the break-after-`=` flat-on-continuation shape it used to reach first (the `variable-declarations`,
`string-literal-equals-in-cast-instanceof`, and `conditional-expression-*-indentation` goldens moved onto the
condition-stays shape). The structural `shouldBreakBeforeConditionalInitializer` rule (binary condition with a binary
branch) is honored first and is independent of this width policy. An **object-creation-rooted chain** initializer on the
trailing-comment / forced-chain sink (`variableWithMethodCallChain`) now attaches `NAME = new X(` and lets the column-aware
constructor `Doc.group` (`ObjectCreationPrinter.widthDrivenObjectCreation`) break its argument list whenever the constructor
OPENER fits after `=` (`objectCreationChainRootOpener`, probed at the real assignment column), rather than breaking after `=`
because the planner's base-indent `methodCallChainFirstLine` measured the whole flat constructor as fitting (the #137/#155
wrong-column read). The attached chain's selectors still fan at the base chain-continuation indent rather than relative to
`NAME = new X(`, and the constructor's closing paren sits on its own line — this sink threads `LayoutContext.root()`, so the
enclosing-column shape (glued paren, selectors continuing from the opener column) is the same `leftEdgePrefix` follow-up
above; the `method-chain-trailing-empty-call-comment` golden's `environment` field shows the constructor-arg-break shape
reached today. One sub-case cannot use that attach: a single-selector object-creation chain whose tail call takes **no
arguments** (`new X(...).build()` / `.withoutAuthentication()`) has no interior break point — the constructor already fits
and the empty tail cannot open — so when a wide declaration prefix (e.g. a broken generic type) pushes it past the line it
would stay flat and overrun with nothing to reflow. `attachedSingleSegmentChainMustBreakAfterEquals` detects exactly that
shape (the attached flat chain overruns AND the whole chain fits on its own continuation line). PR #279 review (#11) then
renders it as a **dot-break** — the constructor root on the `=` line and the zero-argument tail selector fanned onto its own
dotted continuation line (`= new RelaySubject<>(...)` ⏎ `.withoutAuthentication(); // note`) — whenever the constructor
opener fits after `=`, matching the width-driven no-comment sibling byte-for-byte so both converge (a flat-source pass fans
through the `(A)` conditional group; the re-parsed broken-source pass, which parks the trailing comment on the selector
name, lands the identical fan). `dotBrokenObjectRootTailChain` builds that shape ahead of `variableWithMethodCallChain`
(before the chain doc is built) because the fan must claim the trailing comment itself — the chain doc renders the
comment-bearing tail flat and, built first, would claim the comment at doc-build time and drop it from the fan's re-render.
It renders the constructor from its source-neutral `methodCallChainFirstLine` and the tail as `.selector()` text, with the
trailing comment riding as a `lineSuffix` after the `;`, and bails (falling back to the break-after-`=` last resort) unless
the constructor opener fits AND every comment in the chain is one of the tail trailing comments (a constructor-argument or
selector-name comment would be dropped by the text root render). The break-after-`=` fallback's width probe reconstructs the
one-line chain from the constructor scope plus the zero-arg tail rather than `compact.apply(methodCall)`, whose whole-chain
compaction leaks a source-shaped space before the `.` (PR #279 review #17).

Slice 4 (#221, **Case B**) closes the last initializer *dot-split tail* this seam left deferred. A single-call
object-creation root whose selector opener fits and is kept on the assignment line (`NAME = new X(a).sel(simpleArg)`, the
maintainer's "Case 1") was previously argument-broken (`new X(a).sel(`⏎`simpleArg`⏎`)`) by the object-creation branch of
`variableInitializerBrokenOrFlat` via `variableWithBrokenMethodCallArguments`→`brokenMethodCallArgumentList` — opening one
simple argument across three lines when `.sel(simpleArg)` routinely fits on its own dotted continuation line.
`VariableInitializerLayout.initializerSingleSimpleArgTailDotSplits` now intercepts that shape — an object-creation root, one
selector segment, a single *simple* argument (`tailHasSingleSimpleArgument`, the initializer's mirror of
`MethodCallChainPrinter.singleSimpleMethodCallSegmentArgument`: `NameExpr | FieldAccessExpr | ThisExpr | SuperExpr | LiteralExpr`),
and an opener that fits (`argumentBreakOpenerFits`) — and routes it through the initializer's existing
**chain-continuation (+8) fan-out**, `variableWithPackedMethodCallChain`→`packedMethodCallChain`. That is the *same* path a
long-constructor single-selector tail already takes when its opener overflows (the `buildLongConstructorStrategy` /
`buildShortConstructorStrategy` goldens): `packedMethodCallChain` keeps the constructor root on the assignment line and fans
the lone selector compact onto its own continuation line at the chain-continuation indent (constructor root ⏎ `.sel(simpleArg)`),
so Case 1 is byte-for-byte consistent with its opener-overflow siblings rather than taking the argument-open shape or the
shallower `MethodCallChainPrinter.objectRootSingleSegmentChain` indent the `return` chain's #236 dot-split uses. Reaching that
path required relaxing `variableWithPackedMethodCallChain`'s own gate for this one shape: a single-line-source single call is
not a compact-object-creation shape and its opener fits, so the gate would otherwise reject it as an argument-break candidate —
`tailHasSingleSimpleArgument` now admits it (and suppresses the opener-fits argument-break rejection) so the packed fan-out is
selected; multi-argument and lambda tails do not match and keep their prior behavior. The flip is **emitted ahead of** the
source-shape-sensitive collapse branch (`variableWithCompactObjectCreationChain`), and its gate keys only on AST shape and the
opener's fit at the rendered column, so it wins on every pass and is a **fixpoint by construction**: `packedMethodCallChain` is
a pure width function of the AST, so re-formatting the already-split source re-derives the same packed fan-out rather than
collapsing the (now-fitting) whole chain onto the continuation line. It is scoped by `argumentBreakOpenerFits` to exactly the
tails that *currently* arg-open (fit opener); a long-constructor single-simple-arg tail whose `new X(...).sel(` opener
overflows already reaches the identical `packedMethodCallChain` fan-out through the unchanged overflow path, so declining here
leaves it byte-identical. Multi-argument and lambda tails (and non-object-creation roots) are untouched: they never satisfy
`tailHasSingleSimpleArgument` (or the object-creation-root gate) and keep opening. (The *simple-attachable-root* single-call convergence
`singleCallConvergesOnArgumentBreak` also governed — `NAME = Collections.newSetFromMap(...)`, #191 — is routed through
`Doc.bestFitting([argument-break@1, collapse@0])`; see the ranked-engine convergence paragraph below. Its single argument is
a `new WeakHashMap<>(4)` object creation, *not* simple, so it is out of Case B's scope and stays argument-broken/collapsed.)
The initializer's lambda-body and break-after-`=` chain seams pass `root()` (the chain there does not share the
`NAME = ` line), and its packed-object-root seam keeps `root()` because that path measures through its already-prefix-aware
`firstLineWidth` closure, not `compactRootLineWidth`. Covered by the `initializer-chain-root-prefix-width` fixture
(`buildAttachedOpenerStrategy` now fans its single-simple-arg tail at the chain-continuation indent, the *same* column as
`buildLongConstructorStrategy` / `buildShortConstructorStrategy`, whose openers overflow; the multi-argument
`buildMultiTimeoutStrategy` and lambda `buildConditionalStrategy` tails stay opened as scope proofs) and by
`object-creation-root-chain-break` (`singleCallObjectRootDotSplitsSimpleArgTail`). Corpus-wide the flip moves only
object-creation single-simple-arg initializer tails whose opener fits, with no new non-idempotence. (Case A — the
lambda-body chain — remains for slice 5.) `MethodCallChainPrinter.methodCallSegmentWidth`
is deliberately *not* migrated: it measures a segment kept beside a preceding token on the same line, a position deeper
than the block indent that `nodeIndentWidth` cannot express (the one-per-line case is already routed around it via
`segmentOnOwnLine`), so its source column stays. A `!(<binary>)` logical-complement initializer value whose inline
assignment line overflows keeps
`name = !(` on the assignment line and breaks the parenthesized binary one operator per line inside the parentheses
(`VariableInitializerLayout` reuses `EnclosedExpressionPrinter.parenthesizedBreak`, the same shape the `if (...)` condition
and complement-`return` paths produce) instead of breaking after `=` and stranding `!(...)` flat on the continuation line;
a complement that still fits inline stays flat. The return-expression
width gate (`ReturnExpressionPrinter`, with the binary variant `ReturnBinaryExpressionLayout`) measures its candidate
`return value;` line the same way: a `return` value always renders at the statement's rendered indentation plus
`return `, so the gate measures there through `LayoutWidth.nodeLine` rather than at `expression.getRange().begin.column`.
The earlier source-column estimate overshot when a `return` was co-located after a label prefix (`case "x": return …`):
the value broke on the first pass and collapsed on the next once the `return` moved onto its own line and the source
column shrank, the non-idempotent cycle tracked in #137. Counting the enclosing block/type nesting reproduces the same
fit/break decision on every pass — the source-column-to-rendered-column correction first applied to `if` conditions
(`ControlConditionPrinter.ifConditionLineWidth`, #155) and to hugged call openers (#161). The variable/field initializer
master over-width gate (`VariableInitializerLayout`) goes one step further and hands the flat-versus-broken verdict
itself to the renderer (#215, the initializer analogue of the return/unary/ternary LDM-2 measurement parity): its ~10
repeated `LayoutWidth.variableInitializer(variable, flat) > lineWidth` tests (a fixed AST-nesting-depth baseline)
collapse into a single `Doc.conditionalGroup([flat, broken])`, so `DocRenderer` measures the flat form's fit at the true
running column and picks the existing construct-kind broken shape only when it does not fit — a fixpoint by construction
rather than by tuning the depth baseline. The gate carries the same-line terminator (`;`) into both arms so the
measurement counts the one column the old `compact + ";"` gate did. It applies only to comment-free initializers whose
flat rendering is a single line: comment-bearing initializers (both arms would claim the same comments), self-breaking
ones (arrays/switch/anonymous-class own their break), source-multiline-preserved shapes, and casts stay on the historical
imperative cascade (`variableInitializerCommentAndSourceShapeTier`, the initializer analogue of
`ReturnExpressionPrinter.preemptedReturnValue`), which renders the initializer exactly once and is byte-identical to
before. The single-selector, simple-attachable-root fan-out-versus-argument-break convergence (#191, LDM-3) now **runs through
the ranked engine** (`VariableInitializerLayout.rankedSimpleRootSingleCallConvergence`). For an over-width single call
with a name/type-like/field-access root (`NAME = Collections.newSetFromMap(new WeakHashMap<>(4))`),
`variableInitializerBrokenOrFlat` emits `Doc.bestFitting([argument-break@1, collapse@0])` instead of the old imperative
`singleCallConvergesOnArgumentBreak` steering: the **argument-break** (opener attached, `NAME = ROOT.method(`⏎`args`⏎`)`)
carries the higher priority so it wins whenever it fits, and the **collapse** (`NAME =`⏎`ROOT.method(whole)`, the whole
call flat on the continuation line) carries priority 0 so it wins only when the argument-break opener overflows the fit
gate. This reproduces the `field-init-typelike-root-idempotence` golden **by mechanism** — `seenProviders`/
`collapsedProviders`/`attachedProviders` (opener fits) render argument-break, `qualifiedRootProviders`/`qualifiedRootBroken`
(opener overflows) render the collapse — and preserves the maintainer's decided opener-attached house style. The two
blockers the earlier note recorded are both removed by the convergence-redesign foundation: the priority key (Mechanism 2,
slice 1, `Doc.bestFitting(List, int[])`) placed after the fit gate and before line count expresses opener-attachment even
though the collapse uses fewer lines (so the overflow gate stays primary — a fitting-but-lower-priority collapse still
loses to a fitting argument-break, and an overflowing argument-break still loses to a fitting collapse); and the collapse
arm is built **source-neutrally** (the whole call flat on the continuation line — a pure AST function present on every
input), so both passes rank the same two candidates and the previously-oscillating `seenProviders` entry is a fixpoint by
construction rather than by an imperative source-shape predicate. The collapse is built directly rather than through
`MethodCallChainPrinter.chainFanOut`: for a single selector `chainFanOut` fans the selector onto its own dotted
continuation line (`ROOT`⏎`.method(...)`), a *dot-split* shape distinct from this initializer's whole-call collapse, and
routing through it would move the `qualifiedRootProviders` golden. That `qualifiedRootProviders` argument is a
`new WeakHashMap<>(4)` object creation, not a *simple* argument, so it is out of the #221 Case B single-simple-arg tail
dot-split (`initializerSingleSimpleArgTailDotSplits`, above) and stays on this ranked whole-call collapse. The emission is gated comment-free (both arms render the call, and the node is a single
`Doc`, so no comment is double-claimed); comment-bearing single calls stay on the imperative cascade. Object-creation-rooted
single calls (the #48 case) keep their existing imperative branches — their collapse is a broken-constructor/dot-split
shape, not this whole-call collapse — and `singleCallConvergesOnArgumentBreak` survives as the AST+width eligibility signal
for that object-creation case and for the source-multiline gates (`variableInitializerCommentAndSourceShapeTier`,
`variableWithBrokenMethodCallArguments`) that defer converging single calls to this ranked arm; the force-wide gate below
it now reaches only multi-segment type-like chains. The try-with-resources opener
gates (`TryStatementLayout.tryOpenerLineWidth`, feeding both the whole-section flat collapse and the single attached
method-call resource) measure the same way: the `try (…) {` opener renders at the statement's rendered block/type depth,
so counting that nesting through `LayoutWidth.nodeLine` (floored by the `CURRENT` baseline) replaces the fixed one-unit
budget that under-counted every non-top-level `try` and collapsed a resource list flat over width when nested inside a
method body or deeper (#219). The initializer *opener* gates that stay imperative below the master collapse — the ones
that decide whether `NAME = ROOT.method(` (or `NAME = new Type(` / `NAME = new Type<`) keeps its opener on the assignment
line or moves the whole method-call chain or object creation onto an indented continuation — measure the same way through
`VariableInitializerLayout.openerLineWidth`, which takes the wider of `LayoutWidth.variableInitializer(variable, opener)`
(the declarator's real block/type nesting depth) and the historical `LayoutWidth.currentIndented` baseline. That floor
feeds the ~10 argument-break/type-argument/broken-object-creation/commented-object-creation/small-constructor/leading-
commented-lambda opener tests plus the object-creation-chain-root gate (which previously used the fixed two-level
`LayoutWidth.blockStatement` budget); the fixed one-unit `currentIndented` budget matched a top-level field or a method-
body local but under-counted a field in a nested type or a local nested inside further blocks, keeping an opener that
renders past the line width there (the #137/#155 measure-at-the-wrong-column family). Because `openerLineWidth` is never
narrower than the true rendered column, a genuinely over-width opener always trips a gate, and flooring by
`currentIndented` keeps every already-correct shallow position byte-identical (#216). The declaration-header throws
clause (`ThrowsClausePrinter`) and the callable parameter-list break (`CallableSignaturePrinter.parametersBreak`) close
the same family (#220): each measured its same-line width at a fixed one-indent `currentIndented` baseline, so a
`throws …` or a signature on a member of an inner class or nested type — which renders one block/type level deeper per
enclosing scope — was judged against a shallower column than it occupies and kept inline over width. They now take the
wider of that historical baseline and `LayoutWidth.nodeLine` at the node's real block/type depth: the floor leaves
top-level declarations byte-identical (the whole fixture corpus was unchanged) while a deeper-nested `throws` list or
parameter list breaks at its true column, guarded by `format/throws-clause-nested-depth` (the same method and
constructor at class depth stay inline but break one level down).

The breakable-argument gate (`BreakableArgumentExpressionPrinter`) initially joined that family with a
`max(nodeLine, CONTINUATION)` continuation-width probe, but C10-d (#191, once the hub reflows purely by width) replaces
that probe with a renderer-measured `Doc.conditionalGroup([flat, broken])`: instead of pre-computing whether the
argument's continuation line overflows a fixed baseline, it offers the flat rendering and the argument's
expression-specific broken form as ranked alternatives and lets `DocRenderer` pick by flat fit at the argument's true
output column (the trailing comma/tail the enclosing list appends is accounted for by the renderer's line-fit lookahead,
so no width suffix is threaded). Because both arms are pure functions of the AST the choice is a fixpoint — the same
conversion oscillated (+53 non-idempotent files) before the hub stopped reading source shape, but post-flip it does
not — and it retires the gate's `nodeLine`/`CONTINUATION` arithmetic and the printer's `LayoutWidth`/`options`/`compact`
fields entirely. It is a strict determinism improvement: over-width is cleared for deeply nested binary/conditional
arguments (14 corpus files across kafka/camel/cayenne/tomcat/zookeeper) with zero new non-idempotence and no fixture
moved. `format/breakable-argument-nested-depth` (a binary-sum argument that fits flat at method depth breaks
one-operand-per-line inside three nested classes) still guards the deep-nesting break, now driven by the renderer's
true-column fit rather than the probe. A method-call argument stays on the earlier chain-argument path
(`MethodCallPrinter`'s continuation-baseline chain probe), a separate seam left on its fixed baseline.

Chain-unify U3 (LDM-2f, #190) then wired the **statement** and **argument** chain callers — the last two that reached the
chain gates through an implicit `LayoutContext.root()` — with a real `LayoutContext`, and activated the prefix read on
`MethodCallPrinter.methodCallRootLineWidth`. `MethodCallPrinter.forcedMethodCallWithTail` (the statement expression
renderer's forced-chain entry, reached only from `StatementPrinters`) now builds a `LayoutContext`
(`EnclosingConstruct.STATEMENT`, empty `leftEdgePrefix`) and threads a `LayoutWidth.nodeIndentWidth`-based first-line width
(computed once, then folded into the width closure so it stays O(1) per probe) to the chain instead of the fixed
two-unit block baseline, so the chain measures at the statement's real rendered column. A statement always renders at
its own block depth (no stacked continuation indent an argument can accumulate), so that width is exactly the statement's
rendered column and the change is byte-identical on already-formatted input while a statement chain nested deeper than the
two-level budget is now measured at its true depth. The statement's own outer break-or-flat gate
(`StatementPrinter.methodCallStatementWidth`) is intentionally left on the fixed block baseline: it also gates non-chain
statement calls, so swapping it to the rendered column breaks pre-existing over-width statements whose forced-break path
has a latent trailing-line-comment placement non-idempotence, which is out of this byte-identical slice's scope.
`MethodCallPrinter.methodCallArgumentDoc` likewise builds a `LayoutContext` (`EnclosingConstruct.ARGUMENT`) and threads
the fixed continuation baseline (`layoutWidth::continuationStatement`) into the chain, but with an **empty** `leftEdgePrefix`, because an argument's extra offset is pure continuation
*indentation* applied by the enclosing list's nested `Doc.indent` at render time — an argument can sit under several
stacked continuations that `nodeIndentWidth` (block/type depth only) does not count — so the chain gates keep their
wider-of source-column floor, which is where that unmodelled continuation indent still lives; a `nodeIndentWidth`-based
prefix here under-measures a deeply nested argument and regresses it to an over-width flat line, so the rendered-column
attribution of an argument's continuation indent is deferred. `methodCallRootLineWidth` (the source-multiline
expression-lambda hug gate) now reads a non-empty `leftEdgePrefix` the same way `compactRootLineWidth` does
(`nodeIndentWidth(expression) + leftEdgePrefix.length() + firstLine.length()`, floor dropped); reading an empty prefix
stays a strict no-op, so it is byte-identical readiness (no current caller threads a non-empty prefix into it). The whole
U3 slice is byte-identical across the frmtr, kafka, and camel corpora (0 files move) with zero new non-idempotence.

The U3 **floor-drop follow-up** then activated the prefix read on the `rootLineWidth` sibling gate as well
(`nodeIndentWidth(root) + leftEdgePrefix.length() + text.length()`, floor dropped, mirroring `compactRootLineWidth`).
Its consumer `promotedRootArgumentsShouldBreak` is reached by the **initializer** chain carrying a real `"NAME = "`
prefix, so that promoted-root argument-break verdict is now measured at the chain's true rendered column rather than the
value's stale source column. This was expected to move the corpus, but measurement shows it is byte-identical: across
kafka (full), camel, cayenne, tomcat, and zookeeper, 0 files move. The branch is live — it fires 22 times on the kafka
corpus and the rendered-column measurement differs from the old source-column floor in 20 of them (by a few columns each)
— but the difference never crosses the line width on the corpus, so no break verdict flips and the outputs stay
identical. It is therefore a genuine determinism hardening (a reindented initializer whose promoted root sits at a stale
source column across the width boundary is now measured at its true column) that is byte-identical on already-formatted
and real-world code. The remaining sibling gate `selectorLineWidth` stays plumbed-but-no-op; its broken-selector
consumers have not yet been reviewed for the same activation.

The per-node *positional* facts a width gate needs — distinct from the run-scoped `JavaFormatContext` services and
from per-type dispatch — travel in an immutable `LayoutContext` record threaded down the descent
(`EnclosingConstruct` position, `leftEdgePrefix`, the `trailingContent` the caller will emit on the same line after the
node, and a `leadingBreak` flag recording whether the caller has already committed the node to lead with a break). An
earlier transitional fixed-baseline selector once rode on this record to reproduce fixed indentation baselines
for per-node width probes; it has now been **fully retired** (U2/U9, #190) and the field deleted. The return-path reads
went first: the two `returnLineWidth` gates (`ReturnExpressionPrinter`, `ReturnBinaryExpressionLayout`) drop the
fixed-budget floor and measure purely at the rendered column,
`ReturnBinaryExpressionLayout.directBinaryReturnMethodCallFirstLineFits` folds its bare first-line probe into that same
measurement, and the comment-bearing-chain tail and the `returnWithForcedMethodCallChain` callee take a fixed
two-unit block baseline (the return keyword's rendered column is already threaded through `chainLayout`'s
`leftEdgePrefix`, so the prefix-aware chain gates do the real measuring). The last reader was `ChainFanLayout`'s
`--explain`-only width-break diagnostic (`ChainWidthBreakExplain`), now measured at the chain's rendered column
(`nodeIndentWidth` + `leftEdgePrefix` + compact text) exactly like the sibling `MethodCallChainPrinter.compactRootLineWidth`
gate; with no readers left the `widthBudget` field was removed from the record and dropped from its `root()`/`with*` copies
and the writer construction sites (`StatementPrinter`, `BinaryExpressionPrinter`, `MethodCallPrinter`,
`BreakableArgumentExpressionPrinter`). The retirement is byte-identical across the format-fixture suite and the
kafka/camel/cayenne/tomcat/zookeeper corpora (0 files move; only the `--explain` diagnostic's recorded flat-width value
shifts to the correct rendered column). C10-d (#191) then moved the direct continuation-family width probes off the enum
onto standalone `LayoutWidth` arithmetic — `continuationStatement` (three units), `lambdaArgumentClosing` (four units, the
fixed floor the packed-lambda closing gate keeps under its threaded true-column oracle), and `methodChainLambdaBody` (five
units). The **C10 terminal slice** then deleted `LayoutWidth.LineBudget` (the enum, its `line(budget, …)` method, and the
enum-based `currentIndented`/`blockStatement` wrappers) outright. The hub now measures either at the true rendered column
(via `nodeLine`/`nodeIndentWidth`/`variableInitializer` and the threaded `leftEdgePrefix`) or through the enum-free
fixed-baseline arithmetic helpers (`currentIndented` = one unit, `blockStatement` = two, `continuationStatement` = three,
`lambdaArgumentClosing` = four, `methodChainLambdaBody` = five, each a plain `indentUnit.length() * n + text.length()`).
Where a width baseline genuinely has to be threaded down the descent it now travels as a `ToIntFunction<String>` width
measure rather than an enum value. The two load-bearing threaded selectors are the argument-chain continuation seam
(`MethodCallPrinter.methodCallArgumentDoc`, threading `layoutWidth::continuationStatement`) and the block-lambda-body
statement path (`StatementPrinters.methodChainLambdaBlock`, threading `layoutWidth::methodChainLambdaBody` — a signal the
block/type-only `nodeLine` cannot reconstruct, since the statement sits about five `Doc.indent` levels under a broken
chain); the former budget-family chain entries that fed both the residual-probe and first-line slots the same baseline are
routed through `MethodCallPrinter.forcedMethodCallChainAtBaseline`, kept distinct from the `firstLineWidth` overloads so
the two `ToIntFunction<String>` families do not collide. The retirement is byte-identical across the format-fixture suite
and the kafka/camel/cayenne/tomcat/zookeeper corpora (0 files move). `trailingContent` carries the one fact
node-local IR cannot see: the same-line opener a header appends after a clause. The canonical case is the throws
clause — a declaration header's `throws …` width has to include the `" {"` (body) or `";"` (abstract) the caller
glues on that line — so `MethodDeclarationPrinter`/`ConstructorDeclarationPrinter` thread that opener as
`LayoutContext.root().withTrailingContent(" {"/";")` and `ThrowsClausePrinter` reads it from the context
(`layout.trailingContent()`) rather than receiving an ad-hoc `suffix` string, keeping the trailer a positional fact
about where the clause sits instead of a loose parameter (#218). `leadingBreak` carries the mirror fact for the
enclosed-suffix path: whether the node's line is *already* broken. When an assignment or variable-initializer
right-hand side has been decided too wide to stay flat, `AssignmentExpressionPrinter`/`VariableInitializerLayout`
thread `LayoutContext.root().withLeadingBreak(true)` and `EnclosedSuffixDispatcher` reads it from the context
(`layout.leadingBreak()`) — so a `(…).method(…)`/`(…)::member` receiver breaks its parenthesized scope
unconditionally — rather than the dispatcher carrying that decision as a separate boolean argument (#189); the
concrete `MethodCallPrinter`/`MethodReferencePrinter` suffix printers keep the resolved boolean because they also
serve non-positional callers (a plain `methodCall`/`methodReference` with no broken line to inherit). `leftEdgePrefix`
carries the same-line text ahead of the node; its reader is `MethodCallChainPrinter.compactRootLineWidth`, which the
`return` chain feeds `withLeftEdgePrefix("return ")` and the variable-initializer chain feeds
`withLeftEdgePrefix(flatName + " = ")` (LDM-2f, #190, above) so the gate measures at the exact rendered column and drops
its source-column floor. The expression-lambda body chain is the third feeder (#221 Case A, generalized to the
canonical-fan U7 lambda-body position): when a lambda body is a non-object-creation-rooted method-call chain that fans by
the End-state A rule (`someCall(x -> assertThat(x).extracting(...).containsOnly("v"))`, `verifier.each(h -> journalWriter.atInfo().addValue(...).log(...))`),
`ExpressionLambdaArgumentLayout` fans it onto dotted continuation lines while it hugs the lambda
header, routing through `ExpressionPrinters.huggedLambdaBodyChain` →
`MethodCallPrinter.forcedMethodCallChain(expr, CURRENT, layout.withLeftEdgePrefix(firstLine + " "))` so every width gate
the forced chain consults measures past the `someCall(x -> ` prefix (detailed with the expression-lambda layout helpers
above; object-creation-rooted lambda bodies are deferred because that root is not column-invariant at `root()`). The record stays a plain record with a `root()` default of no prefix, no
trailer, and no leading break, plus `withTrailingContent`, `withLeadingBreak`, and `withLeftEdgePrefix` derivations, so it
is native-image safe and every non-header, non-broken, non-prefixed call site is unaffected.
The throws gate's *measurement* now runs at the declaration's real rendered column (`LayoutWidth.nodeLine` floored by
`currentIndented`), the C10 rebaselining parity the LDM-2 unary/ternary/return gates already had, and the
`parametersBreak` and breakable-argument width gates were migrated the same way in the same slice (#220, described with
the rendered-column family above); the `trailingContent` prefix/suffix these gates measure is still the ad-hoc string
the caller assembles, only its measurement column moved. A per-run
`SourceShapePolicy` on `JavaFormatContext` is the consolidating home for
"should the formatter respect the author's source shape here?" decisions, so printers ask one named question instead of
re-deriving those reads from raw token text or `getRange()` arithmetic. After the D3 flip (below) it owns only the four
`FIXPOINT_SAFE` reads the formatter's own output reproduces or normalizes — each round-trips to a fixpoint:

- whether the author left a blank line between two source-adjacent nodes — `hadBlankLineBetween`, plus a
  `hadBlankLineBefore` overload for callers that first resolve a comment-aware begin line (blank lines collapse to at
  most one, so re-reading the output yields the same answer);
- whether a node's source-equivalent compact text fits on one line at its call-site indentation — `fitsOnOneLine`,
  which applies a per-site indented-width function to `CompactSourceText` and owns the single `lineWidth()` comparison
  while leaving compact-text generation in that helper (a width probe over source-equivalent text, not a read of the
  author's line breaks);
- whether a node encloses comments that make a compact or otherwise source-shaped layout unsafe — `hasContainedComments`,
  delegating containment itself to the run-indexed `JavaCommentPlacementPolicy.hasContainedComments` rather than
  re-scanning JavaParser (compact-source reconstruction that strips comments on clones keeps its own direct scan because
  the run index reports an unknown clone as comment-free); and
- the source-only try-with-resources section shape (`tryResources`), reproduced verbatim so the read round-trips.

### D3 flip: the hub reprints by width; the source-shape read ratchet reaches zero

The method-call / chain / object-creation / lambda **hub now reprints by width**. The six `RETIREMENT_TARGET`
"preserve the author's line breaks" reads on `SourceShapePolicy` — `wasMultiline`,
`methodCallArgumentsSpanMultipleLines`, `objectCreationArgumentsSpanMultipleLines`, `expressionLambdaStartsOnSelectorLine`,
`startsOnSameLine`, and `selectorBrokeAfter` — are **retired and deleted**, together with their `SourceShapeException`
entries (`WAS_MULTILINE`, `STARTS_ON_SAME_LINE`, `CHAIN_SELECTOR_BROKE`) and the now-vacuous `SourceReadTripwire`
diagnostic. Every consumer drives layout from the renderer's width verdict at the true column (a
`conditionalGroup`/`bestFitting`) or from a pure structural `BreakRule`: the argument list, the single-argument /
expression-lambda hug, the constructor argument list, the chain fan, and the source-multiline-statement/segment
preservation branches all reflow rather than preserving the author's incidental line breaks. Consumer helpers whose only
input was a retired read are kept as constant-`false`/`Optional.empty()` shells where they were plumbed through
constructors as functional references (so the dispatch wiring stays byte-identical) and deleted where they became fully
unreferenced. `SourceShapeExceptionGovernanceTest` pins the `RETIREMENT_TARGET` count at its **terminal state of `0`** —
the ratchet is done for the catalogued reads.

The deletions were validated as **behavior-neutral**: each retired read had already been driven to constant `false` and
measured on a real corpus (kafka, 400 files) before deletion — real-corpus idempotence is *better* than the pre-flip base
(2 non-idempotent files vs 8), and over-width is +5 bounded files. Deleting the dead reads is byte-identical to the
`return false` behavior across the whole 400-file corpus.

**Honest residual follow-ups** (tracked; the flip is landable but not the whole story):

- **+5 bounded corpus over-width files.** The width-driven hub leaves five kafka files with an over-width line the
  source-preserving reads used to avoid: the segment-lambda multi-selector nested-root family, block-lambda bodies, and
  comment-adjacent chains. Bounded and stable, not growing.
- **One quarantined edge fixture.** `lambda-expression-argument-opener` produces a legitimate, AST-equivalent pass-1
  layout that is not yet a one-pass fixpoint. Its golden is rebaselined to that pass-1 output and its *idempotence*
  sub-assertion is allowlisted (`FrmtrTest.KNOWN_NON_IDEMPOTENT` / `IdempotencePropertyTest.KNOWN_NON_IDEMPOTENT`); two
  comment-drop perturbations (`method-chain-member-access @ expanded`, `source-multiline-object-chain-initializer @ collapsed`)
  are parked in `CommentPresenceDiagnosticTest.KNOWN_DROPS`. The fixture stays as a tracker that flips green when the deep
  slice lands; it is neither deleted nor moved.
  `source-multiline-object-chain-initializer` was the second such idempotence tracker; slice **F5** (Decision D4) makes its
  `sourceBrowser` case a one-pass fixpoint: an inter-segment `//` line comment attached as the sole selector's leading
  comment on an object-creation-rooted single-call chain now renders the comment-preserving **exploded** shape
  (`MethodCallChainPrinter.objectRootSingleSegmentChain`) keyed on comment presence
  (`methodCallSegmentHasLeadingLineComment`) rather than the retired constant-false `sourceMultilineChain` read — so pass 1
  no longer picks a compact-glued shape that pass 2 re-attaches as a root-trailing comment and explodes. It is de-parked
  (removed from both `KNOWN_NON_IDEMPOTENT` sets, golden rebaselined to the exploded fixpoint). Its `@ collapsed`
  comment-drop perturbation is a distinct comment×width fold and stays parked in `KNOWN_DROPS`.
  `method-chain-trailing-empty-call-comment` was the third such tracker; the PR #279 review (#17) empty-tail single-selector
  object-creation break-after-`=` (below), refined by review (#11) into the **dot-break** shape
  (`dotBrokenObjectRootTailChain`: `= new RelaySubject<>(...)` ⏎ `.withoutAuthentication(); // note`), makes it a one-pass
  fixpoint that no longer overruns the line, so it is de-parked (removed from both `KNOWN_NON_IDEMPOTENT` sets, its
  `@ collapsed-whitespace` perturbation now converges so `EXCLUDED_AS_FINDINGS` is empty again, and its over-width allowlist
  entry is dropped).
- **A still-live inline-read tier (now catalogued).** `SourceShapePolicy` is source-independent, but the printers still
  make a handful of inline `getRange()` line reads, now pinned by `InlineSourceLineReadGuardTest`'s allowlist:
  `sourceFirstLineKeepsChainAfterRoot` and roughly eight `begin.line < end.line` comparisons. These must be retired for
  **full** source-independence. `lambdaBodyStartsAfterHeader` (the lambda-arrow keystone read in
  `SourceMultilineLambdaCallLayout`) was **retired by the #190 F2 segment-column slice**: whether an expression-lambda
  method-call body "starts after the arrow line" no longer gates the attach-first-segment opener hug, so the enclosing
  chain fans one selector per line at its true column on both passes — converging the `lambda-expression-argument-opener`
  `assertThatThrownBy(() -> …)` / `probe.withVirtualTime(() -> …)` cases to a one-pass fixpoint (de-parked from both
  `KNOWN_NON_IDEMPOTENT` sets).
- **The D0 corpus-check metric.** The in-harness `corpus-check` idempotence/over-width columns under-report (they showed
  0 idempotence while the true base non-idempotence was ~8/400); the reliable signal is the format-twice-and-diff
  probe. That metric divergence should be fixed so the harness numbers can be trusted directly.

The control-condition logical break no longer reads source shape. A logical `&&`/`||` condition that overflows breaks
through `ControlConditionPrinter.brokenCondition` → the width-driven `BinaryExpressionPrinter` operand-by-operand layout
(the same `brokenExpressionLines` path every other broken binary uses), which explodes each over-wide operand — including
a negated method call `!call(args)` — by rendered width. This retired the `sourceMultilineLogicalCondition` and
`methodCallOperandSpansMultipleLines` reads (and deleted the control-condition-specific `brokenLogicalCondition` /
`sourceMultilineLogicalOperand` renderer that kept an operand broken off the author's line shape), converging control
conditions on the standard binary printer. A
condition the author wrote across multiple source lines that now fits on one line collapses to a single line; a
mixed-operator condition breaks in the same precedence-grouped, operator-spaced shape as every other broken binary
(`(A && B)` ⏎ `|| (C && D)`) instead of the old flat operand-per-line list.

The multi-argument method-call `if`-condition break is likewise structural. Such a condition explodes its argument list
only when the flat `if (...)` line overflows the budget or — within one indent unit of the budget — the call carries a
*complex* argument (`ControlConditionMethodCallLayout.hasComplexArgument`: any argument that is not a bare
name/field/`this`/`super`/literal). The old near-boundary "the author already wrote the arguments across source lines"
relaxation (`methodCallFirstArgumentStartsAfterName`) was retired, so a near-boundary condition with only simple
arguments now collapses to one line regardless of how the author wrapped it (`if-condition-multiarg-argument-reflow`
fixture). These two retirements were an earlier step that dropped the reprint-by-default `RETIREMENT_TARGET` count to 7
(the D3 flip above later drove the remaining hub reads to 0).

Direct binary `return` layout (`ReturnBinaryExpressionLayout`) likewise stopped reading whether a returned expression
tree contained a source-multiline method-call argument. Previously a `+` string-concatenation return that wrapped such a
call was force-routed to the ordinary expression renderer (preserving the author's mid-concatenation break), and the
un-parenthesized source-multiline continuation shortcut excluded those trees as a width-safety guard. Both uses of
`containsSourceMultilineMethodCallArgument` were retired: the concatenation now reflows through the standard
operand-per-line binary continuation, and the continuation shortcut relies on `binaryLines` breaking each over-wide
operand by width rather than on a source-shape exclusion. This dropped the `RETIREMENT_TARGET` count to 6 — the last
step before the D3 flip above retired the remaining six hub reads and drove the count to 0.

Two type-renderer "satellites" outside the hub — type-use annotation lists on a generic argument and qualified class
literals — likewise stopped reading whether the author wrote them across multiple source lines, and are now driven by
width at the true column. A generic argument carrying an annotation with a parenthesized body (`Map<@Size(...) String,
…>`) renders through `TypePrinter.breakableAnnotatedGenericArgument`: a single group keeps the annotations and the
trailing type inline while the compact form fits and, when it overflows, breaks the parenthesized body of each breakable
annotation (each annotation is an `ifBreak(brokenAnnotation, compactText)`, so a fitting argument reproduces the exact
compact spelling and only the overflowing one restructures) while the annotations and the type stay inline — the shape
the retired `sourceMultilineAnnotation` probe used to gate on the author's line breaks. A qualified `Outer.Inner.class`
literal (`ClassExpressionPrinter`) is emitted as a `conditionalGroup` whose first alternative is the exact compact text
and whose fallback packs the dotted segments with a `Doc.fill`, breaking only the dots the line width forces (indenting
each continued segment one level, `.class` riding on the last segment) and breaking a segment's generic argument list by
its own width; this retired both `sourceMultiline` and the `startsOnLaterLine` cross-range read. These three retirements
shrink the `InlineSourceLineReadGuardTest` allowlist (the inline `getRange().line` tier) by their three satellite
entries; the entries left there are the chain/lambda `leftEdgePrefix` follow-ups, not type-renderer reads. The
`type-use-annotation-width-wrap` and `class-literal-width-wrap` fixtures pin the compact-fit and the overflow-wrap shapes
for each.

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

The `--verify` flag (off by default, rejected only in stdin, explain, and print modes, and standalone without `--write`
or `--check`) exposes the API's `formatVerified(...)` safety valve. It does not own the equivalence check; it only
selects the verified format path. With `--write` it selects `FormatterRunner.writeVerified(...)`, which fails closed
with a non-internal diagnostic instead of overwriting a non-equivalent result. With `--check` it selects
`FormatterRunner.checkVerified(...)`, the read-only counterpart: each file is formatted in memory through
`formatVerified`, would-change is reported exactly like a normal check, and nothing is ever written — a general
capability for verifying AST-equivalence over non-throwaway targets. Default `--write`, stdin, explain, check mode, and
the `dev.lanwen.frmtr.debug.verify` toggle are otherwise unchanged. A Gradle-plugin equivalent is a planned follow-up.

`--check --verify` additionally surfaces breakable over-width output lines as informational warnings. `Main` reads each
result's `overWidthLines()` and prints, per file, a gcc/clang-style summary to **stderr** (so stdout's status lines and
diffs stay machine-readable), followed by a run total. The warnings are advisory: `Main.printOverWidthWarnings(...)`
runs after the result loop and never feeds `hasChanges()`/`hasFailures()` or `failureExit(...)`, so the `0/1/2/3`
exit-code contract is untouched. Pragma policy lives in `OverWidthLines` itself, not in the CLI: an over-width line
inside a `@formatter:off`…`@formatter:on` or `frmtr-ignore-start`…`frmtr-ignore-end` range (or carrying a bare
`frmtr-ignore`) is suppressed, because the formatter emitted it verbatim from source and warning there would contradict
the opt-out. The same `OverWidthLines.Scanner` drives the test-only `SuspiciousLineWidthAudit` gate, so the CLI warning
and the fixture audit share one pragma definition (the audit's single-line `frmtr-ignore` suppresses only the line
carrying the marker; this is narrower than `FormatterPragmas`, which raw-passes the following node, but it is the
over-width-specific policy and the audit allowlists any such kept line separately).

`--render-indentation` is a presentation-only transform that visualizes leading indentation, distinguishing a **block**
indent (opening a new brace-delimited body) from a **continuation** indent (a wrap aligned to a logical parent — a
broken method-chain selector, an assignment/return continuation). A block indent renders only the columns it adds over
the previous line as middle-dots (`·`) with the shared prefix blank (a dedent adds nothing, so it is plain spaces); a
continuation renders a vertical ellipsis (`⋮`, U+22EE) at the enclosing statement's indent followed by dots for the rest
of its offset, on every continuation line. It owns no formatting policy: `IndentationRenderer.render(...)` runs on the
already-formatted source just before `Main` prints it, substituting glyphs only for leading whitespace and leaving every
other byte (mid-line spaces, string-literal whitespace, line endings) intact, so it shifts no columns and cannot change
wrapping — replacing `·` and `⋮` back to spaces recovers the plain output exactly. It is wired only into the
source-printing paths (`formatStdin` and `printFiles`, both via `Main.renderSource(source, options)`); `--write`,
`--check`, `--diff`, `--render-line-width`, and `--explain` deliberately do not route through it, and `Main` rejects the
flag when combined with any of them (or with the implicit default check mode, which prints nothing) with a usage error.
Off by default, so printed output is byte-for-byte the formatter result unless the flag is set.

Telling a block indent from a continuation indent is not recoverable from the finished text alone (a block level and a
continuation offset are both leading whitespace, and tabs make column counting ambiguous), so the transform reads a
per-line structural signal the renderer emits. `Frmtr.formatIndented(...)` returns an `IndentedSource` — the formatted
text (byte-identical to `format(...)`) plus one `IndentedSource.Line` per output line, each carrying whether the line's
leading whitespace is a formatter-chosen indent and, if so, its indent _level_. `DocRenderer.renderIndented(...)`
produces that signal as a minimal by-product of the render: `newline(...)` records a structural line at the level it
just emitted, while a newline arriving inside a `Text` (a text-block literal's interior) records a non-structural line
whose leading whitespace is literal program data. The plain `render(...)` path does not accumulate the signal, so it is
allocation-free and unchanged. On top of that level signal the CLI applies a documented **heuristic** for block vs
continuation, because the level alone does not name the construct: a line reads as a continuation when its level rises
two or more levels above the current block baseline (the double-indent shape every continuation construct in the printers
uses), except a real block opened inside a continuation (a block-lambda body under a broken chain) — caught by a trailing
`{` on the previous line, the classic brace-based block signal, which resets the baseline. This is approximate in two
documented ways: a continuation that indents exactly one level (a single-level wrapped argument list) reads as a block,
and text-block interiors keep the pre-existing uniform-dot rendering. A fully exact classifier would require the layout
printers to label their continuation indents; the transform intentionally stays in the CLI/renderer presentation layer
and does not reach into the chain-layout printers.

The CLI maps run outcomes to four process exit codes, highest severity winning (`3 > 2 > 1 > 0`): `0` success (all
clean / written / verified, or no files matched); `1` would-change in check modes (no failures); `2` parse failure, IO
error, or usage/config error; and `3` a verify violation — a cleanly-parsed file whose formatted output was not
AST-equivalent (or did not re-parse), i.e. a formatter bug. The verify-violation code is keyed on a principled
discriminator rather than message matching: `FormatterException.verifyViolation()` is set true only at the two
`JavaFormatter` verify throw sites, and `Main.failureExit(...)` promotes a failing run to `3` when any failed result
carries such an exception, otherwise `2`. Usage and configuration errors stay `2`.

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

Applying the plugin creates project-local aggregate tasks and an extension only for that project. `frmtrCheck` and
`frmtrFormat` are local lifecycle tasks over formatter tasks registered in the same project; they do not recursively
apply the plugin to child projects and do not declare root aggregate dependencies on child tasks.

Multi-project execution relies on Gradle's normal task-selector behavior. When several projects explicitly apply the
plugin, running a selector such as `frmtrCheck` from the root schedules each matching project-local task, and
`--continue` lets independent modules report their own failures. A root `apply false` declaration only centralizes plugin
classpath and version. Parent extension values provide conventions only when the parent actually applies frmtr and a
child explicitly applies frmtr after that extension exists; child values override those conventions, and inheritance does
not imply root aggregation or automatic child participation.

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

`:frmtr-cli:nativeDistributionZip` wraps the platform-local native executable as a JReleaser `BINARY` distribution with
`LICENSE`, `README`, and `bin/frmtr` or `bin/frmtr.exe` entries. The release workflow builds those archives on Linux
x64, macOS arm64, and Windows x64 runners, then Linux publication jobs pass the collected archives to JReleaser for
GitHub release asset upload, Maven Central deployment, and Homebrew formula publication. Gradle Plugin Portal
publication runs as a separate job. Release version changes remain ordinary protected-branch PRs; the workflow creates
only release tags, release assets, tap updates, and signed follow-up automation PRs.

Release automation derives version bumps from Conventional Commits-style PR titles. `feat`/`feature` raises the release
target to at least the next minor version, breaking-change markers raise it to the next minor version while the release
line is `0.x` and to the next major version from 1.0 onward, and all other included PRs default to patch. Main-branch
pushes refresh the generated release PR, and feature or breaking-change merges can also open a signed snapshot PR when
the current `*-SNAPSHOT` version needs to move to a higher release target. That snapshot PR also updates documented
snapshot consumption versions in the README and publishing guide. The snapshot target workflow can be dispatched
manually with an explicit snapshot version. Snapshot target PRs and post-release next-snapshot PRs share the same
reusable snapshot PR workflow, so their GitHub App token handling and signed PR creation stay identical. The schema and
changelog marker contract are documented in [docs/release-automation.md](docs/release-automation.md).

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

### Real-World Corpus Check

`.github/workflows/corpus.yml` runs a release-PR correctness check against a pinned real-world corpus
(`testcontainers/testcontainers-java` at a fixed SHA). The generated `release` PR carries this check before it can merge,
and publishing the release does not run the corpus again. It reuses the shipping CLI rather than a bespoke Java harness:
the workflow fetches the pinned corpus into a throwaway checkout, runs `frmtr --write --verify` over the corpus main
sources (parse-stability plus AST-equivalence), then `frmtr --check` over the now-formatted sources (one-pass
idempotence). The two steps cover distinct invariants: `--write --verify` alone does not prove idempotence, and
`--check --verify` would not either, so the mutating `--write --verify` then read-only `--check` pairing is kept
deliberately. The workflow reads the CLI's distinct exit codes: the `--write --verify` step distinguishes a verify
violation (exit `3`, reported as a formatter bug) from a parse/IO failure (exit `2`), and the `--check` idempotence step
fails on exit `1` (a file would still change). The read-only `--check --verify` capability exists for verifying
non-throwaway targets; the corpus checkout is ephemeral, so mutating it buys nothing there. The pin, scope, and exclude
globs live in the workflow's `env` block so they are easy to bump. Cadence is generated PRs labeled `release` plus
`workflow_dispatch`, not every ordinary PR and not post-release publication.
