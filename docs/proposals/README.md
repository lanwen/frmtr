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

**Overall status:** The big architectural arc has largely **landed**. The source-shape coupling was centralized
(**B1**) and then inverted to reprint-by-default behind a closed, ratchet-guarded exception set (**B4**); the Doc IR
was enriched (**B2**); the ranked-layout engine (`Doc.bestFitting`) shipped and the whole method-call / chain /
object-creation / lambda **hub** was flipped to pure-AST + true-column measurement (the layout-decision-model,
convergence-redesign, chain-path-unification, hub-canonicalization, and leftEdgePrefix-foundation efforts — now
archived as implemented). **S3**, **S5**, **S6**, **S7**, **S8**, **S9**, and **M2/M2a** are done. The archived
provenance records live in [`archived/`](archived/).

What remains active: **B3** Layer 3 (real-world corpus harness), **M3**'s optional Gradle-native progress follow-up,
**M4** (LSP), **M1** (benchmark discipline), the [printer-contract-inversion](printer-contract-inversion.md) Stage-3
per-construct generalization (= the layout-decision-model's **LDM-4**), the
[comment-containment-index](comment-containment-index.md) caller migration, the
[cli-discovery](cli-discovery-lazy-ignore.md) ignored-directory pruning, and the still-proposed smalls (**S1**, **S2**,
**S4**).

### Relationship to existing proposals in this directory

This roadmap sits alongside proposals the maintainer already started; several map directly onto
roadmap items and are cross-linked from the relevant sections:

- [`formatter-owned-syntax-view.md`](formatter-owned-syntax-view.md) (held for architecture review)
  — the broad version of **B1**; B1's `SourceShapePolicy` shipped as its concrete first slice.
- [`comment-containment-index.md`](comment-containment-index.md) (partially implemented — the index
  boundary landed; caller migration remains) — a perf + comment-handling effort on the **B1/B2** surface.
- [`cli-discovery-lazy-ignore.md`](cli-discovery-lazy-ignore.md) (implemented; one guarded follow-up —
  ignored-directory pruning) — already delivered part of the discovery speed-up that **M3** builds on.
- [`archived/`](archived/) — implemented and superseded proposals retained as provenance records (e.g. the
  native-image companion task, the S6–S9 audit smalls, B1/B2/B4, and the layout-engine cluster).

## Where frmtr stands today

The architecture is already strong:

- A clean two-pass design: `JavaParser` AST → `Doc` intermediate representation → width-based
  `DocRenderer` (Wadler/Lindig-style pretty printing).
- Clear ownership layering: envelope gates → dispatchers → specialized printers, wired through
  per-domain composers (`ExpressionPrinters`, `DeclarationPrinters`, `StatementPrinters`).
- A GraalVM native image, a Picocli CLI, and a Gradle plugin.
- A glob-discovered, golden-file fixture suite.

The opportunities that framed this roadmap were targeted leverage, not a rewrite. Three of the four are now
**largely addressed**:

1. ~~The **amount of original-source peeking**~~ — **addressed.** Consolidated behind `SourceShapePolicy` (B1) and
   then retired: the hub reprints by width behind a closed `SourceShapeException` set (B4); `SourceShapePolicy.wasMultiline`
   is gone.
2. ~~An **IR that cannot express** trailing comments, fill-wrapping, break-parent, conditional/ranked layouts~~ —
   **addressed.** `LineSuffix`, `Fill`, `BreakParent`, `ConditionalGroup`, and `BestFitting` all landed (B2 + the
   ranked-layout engine).
3. ~~**No correctness safety net**~~ — **largely addressed.** AST-equivalence verify + an idempotence property test
   over a perturbed corpus landed (B3 Layers 1–2); the real-world corpus harness (Layer 3) is the remaining piece.
4. **The performance / distribution story is the main open frontier.** Multi-file parallelism + Gradle caching landed
   (M3), but there is still no benchmark discipline (M1) and no editor integration (M4).

---

## 🟥 Big — architectural, high-leverage

### B1. Centralize source-shape coupling into one explicit policy
**Status:** ✅ Done (archived) — `SourceShapePolicy` consolidation + shape-independent comment ownership landed, and the residual `strict-claims` guardrail is now enabled by default as a CI gate (`strict-claims=true` in `frmtr-core/build.gradle.kts`) after the comment-ownership consolidation completed via the claim-neutral `ownedComment` rail (printer-contract-inversion Phases A–D). Successor **B4** then closed and retired the source-shape read set (`wasMultiline` grep = 0). · _focused proposal:_ [archived/source-shape-policy-consolidation.md](archived/source-shape-policy-consolidation.md) · _successor:_ [B4 / archived/reprint-by-default-break-rules.md](archived/reprint-by-default-break-rules.md)

> **Investigation finding:** ~115 distinct source-peeking call sites (26 `sourceShape.*`, 41
> `rawSource.*`, 43 `compactSource.*`, 5 hand-rolled blank-line probes) answer the same few
> questions inconsistently. Proposes a per-run `SourceShapePolicy` on `JavaFormatContext`; first
> slice = unify the "was this multiline?" definition. Positioned as the concrete first step of the
> held `formatter-owned-syntax-view` proposal.
>
> **Closing note:** delivered end to end. `SourceShapePolicy` and shape-independent comment ownership landed; the
> comment-ownership consolidation then completed via the claim-neutral `ownedComment` rail, so the `strict-claims`
> guardrail (`FormatterGuardrails.STRICT_CLAIMS_PROPERTY`) is now enabled by default as a CI gate. Successor **B4**
> carried the reads further — closing them into a ratchet-guarded `SourceShapeException` set and retiring the
> `wasMultiline` family entirely.

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
**Status:** ✅ Done (archived) — all four Doc-IR primitives + group identity landed (`LineSuffix`, `BreakParent`, `Fill`, `ConditionalGroup`, plus `BestFitting`); the chain-collapse goal was documented as not achievable/expressible (see outcomes). The separate ownership consolidation also completed — every comment family moved to the claim-neutral `ownedComment` rail and `strict-claims` is now a CI gate. · _focused proposal:_ [archived/doc-ir-combinators.md](archived/doc-ir-combinators.md)

> **Outcome:** all four primitives — `LineSuffix`, `BreakParent`, `Fill`, `ConditionalGroup` — plus
> the optional `Group`/`IfBreak` `groupId` landed on `main`, with renderer, width, debug, and
> explain support and factory validation (`Fill` rejects a non-empty even-length list;
> `ConditionalGroup` rejects an empty alternative list). `LineSuffix` delivered the headline win:
> the enum trailing-comment comma-ordering coupling is gone (the constant emits a `lineSuffix`), and
> records, control conditions, statements, expression lists, and method-call chains route trailing
> comments through it too. `Fill`'s only consumer so far is `ThrowsClausePrinter` (wrapping
> overflowing `throws` lists). **The chain-collapse goal was not achievable** — `ConditionalGroup`
> cannot replace `MethodCallChainPrinter`'s `Optional<Doc>` dispatch byte-identically (the chain
> probes flattened strings at fixed-indent/source-column baselines via `LayoutWidth`, vs
> `ConditionalGroup` measuring flat fit at the actual output column) nor expressibly (the chain ranks
> multiple *broken* layouts on structural/source predicates; `ConditionalGroup` is Prettier-shaped: N
> flat candidates + one final broken fallback), so `MethodCallChainPrinter` and `LayoutWidth` stay.
> Enum-constant `Fill` packing was **declined** as an opinionated reflow at odds with frmtr's
> source-shape-preservation bias.

> **Ownership consolidation — Stage 1 (landed).** Separate from the Doc-IR primitives, B2 also owns
> making comment ownership explicit so the `strict-claims` guardrail can eventually be enabled. Stage 1
> introduces an explicit pre-claim ownership subsystem (`OwnerSlot` role enum, `OwnerKey(anchor, slot)`
> identity-keyed record, a `CommentTracker.ownership` map populated by a read-only
> `assignOwnership(unit)` pre-pass, and an `ownsHere` filter) and migrates **only** the
> `trailingLineComment` family to it. This is output-neutral and proven so: the trailing family is the
> unique one a pure source-order rule reproduces byte-for-byte (zero cross-node `ownsHere` rejections
> corpus-wide; goldens byte-identical). The remaining families (leading/adjacent/own/interleaved/orphan)
> need a **traversal-order** ownership rule — a pure source-order rule diverges ~12% on contested
> leading/own comments (the parent-interleaver-beats-child cases) — so they stay on today's
> first-claim-wins behavior. `strict-claims` stays off until all families migrate **and** the eager
> `Optional<Doc>` candidate-ladder probes render claim-free (even the migrated trailing slot still
> sees same-owner probe re-claims an ownership rule cannot dedupe).

The IR had 9 primitives (`Text/Concat/Line/SoftLine/HardLine/Indent/Group/IfBreak/Label`); it gained
the four it was missing most:

- **`lineSuffix`** — defer trailing content (comments!) to end of line. **Done and widely adopted**,
  retiring the comment-placement machinery and the comma/comment ordering logic that had repeatedly
  broken.
- **`fill`** — pack as many items per line as fit. **Done**; first consumer is the throws-clause
  printer. Arrays/arguments/enum-constant packing were not migrated (enum packing was declined as an
  opinionated reflow).
- **`breakParent` / `conditionalGroup`** — "if any child breaks, break the parent" and the
  Prettier-style "first flat layout that fits, else the final broken fallback". **Both done.**
  `breakParent` has one consumer (`RecordDeclarationPrinter`); `conditionalGroup` is additive with no
  Java-printer consumer yet, because collapsing the method-chain `Optional<Doc>` dispatch onto it is
  neither byte-identical nor expressible (see outcome above).

- **Serves:** maintainer-friendly (delete special cases), richer layouts, uniform comments.
- **Effort:** large but additive and incremental. Paired with B1.

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
3. A **corpus harness** that formats large, pinned real-world Java corpora in CI and checks
   parse-stability + idempotence + AST-equivalence.

- **Serves:** correctness, plus the confidence to do B1/B2 aggressively.
- **Effort:** medium-large; layer 1 is small and high-value alone.

---

### B4. Reprint by default: structural break rules + a closed source-shape exception set
**Status:** ✅ Done (archived) — the default flipped from preserve-the-author's-shape to reprint-from-scratch: a closed,
ratchet-guarded `SourceShapeException` set (terminal after #291/#293), the `wasMultiline` family retired behind named
structural `BreakRule`s (#259/#260/#281), and the satellite + hub reads retired (#262–#266, hub atomic flip #279).
· _focused proposal:_ [archived/reprint-by-default-break-rules.md](archived/reprint-by-default-break-rules.md)
· _execution plan for the hub:_ [archived/hub-canonicalization-atomic-rewrite.md](archived/hub-canonicalization-atomic-rewrite.md)
· _D3 atomic-flip map (per-read consumer/replacement guide):_ [archived/hub-canonicalization-d3-flip-map.md](archived/hub-canonicalization-d3-flip-map.md)
· _post-flip true-column foundation (leftEdgePrefix; F1–F8 landed):_ [archived/left-edge-prefix-foundation.md](archived/left-edge-prefix-foundation.md)

The satellite constructs (params, throws, control-condition, ternary, enclosed-binary-operand,
try-resource) landed as small verified commits (#262–#266). The method-call / chain / object-creation / lambda
**hub** could not be retired incrementally — four corpus-proven attempts each oscillated or over-widthed — so it was
converted in a single **atomic** flip to pure-AST + true-column (#279), following
[archived/hub-canonicalization-atomic-rewrite.md](archived/hub-canonicalization-atomic-rewrite.md): the
renderer-measured substrate and structural residue gates were built byte-identically first, then the source-read gates
were removed all at once, validated against golden-independent corpus invariants (idempotence, AST-equivalence, comment
parity, over-width). The post-flip true-column foundation (leftEdgePrefix, LineBudget/widthBudget retirement) then
landed as [archived/left-edge-prefix-foundation.md](archived/left-edge-prefix-foundation.md)'s F1–F8 slices.

---

## 🟧 Middle

### M1. A benchmark discipline (JMH + macro + competitive diff)
**Status:** 🟣 Investigating · _focused proposal:_ [performance-followups-from-jfr.md](performance-followups-from-jfr.md)

A JMH microbenchmark harness (`:frmtr-bench`) measures formatter hot paths against the real code; the pieces still
missing before "fastest" can be claimed are a committed macro run over the B3 corpus, CI regression gates, and
published head-to-head numbers + output diffs against other Java formatters. Measurement already shows parser
tokenization and comment queries — not rendering — dominate CPU, which directs further optimization.

> **M2a follow-up finding:** paired macro checks over an anonymized 631-file external corpus did not
> show a whole-CLI win from M2a (paired mean delta `+0.18s`, median delta `-0.09s`, M2a faster in
> `10/20` pairs). Denser JFR allocation sampling did confirm the targeted cleanup:
> `DocWidths$Budget` (`19` samples / `6496.3 kB`) and `DocWidths$MeasureResult` (`20` samples /
> `7193.9 kB`) appeared on main and disappeared on M2a. The same JFR pass produced a focused
> performance backlog: raw-source whitespace churn, comment-query indexing, worker-local
> formatter/parser reuse, stream-heavy method-chain analysis, startup separation, and discovery
> isolation. Two slices have since landed: the raw-source trailing-whitespace strip is single-pass
> (out of the top allocation tier; `normalizeWhitespace` is the residual raw-source target), and
> formatter/parser reuse ships as public sequential `FrmtrSession` reuse plus worker-local reuse in
> the file-oriented runner. Remaining work is measurement and the follow-ups that evidence justifies.

- **Serves:** "fastest" credibility, maintainer-friendly.
- **Effort:** medium. Methodology-heavy, low risk.

### M2. Linear-time renderer (width memoization + bounded `fits`)
**Status:** ✅ Done (archived) · _focused proposal:_ [archived/linear-time-doc-renderer.md](archived/linear-time-doc-renderer.md)

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
**Status:** 🟢 Implemented in `dc9bd4c`

M2 preserved the single width-authority contract but added runtime overhead on a real macro corpus.
`DocWidths.Measurement` now uses primitive sentinel returns instead of per-node `Budget` /
`MeasureResult` record traffic, fast-paths cached complete widths, and preserves the one-switch Doc
measurement contract. More complex overflow-bound caching remains a later option only if allocation
profiling proves it is worthwhile.

- **Measurement note:** paired macro timing did not show a reliable whole-CLI speedup. The change is
  best treated as an internal allocation and maintainability cleanup; the broader JFR-derived backlog
  is tracked in [performance-followups-from-jfr.md](performance-followups-from-jfr.md).
- **Serves:** fastest, maintainer-friendly.
- **Effort:** small-medium; must preserve byte-identical output and the one-switch Doc measurement
  contract.

### M3. Multi-file parallelism + content-addressed caching
**Status:** 🟢 In progress — runner-level bounded parallelism, CLI progress, and Gradle incremental/cache behavior landed; Gradle-native progress/logging remains · _focused proposal:_ [multi-file-parallelism-and-caching.md](multi-file-parallelism-and-caching.md)

> **Implementation state:** `FormatterRunner` now uses order-preserving bounded-pool parallelism
> for `check` and `write`. `frmtrJavaCheck` is cacheable through Gradle's native build cache via a
> deterministic success marker and uses Gradle incremental inputs so changed-source runs process
> only added/modified files. `frmtrJavaFormat` remains deliberately non-cacheable because it mutates
> source files in place and does not declare a synthetic marker output.
> `Frmtr.format` is **definitively thread-safe** (pure function of `(source, options)`; fresh
> `JavaFormatContext` per call, no shared mutable state), while `FrmtrSession` provides explicit
> sequential reuse and the file-oriented runner reuses sessions per worker. The CLI now renders
> progress on a separate side channel while preserving deterministic final result output. Persistent
> CLI caching stays out of scope per the lazy-ignore non-goal, and Gradle-native progress/logging
> remains a separate follow-up if needed.

The tooling runner now formats independent files on a bounded thread pool, and the Gradle check task
now leans on Gradle's input snapshots and build cache to avoid reprocessing unchanged files.
Remaining M3 work is limited to measurement/reporting discipline and any Gradle-native
progress/logging that proves necessary.

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

### S6. Atomic in-place writes for `--write` / `frmtrFormat`
**Status:** ✅ Done — landed on `main` · _focused proposal (archived):_ [archived/atomic-in-place-writes.md](archived/atomic-in-place-writes.md)

`--write`/`frmtrFormat` overwrote source in place via a non-atomic truncate-then-write, so an interrupted write could
leave a truncated or empty source file. **Now crash-safe:** `BestEffortAtomicFileWriter` writes a sibling `*.frmtr.tmp`,
copies the target's POSIX mode, and renames it over the original (`ATOMIC_MOVE`, falling back to a plain replacing move
where unsupported); `FormatterRunner.writeFile` routes through it. `FormatFileStatus.WRITTEN_PARTIALLY` was kept and now
means "replace failed, original intact."

- **Serves:** correctness / data-loss. Isolated to `frmtr-tooling`.

### S7. Comment guardrail split + output-level comment-drop net in CI
**Status:** ✅ Done — landed on `main` · _focused proposal (archived):_ [archived/comment-accounting-in-ci.md](archived/comment-accounting-in-ci.md) · _evidence:_ [archived/comment-handling-findings.md](archived/comment-handling-findings.md)

The single `debug.guardrails` toggle conflated an unreliable dup-claim fail-fast with the accounting checks; it was
**split** so the dup-claim invariant sits behind an off-by-default `FormatterGuardrails.STRICT_CLAIMS_PROPERTY`. The
durable "no comment dropped" gate is **not** `assertAllCommentsAccounted` (12 FP / 3 FN) but an output-level lexer
comment-token comparison: `CommentPresenceDiagnosticTest` now **asserts** over every golden fixture plus
collapsed/expanded perturbations, with the S9 backlog as a documented `KNOWN_DROPS` exclusion list.

- **Serves:** correctness, test discipline. Enables S9.

### S8. Opt-in `--verify` safety valve: refuse to overwrite non-equivalent output
**Status:** ✅ Done — landed on `main` · _focused proposal (archived):_ [archived/verify-on-write-safety-valve.md](archived/verify-on-write-safety-valve.md)

The AST-equivalence check existed only as a debug toggle whose failure was mislabeled as an internal crash. **Now an
opt-in valve:** `--write --verify` (CLI) → public `Frmtr.formatVerified` → `JavaFormatter.formatVerified` (seam `assertOutputEquivalentOrThrow`)
re-parses each file and refuses to overwrite (non-internal `"frmtr verify: …"` message, original left intact) when the
output is not AST-equivalent; recovered inputs are skipped. Reuses the `FAILED` status; the Gradle `verify` option was
deferred. Pairs with S6 so `--write` is fully safe.

- **Serves:** correctness / UX. Partially overlaps B3's surface.

### S9. Fix comment data loss
**Status:** ✅ Done — the ~42-case backlog is drained, each fix fixture-pinned · _focused proposal (archived):_ [archived/comment-data-loss.md](archived/comment-data-loss.md)

The S7 net surfaced ~42 `(fixture, shape)` real comment drops. **All are now fixed** — concrete preservation fixes
landed in `CompilationUnitPrinter` (file-orphan ordering), `SwitchPrinter` (orphan interleaving), and
`CommentedExpressionListPrinter` (`lineSuffix` routing), plus the shape-dependent ownership recoveries across the
control-condition/if, labeled-statement, try-resource, method-argument, switch, records/enums/conditionals, and
member/interface-body clusters, pinned by fixtures like `block-orphan-method-call-comments`.
The S7 net is green over the full corpus plus every collapsed/expanded perturbation and bites on any new or stale
entry, and `CommentPresenceDiagnosticTest.KNOWN_DROPS` is empty: every fixture and perturbation preserves all
comments, so no comment-drop backlog remains.

- **Serves:** correctness / data-loss. Iterative; folds into B1/B2.

---

## Suggested sequencing

The original B1→B2→B3 / S5→M2 spine is **done**: B1/B2 landed, the ranked-layout engine + reprint-by-default hub flip
retired the source-shape coupling and `LayoutWidth`, and B3 Layers 1–2 + M2/M2a shipped. What's left, roughly in
priority order:

1. **B3 Layer 3** — the real-world OSS corpus harness (the last correctness-net layer; the property test already runs
   over perturbed fixtures).
2. **M1** — a benchmark discipline (JMH + macro + competitive diff); the prerequisite for any "fastest" claim and for
   validating M2/M3.
3. **Printer-contract Stage 3 / LDM-4** — generalize the ranked output seam per construct (context→enum) so the
   remaining mega-printers decompose. Enablers all landed.
4. **M4** (LSP / editor integration) for adoption; the thin partial follow-ups (M3 Gradle progress, cli-discovery
   pruning, comment-containment caller migration); and the still-proposed smalls **S1** (display width), **S2**
   (`.editorconfig`), **S4** (pre-commit/Action/changed-hunks) — **S2 / S4** are quick user-facing wins.

The throughline held: **B1/B2 shrank the code and bug surface, B4 inverted the source-shape coupling behind a closed
exception set, B3 made those changes safe, and the remaining M-items turn a correct formatter into a fast, widely
adopted one.**

---

## Focused proposals in this directory

### Active (in this directory) — proposed / investigating / partial

| ID | Topic | Doc | Remaining work |
| --- | --- | --- | --- |
| B3 | Correctness safety net | [semantic-preservation-safety-net.md](semantic-preservation-safety-net.md) | Layer 3 (real-world corpus harness) |
| M1 | Performance follow-ups from JFR sampling | [performance-followups-from-jfr.md](performance-followups-from-jfr.md) | benchmark discipline (investigating) |
| M3 | Parallelism + caching | [multi-file-parallelism-and-caching.md](multi-file-parallelism-and-caching.md) | optional Gradle-native progress |
| M4 | LSP / editor integration | [lsp-editor-integration.md](lsp-editor-integration.md) | whole feature (unimplemented) |
| — | Printer-contract inversion (dissolve the callback mesh) | [printer-contract-inversion.md](printer-contract-inversion.md) | Stage 3 per-construct generalization |
| B2/B3 | Layout decision model (LDM-1…LDM-5) | [layout-decision-model.md](layout-decision-model.md) | LDM-4 context→enum (LDM-5 mostly moot) |
| — | Formatter-owned syntax view | [formatter-owned-syntax-view.md](formatter-owned-syntax-view.md) | held for architecture review |
| — | Comment containment index | [comment-containment-index.md](comment-containment-index.md) | bottom-up index + caller migration |
| — | Lazy `.gitignore` discovery | [cli-discovery-lazy-ignore.md](cli-discovery-lazy-ignore.md) | guarded ignored-directory pruning |
| S7/S9 | Comment-handling findings (evidence record) | [archived/comment-handling-findings.md](archived/comment-handling-findings.md) | — (reference) |

### Archived (implemented / superseded) — see [`archived/`](archived/)

| ID | Topic | Doc |
| --- | --- | --- |
| B1 | Centralize source-shape coupling | [archived/source-shape-policy-consolidation.md](archived/source-shape-policy-consolidation.md) |
| B2 | Enrich the Doc IR | [archived/doc-ir-combinators.md](archived/doc-ir-combinators.md) |
| B4 | Reprint by default: structural break rules + closed exception set | [archived/reprint-by-default-break-rules.md](archived/reprint-by-default-break-rules.md) |
| — | Layout hub: atomic-rewrite plan | [archived/hub-canonicalization-atomic-rewrite.md](archived/hub-canonicalization-atomic-rewrite.md) |
| — | Layout hub: D3 atomic-flip map | [archived/hub-canonicalization-d3-flip-map.md](archived/hub-canonicalization-d3-flip-map.md) |
| — | leftEdgePrefix true-column foundation | [archived/left-edge-prefix-foundation.md](archived/left-edge-prefix-foundation.md) |
| — | Convergence redesign (fan-out + opener-attachment ranking) | [archived/convergence-redesign.md](archived/convergence-redesign.md) |
| — | Chain-path unification | [archived/chain-path-unification.md](archived/chain-path-unification.md) |
| — | F2 / #190 object-creation-root column | [archived/f2-object-creation-root-column.md](archived/f2-object-creation-root-column.md) |
| M2 | Linear-time renderer | [archived/linear-time-doc-renderer.md](archived/linear-time-doc-renderer.md) |
| S6 | Atomic in-place writes | [archived/atomic-in-place-writes.md](archived/atomic-in-place-writes.md) |
| S7 | Comment guardrail split + drop net | [archived/comment-accounting-in-ci.md](archived/comment-accounting-in-ci.md) |
| S8 | `--verify` safety valve | [archived/verify-on-write-safety-valve.md](archived/verify-on-write-safety-valve.md) |
| S9 | Fix comment data loss | [archived/comment-data-loss.md](archived/comment-data-loss.md) |
| — | GraalVM native-image companion task | [archived/frmtr-native-image-companion-task.md](archived/frmtr-native-image-companion-task.md) |

> Items without a dedicated doc — the smalls **S1–S5** (self-contained) — are specified inline above.
> **S1**, **S2**, and **S4** are still 🔵 Proposed and can be picked up directly; **S3** and **S5** are ✅ done
> (S5 landed on `main` in `6e4f600a`). The audit-correctness smalls **S6–S9** each carry a dedicated doc, now moved to
> [`archived/`](archived/) (see the "Archived" table above) and preserved as provenance records of already-shipped work.
