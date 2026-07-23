# Architecture

`frmtr` is a Java formatter with a small public API, thin CLI and Gradle adapters, and a formatter engine built around
JavaParser plus an internal document IR. The project is split into focused Gradle modules so formatter internals and
build-tool integrations evolve behind explicit dependency boundaries.

This file is the high-level overview: build shape, module boundaries, data flow, the formatter pipeline, and adapter
contracts. The [README](README.md) is the source for installation, CLI recipes, Gradle plugin usage, native-binary
build commands, and user-facing option names. Deep implementation detail lives under `docs/` so this overview stays
readable:

- [docs/java-formatter-internals.md](docs/java-formatter-internals.md) — the printer graph, helper boundaries,
  comments, raw-source handling, and guardrails.
- [docs/formatter-coverage.md](docs/formatter-coverage.md) — JavaParser AST kinds mapped to their formatter owner and
  the intentional raw/compact fallback paths.
- [docs/error-recovery-behavior.md](docs/error-recovery-behavior.md) — the implemented parse-error recovery behavior.
- [docs/testing-strategy.md](docs/testing-strategy.md) — module coverage, golden fixtures, and native-image checks.

## End-to-End Pipeline

Formatting is a two-pass shape: **parse to an AST, build a `Doc` intermediate representation, then render that IR to
text under a width budget.** The printers make *structural* decisions (what may break, how things nest); the renderer
makes the *width* decision (whether each break actually happens at the real output column).

```
  source text
      │
      ▼
  ┌──────────────┐   store tokens + attribute comments
  │  JavaParser  │──────────────────────────────────────► parse problems ──► FAIL: FormatterException
  └──────────────┘                                                           RECOVER: raw-region printing
      │  CompilationUnit (AST)
      ▼
  ┌──────────────┐   source-equivalent normalization (imports, etc.)
  │  transforms  │   (skipped for recovered trees)
  └──────────────┘
      │
      ▼
  ┌──────────────┐   per-node-kind dispatch → specialized printers/layouts
  │  JavaPrinter │   emits the Doc IR (break opportunities, groups, indents)
  └──────────────┘
      │  Doc
      ▼
  ┌──────────────┐   width-driven: keeps groups flat while they fit,
  │  DocRenderer │   breaks where they do not; ranks candidate layouts
  └──────────────┘
      │  formatted text
      ▼
   output  ◄───── side-checks (opt-in / tests): AST-equivalence verify,
                  comment-presence, idempotence (format-twice-and-diff)
```

JavaParser's own pretty-printers are *not* the engine. Final formatting is owned entirely by the `Doc` pipeline.

## Build

The Gradle build is the source of truth for exact toolchain and dependency versions, publication metadata, license
packaging, generated jars, and CI wiring. This section records the architectural shape those files support; the version
catalog owns external versions and they are not repeated here.

### Modules

```
(arrows point to the dependency; each adapter depends on both tooling and core)

┌──────────────┐   ┌─────────────────────┐        ┌────────┐
│  frmtr-cli   │   │ frmtr-gradle-plugin │        │  site  │  standalone JBake
└──────┬───────┘   └──────────┬──────────┘        └────────┘  site (no runtime dep)
       │                      │
       └──────────┬───────────┘
                  ▼
         ┌───────────────┐
         │ frmtr-tooling │  file runner · diffs · diagnostics
         └───────┬───────┘
                 ▼
         ┌──────────────┐
         │  frmtr-core  │  public API · JavaParser · Doc IR · renderer · printers
         └──────────────┘

frmtr-cli ──► frmtr-native-image-support   (build-time only; consumed by the native-image build)
frmtr-bench ──► frmtr-core                 (dev-only JMH benchmarks; no runtime artifact)
```

- **Root** — aggregates build conventions and repo-local helper tasks; produces no runtime artifact.
- **`:frmtr-core`** — formatter public API, JavaParser integration, the `Doc` document IR, renderer, and Java printers.
- **`:frmtr-tooling`** — adapter-agnostic file runner, run summaries, diff rendering, diagnostics; depends on core.
- **`:frmtr-cli`** — Picocli adapter, source discovery, terminal presentation, JVM app, and native entrypoint; depends
  on core and tooling.
- **`:frmtr-gradle-plugin`** — Gradle adapter, source-set integration, task wiring, diagnostics; depends on core and
  tooling.
- **`:frmtr-native-image-support`** — build-time native-image metadata for JavaParser reflection; visible only to
  native-image builds and native tests, never on a normal JVM runtime classpath.
- **`:frmtr-bench`** — JMH microbenchmarks for formatter hot paths; shares core's package to measure package-private
  helpers directly. Dev-only, depends on core, produces no runtime artifact and is not published.
- **`:site`** — static onboarding site built with JBake.

### Toolchain

All Java subprojects compile with a Java 21 toolchain and `--release 21`, so published artifacts and the JVM CLI stay
loadable by Java 21 Gradle daemons. Building the native executable is the only Java 25 path: `:frmtr-cli:nativeCompile`
drives GraalVM native-image through a native-image-capable JDK 25 launcher while consuming the same Java 21 CLI
bytecode. `:frmtr-cli` generates a small `BuildInfo` source during compilation so JVM and native binaries report the
same version, Git SHA, and build timestamp.

### Distribution Channels

- Only the library modules external adapters need — `:frmtr-core` and `:frmtr-tooling` — are staged as Central-bound
  `mavenJava` publications.
- The **Gradle plugin** publishes through Gradle's plugin-development / plugin-publish model.
- The **CLI** ships as an application / native executable, not a Maven library.
- `:frmtr-native-image-support` stays a build-time companion only.
- **JReleaser** owns GitHub release and binary-distribution metadata (`jreleaser.yml`); Homebrew publication runs
  through a separate `jreleaser-brew.yml` so the tap job validates only release/packaging metadata, not Central deploy
  or signing secrets. Release preparation keeps the Gradle version, README plugin snippets, and site version property
  in sync with the published coordinate.

## Package Layout

Package layout follows module ownership rather than sharing implementation packages across adapters:

```text
frmtr-core/src/main/java/dev/lanwen/frmtr
├── (public API and configuration: Frmtr, FrmtrSession, FormatterOptions, ...)
├── doc   → the Doc IR, DocRenderer, DocWidths, and the debug/explain renderers
└── java  → JavaParser-backed parsing, comments, pragmas, raw-source helpers, and Java printers

frmtr-tooling/…/tooling                 → file runner, summaries, per-file results, diffs, diagnostics
frmtr-cli/…/cli                         → Picocli command, discovery, stream routing, output views
frmtr-gradle-plugin/…/gradle            → Gradle extension, source-set integration, formatter tasks
frmtr-native-image-support/…/nativeimage→ hosted-reflection registration for JavaParser AST nodes
site/src/jbake                          → documentation site source
```

## Public API

The public surface is deliberately small and lives at the root of `frmtr-core`:

- **`Frmtr`** — static entry points: `format`, `formatVerified` (AST-equivalence-checked), `formatIndented`
  (text plus per-line indent structure), `explain` (formatting plus a layout-decision report), `debugDoc` (structural
  IR dump), and `session(...)`. Each has a `FormatterOptions` overload.
- **`FrmtrSession`** — wraps one configured engine for sequential reuse when formatting many sources with identical
  options; explicitly *not* thread-safe.
- **`FormatterOptions`** — the single formatter-wide policy record (canonical constructor, `defaults()`, focused
  withers). It covers line width, indentation, line ending / trailing newline, raw-region trailing whitespace,
  require-pragma gating, single-parameter lambda parentheses, broken-binary-operator placement, parse-error behavior,
  and parser language level. Node-specific heuristics stay internal, not public options.
- **`FormatterException`** — structured failures carrying `SourceProblem` entries (message, location, enclosing
  declaration line, source context); `FormatterException.internal(...)` wraps recoverable engine faults so adapters
  report concise failures rather than VM crashes.

Adapters translate their user-facing flags/DSL into `FormatterOptions` and call the same engine path, so CLI and Gradle
behavior cannot drift into separate formatting policies.

## Document IR (`Doc`)

`Doc` is the intermediate representation between Java syntax rules and text output. It models *formatting decisions*
instead of building strings, so the width choice can be deferred to render time. The renderer is language-agnostic;
all Java-specific choices live in the printers.

The combinator vocabulary groups into a handful of concerns:

| Concern | Combinators | Idea |
|---|---|---|
| Literal content | `Text`, `Concat` | Emit and join text. |
| Break opportunities | `Line`, `SoftLine`, `HardLine`, `BreakParent` | Optional vs. required line breaks; `BreakParent` forces the nearest enclosing group to break without printing a newline. |
| Indentation | `Indent` | Increase indent after breaks. |
| Width decisions | `Group`, `Fill`, `ConditionalGroup`, `BestFitting` | How a subtree chooses flat vs. broken (see below). |
| Conditional content | `IfBreak` | Different output for flat vs. broken layout, optionally keyed to a named group. |
| Deferred content | `LineSuffix` | Trailing comments that lay out after the code on their line. |
| Diagnostics | `Label` | Debug-only provenance; transparent to rendering and width. |

The four **width-deciding** primitives are the heart of the model:

- **`Group`** renders flat first and breaks only when its content does not fit the space left on the line.
- **`Fill`** packs an alternating `[content, separator, …]` list greedily — as many items per line as fit — instead of
  being all-or-nothing like a group.
- **`ConditionalGroup`** holds ordered alternatives and renders the first whose *flat* layout fits, falling back to the
  last (which may be broken). It ranks by flat fit only, so it cannot compare two broken shapes.
- **`BestFitting`** holds ordered, flattest-first alternatives and picks the one that *fits, then minimizes rendered
  line count* at the live column, with a deterministic, priority-aware tie-break. Unlike a conditional group it *can*
  rank two broken shapes against each other, which is what method-call chains need. Ranking measures at most a small cap
  of alternatives per node and memoizes each node's ranking by (identity, indent, start column), so nested ranking stays
  affordable at any depth without a nesting cap — native-image-safe and near-linear on real code.

```
   ┌─ Group ──────────► flat if it fits, else break
   │
   ├─ Fill ───────────► greedy: keep packing until the next item overflows
   │
   ├─ ConditionalGroup► first alternative whose FLAT form fits; last = fallback
   │
   └─ BestFitting ────► rank alternatives (incl. broken ones):
                          fit gate ▸ priority ▸ fewer lines ▸ less overflow ▸ flattest
```

### Renderer and width authority

- **`DocRenderer`** walks the `Doc` and emits text under the width, indentation, and line-ending options. It buffers
  `LineSuffix` content and flushes it just before each line break, and delegates every width question to `DocWidths`.
- **`DocWidths`** is the single width authority: it owns flat-width measurement and the fit test (so a fit decision can
  never diverge from the width reported for it) and `measureLineCount`, the side-effect-free replay used to rank
  `BestFitting` alternatives. A congruence test pins the replay's line count to the newlines `render` emits so the two
  walks cannot drift.
- **`DocDebugRenderer`** produces a stable structural dump of the IR (break opportunities, groups, indent scopes, rule
  labels) for maintainers; exposed via `Frmtr.debugDoc(...)`.
- **`DocExplainRenderer`** re-walks the document with the same fit logic to produce a presentation-free explanation of
  *why* each line laid out as it did. Because some constructs are pre-decided by the printers and emitted as forced
  breaks, the printers also record their own width arithmetic into a per-run side channel (`LayoutDecisionLog` /
  `PrinterWrap`) that never changes the rendered text; `explain(...)` merges the two so the report can name a construct
  and show true width arithmetic. This is a pure observation surface — `format(...)` never reads it.

## Java Formatter

`JavaFormatter` owns JavaParser configuration, pragma gating, parse-error handling, and the transform stage between
parsing and printing. It stores tokens and attributes comments because formatter rules need syntax-adjacent trivia.
Recovered (partially parsed) trees skip transforms so recovered syntax is printed raw rather than reordered.

Pragmas are core policy: the require-pragma gate honors a leading Javadoc `@format` marker, and ignore pragmas are
`frmtr-ignore`, `frmtr-ignore-start`, and `frmtr-ignore-end`.

### Dispatch layering

Printing is layered from a broad entry point down to specialized layouts, each level emitting `Doc`:

```
  JavaPrinter (one JavaFormatContext per run; shared type rendering)
      │  builds three composer groups
      ▼
  ExpressionPrinters │ DeclarationPrinters │ StatementPrinters
      │  per-node-kind handoff
      ▼
  rule envelopes + dispatchers            ── JavaFormatRule<N>: (node, LayoutContext) → Doc,
  (ExpressionDispatcher, …RuleEnvelope)      selected by node kind
      │
      ▼
  specialized printers & layouts          ── e.g. chains, lambdas, control flow, comments;
  (many focused *Printer / *Layout types)    complex break decisions extracted into *Layout helpers
      │
      ▼
  Doc IR  ── plus pure structural BreakRule<C> / BreakRuleRegistry
             (first-match-wins) for source-neutral break shapes such as the chain fan
```

Two dispatch styles coexist. `JavaFormatRule<N>` is the type-dispatched node→`Doc` handoff a dispatcher selects by node
kind. `BreakRule<C>` is a pure predicate over a construct-specific candidate paired with the source-neutral `Doc` it
emits, resolved first-match-wins in a `BreakRuleRegistry` — used where a break shape is a function of AST structure
alone (the canonical chain fan and its sub-shapes).

`JavaPrinter` runs the print traversal **twice**: a record-only dry run that settles comment ownership (below), then the
real render. The dry run costs roughly a second print, never a second parse.

### Width model

The formatter **reprints by width at the true output column.** Layout decisions are made by the renderer measuring
candidate `Doc`s at the real column, or by pure structural rules. Where a printer must
know a positional fact the node itself cannot see — the same-line prefix ahead of it (an assignment target, `return `,
a declarator `name = `), a trailer glued after it (a header's ` {` or `;`), or whether its line is already broken — that
fact travels in an immutable **`LayoutContext`** record threaded down the descent, so a construct that fits at the block
indent but overflows once its prefix is counted breaks instead of being emitted flat over width. `LayoutWidth` supplies
the indentation baselines these probes measure against.

A per-run **`SourceShapePolicy`** is the single home for the "respect the author's source shape here?" questions, including blank-line-between, fits-on-one-line, contained-comments, and try-with-resources shape.
It holds a small set of *fixpoint-safe* reads that the formatter's own output reproduces; the method-call / chain / object-creation / lambda hub reflows by width, not by the author's incidental line breaks.
Contained-comment gates read the run index for original nodes and fall back to JavaParser for detached clones outside that snapshot.
Governance tests keep new source-shape reads out of the printers.

### Chain and wrapping layout

Method-call chains are the most involved layout and route through a **fan layout**.
A multi-link chain that meets a structural threshold (a call/factory/constructor root at two-plus selectors, or a plain
receiver at three-plus) fans one selector per dotted line — matching google-java-format / prettier-java's "one segment
per line once the chain is a builder" convention — **even when the flat form would fit**. The fan shape is a pure
function of the AST (built once, shared across candidates) and is *ranked against* the compact/attached alternative by
`BestFitting` / `ConditionalGroup` at the true column, so the result is a fixpoint by construction (idempotent
re-formatting) instead of a source-shape-sensitive per-printer decision. The same fan seam is replicated at every host
position a chain can appear (initializers, assignment RHS, `return`, call/constructor arguments, binary operands,
statement expressions, lambda bodies). Array initializers follow an analogous structural fan-at-three rule.

The chain family is the largest cluster in the `java` package: `MethodCallChainPrinter` and `MethodCallPrinter` are the
entry points; `ChainFanLayout` holds the break-rule registries and fan builders; `ChainSegmentRenderer` renders the
individual `.selector(args)` links (flat, width-broken, force-broken, comment/lambda-carrying, root-close-attached) and
their prefixes; `ChainRootPromotionLayout` renders the chain root in each promotion shape (inline, grouped, broken,
expression-rendered): the grouped-promoted multi-argument root ranks its grouped and fully-broken shapes with
`Doc.bestFitting` at the true rendered column (the single-segment method-root break uses the same form but its caller
offers no broken alternative, so that ranking is inert), while a `LayoutWidth.nodeLine` indentation estimate still gates
the block-lambda multi-argument pre-emption and both block-lambda root hugs — the grouped-promoted hug and the
single-segment promoted-root hug — whose hard-break lambda bodies defeat fewest-lines ranking;
`CompactRootBrokenSegmentLayout` builds the
compact-root-with-broken-final-segment shapes (root and selector on one line, only the final argument list broken) and
the sibling that breaks the root's arguments and glues a no-arg segment to its close; `MethodCallChainSourcePlanner`,
`ChainSelectorLambdaLayout`, `LambdaBodyChainFanLayout`, `PackedMethodCallChainLayout`, and `VariableInitializerLayout`
handle the surrounding positions.

### Comment placement

Comment preservation is a dedicated subsystem, because comments are trivia (not AST nodes) and a naive build-order race
can drop or duplicate them. Ownership is settled up front: a **`JavaCommentMap`** and read-only
**`JavaCommentPlacementPolicy`** index the source's comments, and a **`CommentTracker`** pre-pass (the record-only dry
run above) records the single `(node, slot)` owner of every comment across all families (leading, trailing, adjacent,
own, orphan, interleaved). JavaParser attaches a trailing comment by a whitespace-sensitive rule, so
**`CanonicalCommentBinding`** supplies a whitespace-invariant binding — a comment→node skeleton (`preceding`/
`following`/`enclosing`) and token-span containment computed from the code-token stream, proven invariant by a property
test. The containment hub `SourceShapePolicy.hasContainedComments` reads it, so comment-presence layout gates decide the
same way regardless of how the input happened to wrap; a comment trailing a node past its last code token is not
"contained", so gates that withhold a comment-dropping path also consult the canonical trailing-comment owner. The real render then emits a comment only from its recorded owner and empty everywhere else,
so a comment can be offered in several eagerly-built ranked layout arms without being dropped or duplicated — the
renderer keeps only the arm it picks. A trailing line comment that follows a closing token — a statement/declaration
terminator (`); // note`, `}; // note`) or a broken chain segment's close (`) //`) — is re-anchored to hug the token it
trails even when a broken shape makes JavaParser re-bucket it as an enclosing-block/type orphan or the next selector's
own name; the member/block interleavers and `ChainCommentLayout` claim it under the terminator's `INTERLEAVED` slot as a
width-free `LineSuffix`, so it renders identically whether the inner expression is flat or broken. A width-affecting
shape gate can also consult ownership (`CommentTracker#claimedOutside`) so a local layout does not break itself to keep a
trailing comment an enclosing slot already owns, matching its comment-free form and staying stable across passes. Comment
*text* rendering (Javadoc reflow, banner preservation, block/line normalization) is centralized in one routine keyed on
parser kind. Many focused `*CommentLayout` helpers place comments within specific constructs (chains, control conditions,
switch labels, parameters, module directives).

### Correctness guardrails

Correctness is enforced by side-checks that never alter normal output:

- **AST-equivalence verify** re-parses the formatted output and asserts it is structurally equivalent to the input
  (modulo comments, whitespace, and the deliberate import reorder). It runs across the whole `frmtr-core` test suite,
  and `formatVerified(...)` exposes it as a fail-closed safety valve.
- **`CommentPresenceDiagnosticTest`** is the durable "no comment dropped" gate — it compares the comment-token multiset
  of input vs. output over every fixture and perturbation, catching drops that AST-equivalence (which ignores comments)
  cannot.
- **Idempotence** is checked by formatting twice and diffing; a small allowlist tracks known non-idempotent fixtures.
- Opt-in dev toggles (`FormatterGuardrails`) add comment-accounting and per-transform identity checks; the
  "each comment claimed at most once" invariant is on by default in the core build.

### Where to look

| Concern | Start here |
|---|---|
| Public entry / options | `Frmtr`, `FrmtrSession`, `FormatterOptions` |
| Parse, transforms, pragmas | `JavaFormatter`, `FormatterPragmas` |
| Dispatch entry | `JavaPrinter`, `ExpressionPrinters` / `DeclarationPrinters` / `StatementPrinters` |
| Method-call / chain wrapping | `MethodCallChainPrinter`, `ChainFanLayout`, `MethodCallPrinter` |
| Declarations / statements | the many `*DeclarationPrinter`, `*StatementLayout`, `BlockPrinter`, `SwitchPrinter` |
| Comment placement | `JavaCommentMap`, `JavaCommentPlacementPolicy`, `CommentTracker`, `*CommentLayout` |
| Width / positional facts | `LayoutContext`, `LayoutWidth`, `SourceShapePolicy` |
| Doc IR / rendering | `doc/Doc`, `doc/DocRenderer`, `doc/DocWidths` |
| Explain / debug | `doc/DocExplainRenderer`, `doc/DocDebugRenderer`, `LayoutDecisionLog` |
| Raw / recovery text | `RawSource`, `CompactSourceText`, the recovery helpers |

See [docs/java-formatter-internals.md](docs/java-formatter-internals.md) for the full helper map and
[docs/formatter-coverage.md](docs/formatter-coverage.md) for the AST-kind ownership table.

## File-Oriented Runs

`:frmtr-tooling` owns the adapter-neutral run model for checking or writing many files. Adapters supply the file list
and own presentation; the runner owns execution, ordering, and result aggregation.

- **`FormatterRunner`** exposes `check`, `write`, `checkVerified`, and `writeVerified`. `check` formats in memory and
  returns per-file results plus aggregate status; `write` stages output in a temp file and atomically renames it over
  the original (preserving POSIX mode), continuing past per-file failures. The `*Verified` variants format through the
  AST-equivalence path and fail closed before writing.
- `checkVerified` is the only path that scans formatted output for breakable over-width lines (via
  `dev.lanwen.frmtr.OverWidthLines`, which masks literals/comments and honors ignore pragmas). Findings are purely
  informational and never affect a file's changed/failed status.
- Multi-file runs use a bounded worker pool; each worker owns one `FrmtrSession` (never shared across threads, since
  the JavaParser instance is single-threaded). Results are collected into input-order slots so output stays
  deterministic regardless of completion order. Progress is a side-channel callback of started/running/finished
  snapshots.
- **Shared output models** keep CLI and Gradle consistent: `UnifiedDiffRenderer` (patch-like unified diffs, with an
  opt-in width-guide decoration), and `FormatterFailureRenderer` / `FormatterRunFailureRenderer` (structured diagnostic
  text split into semantic spans so each adapter applies its own presentation).

Source *discovery* stays adapter-specific: the CLI uses selectors and `.gitignore`; the Gradle plugin builds one
canonical collection from Java source sets and Gradle filters.

## CLI

The CLI is a Picocli adapter over the public API and the tooling runner. It owns argument validation, source discovery,
stream routing, terminal presentation, application packaging, and process-exit mapping — but **no formatting policy**:
new behavior lands in the API/engine first, and the CLI exposes it by translating flags into `FormatterOptions` or by
selecting a presentation. It currently surfaces line width, indentation, parser language level, and parse-error
behavior.

Notable adapter surfaces:

- **`--verify`** selects the verified format path (`writeVerified` / `checkVerified`); it does not own the equivalence
  check. `--check --verify` additionally prints advisory over-width warnings to stderr without affecting exit codes.
- **`--explain`** maps the core `DocExplanation` to terminal text (`ExplainView`), preserving byte-identical formatted
  output.
- **`--render-indentation`** is a presentation-only transform that visualizes block vs. continuation indentation using
  a per-line structural signal from the renderer (`formatIndented` / `IndentedSource`); off by default and mutually
  exclusive with the non-printing modes.
- **Exit codes** map run outcomes by highest severity: `0` clean, `1` would-change (check modes), `2` parse/IO/usage
  error, `3` a verify violation (a formatter bug), keyed on `FormatterException.verifyViolation()` rather than message
  matching.

Selector discovery, colorization, and in-place progress repainting are CLI-local layers over the plain tooling models.
The root build exposes `frmtrSelfCheck` / `frmtrSelfFormat` as one-invocation dogfood paths over this checkout
(excluding the fixture corpora).

## Gradle Plugin

The Gradle plugin is the build-tool adapter over the same API and tooling runner. It owns Gradle model wiring, task
registration, source-set discovery, incremental input declarations, cache semantics, and Gradle-native diagnostics.
Runner progress flows through `GradleProgressLogger`, which reports check/format lifecycle snapshots, counters, worker counts, and active project-relative paths through Gradle's public `Logger` at `INFO` level.
Ordered final diagnostics and diffs remain lifecycle output assembled from `FormatRunResult` after the runner finishes.

Applying the plugin creates project-local `frmtrCheck` / `frmtrFormat` lifecycle tasks and an extension for that
project only — no recursive application or root aggregation. Multi-project runs rely on Gradle's normal task-selector
behavior (`--continue` lets modules report independently). When the Java plugin is present, Java formatting is enabled
by convention over one canonical file collection (source sets plus filters, build dir excluded, no implicit dependency
on source-generation tasks). Check tasks are cacheable verification tasks; format tasks stay non-cacheable because they
rewrite sources in place. Parser level is a semantic DSL value mapped into `FormatterOptions.JavaLanguageLevel`.

## Native Binary

Native-image support is split so JVM runtime classpaths stay clean: `:frmtr-cli` owns the executable entrypoint and
native configuration, while `:frmtr-native-image-support` (visible only to native builds/tests) registers JavaParser
AST node fields for hosted reflection via `JavaParserReflectionFeature`. Picocli's annotation processor generates CLI
reflection metadata during `:frmtr-cli:compileJava`; proxy metadata is disabled.

Native-image targets the build OS and uses a native-image-capable JDK 25 launcher without raising the bytecode level of
the JVM artifacts. `:frmtr-cli:nativeDistributionZip` wraps the platform executable as a JReleaser `BINARY`
distribution; the release workflow builds archives on Linux x64, macOS arm64, and Windows x64, then publishes GitHub
release assets, Maven Central artifacts, the Homebrew formula, and the Gradle Plugin Portal publication. Release
automation derives version bumps from Conventional Commits-style PR titles and starts post-release development at the
next patch snapshot (schema in [docs/release-automation.md](docs/release-automation.md)).

## Tests

The suite is module-scoped:

- **`:frmtr-core`** — formatter engine, document rendering, parser behavior, Java output, and formatter fixtures.
- **`:frmtr-tooling`** — file runs, diffs, ordering, de-duplication, write behavior, per-file failure handling.
- **`:frmtr-cli`** — selectors, discovery, ignore handling, stream modes, summaries, diagnostics, option validation,
  exit codes.
- **`:frmtr-gradle-plugin`** — TestKit functional coverage for task registration, defaults, lifecycle, progress logging, filters, and language-level inference.
- **`:frmtr-native-image-support`** / **`:frmtr-cli:nativeTest`** — reflection-registration coverage and explicit
  native-image compatibility checks.

Formatter behavior is covered primarily by **golden fixtures** under `frmtr-core/src/test/resources/format/**` (with
option-variant outputs via sidecar properties) rather than inline `Frmtr.format(...)` assertions. Conventions and
new-rule expectations live in [docs/testing-strategy.md](docs/testing-strategy.md).

### Real-World Corpus Check

`.github/workflows/corpus.yml` runs a release-PR correctness check against a pinned real-world corpus
(`testcontainers/testcontainers-java`). It reuses the shipping CLI: `frmtr --write --verify` over the corpus
(parse-stability plus AST-equivalence), then `frmtr --check` over the now-formatted sources (one-pass idempotence). The
two steps cover distinct invariants, and the workflow reads the CLI's distinct exit codes to separate a verify
violation (`3`) from a parse/IO failure (`2`) and an idempotence miss (`1`). It runs on generated `release` PRs and via
`workflow_dispatch`, not on every ordinary PR.
