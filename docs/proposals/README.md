# frmtr Improvement Roadmap

**Goal:** evolve `frmtr` into the best Java formatter available — the fastest, the most
correct, and the friendliest for both users and maintainers.

This is the umbrella proposal. It states the strategic direction and tracks a set of concrete
improvements grouped by leverage (Big / Middle / Small). Items that warrant a dedicated design
investigation link out to a focused proposal in this directory.

## Status legend

| Status | Meaning |
| --- | --- |
| 🔵 Proposed | Captured here; not yet scheduled. |
| 🟣 Investigating | A focused proposal doc is being / has been drafted. |
| 🟡 Accepted | Agreed to do; not started. |
| 🟢 In progress | Implementation underway. |
| ✅ Done | Landed on `main`. |
| ⚪ Deferred | Intentionally parked. |

**Overall status:** 🔵 Proposed — this roadmap is a starting point for discussion. Two items have
landed (**S5** and **S3**, ✅); the rest is unscheduled. Focused investigation docs (linked below)
have been drafted to de-risk the highest-leverage items before any implementation.

### Relationship to existing proposals in this directory

This roadmap sits alongside proposals the maintainer already started; several map directly onto
roadmap items and are cross-linked from the relevant sections:

- [`formatter-owned-syntax-view.md`](formatter-owned-syntax-view.md) (held for architecture review)
  — the broad version of **B1**; B1's `SourceShapePolicy` is proposed as its concrete first slice.
- [`comment-containment-index.md`](comment-containment-index.md) (partially implemented) — a perf +
  comment-handling effort that touches the same surface as **B1/B2**.
- [`cli-discovery-lazy-ignore.md`](cli-discovery-lazy-ignore.md) (implemented) — already delivered
  part of the discovery speed-up that **M3** builds on.
- [`implemented/`](implemented/) — landed proposals (e.g. the native-image companion task).

## Where frmtr stands today

The architecture is already strong:

- A clean two-pass design: `JavaParser` AST → `Doc` intermediate representation → width-based
  `DocRenderer` (Wadler/Lindig-style pretty printing).
- Clear ownership layering: envelope gates → dispatchers → specialized printers, wired through
  per-domain composers (`ExpressionPrinters`, `DeclarationPrinters`, `StatementPrinters`).
- A GraalVM native image, a Picocli CLI, and a Gradle plugin.
- A glob-discovered, golden-file fixture suite.

The biggest opportunities are therefore not "rewrite," but targeted leverage:

1. The **amount of original-source peeking** (`SourceShape`, `RawSource`, `CompactSourceText`,
   "source-multiline" predicates) that makes output input-dependent and the code fragile.
2. An **IR that cannot express** several things printers hand-roll today (trailing comments,
   fill-wrapping, "break parent if any child breaks", conditional layouts).
3. **No correctness safety net** beyond hand-written golden files — semantic-preservation is not
   independently verified.
4. **No performance or distribution story** to back a "fastest, everywhere" claim (no benchmarks,
   sequential file processing, no editor integration).

---

## 🟥 Big — architectural, high-leverage

### B1. Centralize source-shape coupling into one explicit policy
**Status:** 🟣 Investigating · _focused proposal:_ [source-shape-policy-consolidation.md](source-shape-policy-consolidation.md)

> **Investigation finding:** ~115 distinct source-peeking call sites (26 `sourceShape.*`, 41
> `rawSource.*`, 43 `compactSource.*`, 5 hand-rolled blank-line probes) answer the same few
> questions inconsistently. Proposes a per-run `SourceShapePolicy` on `JavaFormatContext`; first
> slice = unify the "was this multiline?" definition. Positioned as the concrete first step of the
> held `formatter-owned-syntax-view` proposal.

Formatting decisions currently read the original token layout in many places (`SourceShape`,
`RawSource`, `CompactSourceText`, source-multiline predicates threaded through printers). Output
therefore depends on input formatting, which both creates many special cases and makes idempotence
hard to reason about. Preserving author intent (blank lines, deliberately multiline calls) is a
legitimate, user-friendly feature — so the goal is **not** to remove it, but to move every
"respect the source shape here?" decision behind a single documented policy and forbid printers
from reaching into raw source directly.

- **Serves:** maintainer-friendly, predictable output, provable idempotence.
- **Effort:** large, phased. Unblocks B2 and B3.

### B2. Enrich the Doc IR with the combinators printers are faking today
**Status:** 🟣 Investigating · _focused proposal:_ [doc-ir-combinators.md](doc-ir-combinators.md)

> **Investigation finding:** recommends implementing **`lineSuffix` first** — it retires the
> largest, most bug-prone body of special-case code (trailing-comment placement + the enum
> comma-ordering coupling that has broken repeatedly) with the least renderer risk (zero width, no
> effect on existing fit/break decisions, fixtures stay byte-identical). Caveat: land **S5** before
> adding any primitive, so new variants don't have to edit two parallel width switches — **S5 is now
> done** (`6e4f600a`), so this prerequisite is satisfied.

The IR has 9 primitives (`Text/Concat/Line/SoftLine/HardLine/Indent/Group/IfBreak/Label`). It is
missing the ones that matter most:

- **`lineSuffix`** — defer trailing content (comments!) to end of line. Would retire most of the
  comment-placement machinery and the comma/comment ordering logic that has repeatedly broken.
- **`fill`** — pack as many items per line as fit (arrays, arguments, throws lists, enum
  constants).
- **`breakParent` / `conditionalGroup`** — "if any child breaks, break the parent" and "try layout
  A, else B, else C", currently emulated with width probes.

- **Serves:** maintainer-friendly (delete special cases), richer layouts, uniform comments.
- **Effort:** large but additive and incremental. Pairs with B1.

### B3. A correctness safety net: AST-equivalence + idempotence + a real-world corpus
**Status:** 🟢 In progress — Layers 1-2 landed · _focused proposal:_ [semantic-preservation-safety-net.md](semantic-preservation-safety-net.md)

> **Layer 1 landed.** The AST-equivalence verify mode is implemented: a `dev.lanwen.frmtr.debug.verify` toggle
> (off by default) re-parses the formatter output and asserts it is structurally equivalent to the input (via
> `AstEquivalence` + `EqualsVisitor`), with import order, comments, parentheses, modifier order, block-level empty
> statements, and text-block whitespace normalized away — but a dropped/duplicated import or any structural change still
> fails. Enabled across the `frmtr-core` test suite so every golden fixture is AST-checked.

> **Layer 2 landed.** `IdempotencePropertyTest` runs the idempotence and semantic-preservation properties over a corpus
> broader than the golden fixtures: every fixture input verbatim, two parse-preserving whitespace perturbations of each
> (token-stream rewrites that never touch literal/comment content), and diverse hand-written snippets. It asserts
> one-pass idempotence + AST-equivalence on well-shaped inputs and AST-equivalence + parse-stability on perturbed inputs,
> and deliberately does **not** assert convergence (the formatter preserves intentional source shape). The broadened
> corpus surfaced six real data-loss / parse-stability defects on perturbed inputs (dropped enum separators, dropped
> module directives), excluded from the green corpus and recorded as findings in the proposal. Layer 3 (real-world OSS
> corpus harness) remains proposed.

> **Investigation finding:** an idempotence assertion already exists (`FrmtrTest.java:29`); the gap
> is AST-equivalence. Proposes a `dev.lanwen.frmtr.debug.verify` toggle parallel to the existing
> `debug.guardrails`. Key subtlety: `ImportSortTransform` deliberately reorders imports, so the
> comparator must sort both trees' imports with the formatter's own order before comparing — but
> still fail on a dropped/duplicated import.

A formatter must never change program meaning. Today that is guarded only by golden fixtures, which
is why an earlier change silently dropped enum separators undetected. Add three layers:

1. A debug/CI mode that **re-parses output and asserts AST equivalence** (modulo trivia).
2. A property test: `format(format(x)) == format(x)` over generated and real inputs.
3. A **corpus harness** that formats large OSS codebases (JDK, Spring, Guava, …) in CI and checks
   parse-stability + idempotence + AST-equivalence.

- **Serves:** correctness, plus the confidence to do B1/B2 aggressively.
- **Effort:** medium-large; layer 1 is small and high-value alone.

---

## 🟧 Middle

### M1. A benchmark discipline (JMH + macro + competitive diff)
**Status:** 🔵 Proposed

No benchmarks exist; "fastest" cannot be claimed without them. Add JMH microbenchmarks (render-only,
full pipeline), a macro run over the B3 corpus, CI regression gates, and published head-to-head
numbers + output diffs vs google-java-format / palantir-java-format / Spotless. This also reveals
whether parsing or rendering dominates, which directs further optimization.

- **Serves:** "fastest" credibility, maintainer-friendly.
- **Effort:** medium. Methodology-heavy, low risk. (No dedicated investigation doc — execute directly.)

### M2. Linear-time renderer (width memoization + bounded `fits`)
**Status:** 🟢 Implemented · _focused proposal:_ [linear-time-doc-renderer.md](linear-time-doc-renderer.md)

> **Investigation finding:** `render`'s `Group` case fires one `fits` per group, and `fits` calls
> the unbounded `flatWidth` on every `Concat` child → overlapping subtrees re-measured with no
> cache or early stop. Recommends an `IdentityHashMap<Doc, Integer>` memo held by the renderer
> instance (records can't carry lazy fields), FLAT-mode-keyed (because `IfBreak` makes width
> mode-dependent), plus bounded `fits`. Absorbs **S5** — whose unification step is **already landed**
(`6e4f600a`); M2 adds the memoization and bounded lookahead.

> **Follow-up finding:** a local 50-pass no-diff macro run on a project with ~600 files showed the
> implemented M2 branch slower than `main` in central tendency (`main`: mean 3.342s / median 3.140s /
> p95 5.440s; M2: mean 3.718s / median 3.540s / p95 5.320s). Keep the single measurement walker
> maintainability gain, but add a follow-up to reduce the implementation overhead.

`DocRenderer` re-walks subtrees to measure width, so nested groups degrade toward O(n²). `Doc` nodes
are immutable records, so flat-width can be precomputed bottom-up once and `fits` can short-circuit
at the first hard line / when remaining < 0 (bounded lookahead). O(n²) → O(n·w). The
`fits`/`flatWidth` unification (S5) is already done; M2 adds memoization on top of the resulting
single `measureFlat` function.

- **Serves:** fastest, maintainer-friendly.
- **Effort:** contained to `DocRenderer`.

### M2a. Reduce implemented renderer measurement overhead
**Status:** 🔵 Proposed

M2 preserved the single width-authority contract but added runtime overhead on a real macro corpus.
Optimize `DocWidths.Measurement` without reintroducing separate `fits` and `flatWidth` switches:
replace per-node `Budget` / `MeasureResult` record traffic with primitive sentinel returns, fast-path
cached complete widths, and only consider more complex overflow-bound caching after allocation
profiling proves it is worthwhile.

- **Serves:** fastest, maintainer-friendly.
- **Effort:** small-medium; must preserve byte-identical output and the one-switch Doc measurement
  contract.

### M3. Multi-file parallelism + content-addressed caching
**Status:** 🟢 In progress — runner-level bounded parallelism and CLI progress landed; Gradle incremental/cache work remains · _focused proposal:_ [multi-file-parallelism-and-caching.md](multi-file-parallelism-and-caching.md)

> **Implementation state:** `FormatterRunner` now uses order-preserving bounded-pool parallelism
> for `check` and `write`. The Gradle tasks still declare `@InputFiles` but no outputs /
> `@CacheableTask` / `InputChanges`, so they reformat the whole source set every run. `Frmtr.format`
> is **definitively thread-safe** (pure function of `(source, options)`; fresh `JavaFormatContext`
> per call, no shared mutable state). The CLI now renders progress on a separate side channel while
> preserving deterministic final result output. The remaining recommendation is making Gradle's build
> cache the content-addressed store; a persistent CLI cache stays out of scope per the lazy-ignore
> non-goal, and Gradle-native progress/logging remains a separate follow-up if needed.

The tooling runner now formats independent files on a bounded thread pool, while the Gradle plugin
still reformats unchanged files. Finish this item by skipping files whose `(content-hash, options,
formatter-version)` is unchanged via Gradle incremental inputs/build-cache. On a monorepo this is
the difference between "fast" and "instant on re-run."

- **Serves:** fastest (real-world), user-friendly.
- **Effort:** medium.

### M4. Editor integration via an LSP server (with range formatting)
**Status:** 🟣 Investigating · _focused proposal:_ [lsp-editor-integration.md](lsp-editor-integration.md)

> **Investigation finding:** the core only does whole-document `format(String)` — `rangeFormatting`
> is genuinely new core work. Recommends a new `frmtr-lsp` module (native-compiled for startup),
> Phase 1 = whole-document format-on-save (zero core change, single full-document `TextEdit`),
> Phase 2 = `Frmtr.formatRange(...)` that formats the whole document then returns only the diff
> hunks overlapping the (line-clamped) range — so range output can never disagree with
> format-on-save, leaning on B3 for the equivalence property.

Today it is CLI + Gradle only. A small LSP server (`textDocument/formatting` +
`rangeFormatting`, backed by the native binary for instant startup) unlocks format-on-save in
VS Code / IntelliJ / Neovim — the single biggest driver of adoption. Requires range/partial
formatting, which is independently valuable.

- **Serves:** user-friendly (large), reach.
- **Effort:** medium-large.

---

## 🟨 Small — self-contained, slot in anywhere

### S1. Correct display-width: tabs, CJK wide chars, emoji, combining marks
**Status:** 🔵 Proposed

The width model counts `String.length()` (UTF-16 units), so fullwidth CJK characters and
astral-plane emoji mis-measure column width and produce wrong break decisions on international code.
Switch the width helpers to grapheme + East-Asian-width counting.

- **Serves:** correctness for global users. Isolated.

### S2. `.editorconfig` support
**Status:** 🔵 Proposed

Read `indent_style`, `indent_size`, `max_line_length`, `end_of_line`, `insert_final_newline`,
`charset`; they map almost 1:1 onto `FormatterOptions`. Near-zero-config adoption in mixed-tool
repos.

- **Serves:** user-friendly.

### S3. `--explain` / surfaced `debugDoc`
**Status:** ✅ Done — shipped as the `--explain` CLI mode.

`debugDoc` was core-only; the `--explain` CLI mode now shows which rule decided a given break and
the width arithmetic behind it — "why did this line wrap?". Width-driven wraps are captured by the
printers into a per-run side channel (`PrinterWrap`/`LayoutDecisionLog`) and surfaced with humanized
construct names; structural breaks are filtered so only causal wraps appear; `-v` exposes raw rule
labels and the full decision tree. Explain is a pure observer — formatted output is byte-identical to
a normal run. The `explain-diff` agent skill wraps this for "explain the formatting of this file".

- **Serves:** maintainer-friendly, user-friendly. Cheap (Label already carries provenance).
- **Follow-ups:** widen width-recording to object-creation argument lists, extend structural
  filtering to `try`/`switch`/loops, and collapse repeated rule-driven notes (see [B2](#b2-enrich-the-doc-ir-with-the-combinators-printers-are-faking-today) for richer labels).

### S4. One-line adoption: pre-commit hook + GitHub Action + changed-hunks mode
**Status:** 🔵 Proposed

Ship an official `pre-commit` hook definition and a GitHub Action, plus a "format only changed git
hunks" mode for gradual rollout in legacy codebases.

- **Serves:** user-friendly (adoption). Mostly packaging.

### S5. Collapse `fits` and `flatWidth` into one function
**Status:** ✅ Done — landed on `main` in `6e4f600a`.

They were structurally identical switches over `Doc`; a new IR variant had to edit both — exactly the
divergence risk B2 multiplies. **Now unified:** `DocRenderer` has a single `measureFlat(doc, mode)`
width authority with a `NO_FIT` sentinel (replacing the dual `-1`/`false` hard-line encoding), and
`fits(doc, remaining)` is a thin wrapper; the dead `indent` parameter was dropped. Behavior-preserving
(full `:frmtr-core` suite green, 287 tests). This is the prerequisite that M2 and B2 build on.

- **Serves:** maintainer-friendly. Landed as ~30 lines (net −10).

---

## Suggested sequencing

1. **S5 → M2** (cheap cleanup that unlocks the renderer speedup) and **B3 layer 1**
   (AST-equivalence check — small, catches real bugs now). _S5 is done (`6e4f600a`); M2 is unblocked._
2. **B2** incrementally, starting with `lineSuffix` (retire the comment machinery), letting it pull
   **B1** along where the two intersect.
3. **M1** in parallel (numbers are needed before/after B2/M2), then **M3 / M4** for the adoption and
   speed story.
4. Smalls (**S1–S4**) slot in anywhere; **S2 / S4** are quick user-facing wins.

The throughline: **B2 + B1 shrink the code and the bug surface, B3 lets you make those changes
fearlessly, and M1–M4 turn a good formatter into the fastest one, everywhere.**

---

## Focused proposals in this directory

Drafted for this roadmap:

| ID | Topic | Doc |
| --- | --- | --- |
| B1 | Centralize source-shape coupling | [source-shape-policy-consolidation.md](source-shape-policy-consolidation.md) |
| B2 | Enrich the Doc IR | [doc-ir-combinators.md](doc-ir-combinators.md) |
| B3 | Correctness safety net | [semantic-preservation-safety-net.md](semantic-preservation-safety-net.md) |
| M2 | Linear-time renderer | [linear-time-doc-renderer.md](linear-time-doc-renderer.md) |
| M3 | Parallelism + caching | [multi-file-parallelism-and-caching.md](multi-file-parallelism-and-caching.md) |
| M4 | LSP / editor integration | [lsp-editor-integration.md](lsp-editor-integration.md) |

Pre-existing, related:

| Topic | Doc | Maps to |
| --- | --- | --- |
| Formatter-owned syntax view | [formatter-owned-syntax-view.md](formatter-owned-syntax-view.md) | B1 (broad form) |
| Comment containment index | [comment-containment-index.md](comment-containment-index.md) | B1/B2 surface, perf |
| Lazy `.gitignore` discovery | [cli-discovery-lazy-ignore.md](cli-discovery-lazy-ignore.md) | M3 (delivered part) |

> Items without a dedicated doc — **M1** (benchmark discipline, methodology-heavy and low-risk) and
> the smalls **S1–S5** (self-contained) — are specified inline above and can be picked up directly.
> **S5** (the recommended prerequisite for **M2** and **B2**) is ✅ **done** — landed on `main` in
> `6e4f600a`.
