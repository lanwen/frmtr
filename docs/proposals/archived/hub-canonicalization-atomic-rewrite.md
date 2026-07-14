> **Status: Implemented.** The D0–D4 plan landed on `main`: D0 harness (#268), D1/D2 substrate + residue gates (#269–#276), the D3 atomic flip (#279), D4 governance retirement (#290/#291/#293). The source-shape hub reads are gone and the `SourceShapeException` ratchet is terminal. Archived 2026-07-14; retained as a provenance record.

# Hub Canonicalization: A Non-Reverting Atomic Rewrite Plan

Status: ✅ Implemented — execution plan carried out (see banner). Consumes [layout-decision-model.md](../layout-decision-model.md)
(the ranking/measurement mechanism) and [reprint-by-default-break-rules.md](reprint-by-default-break-rules.md)
(the read-retirement governance). This doc owns *how to actually land* the method-call / chain /
object-creation / lambda **hub** canonicalization without the reverts that four incremental attempts hit.
The per-read consumer/replacement guide the D3 flip works from is
[hub-canonicalization-d3-flip-map.md](hub-canonicalization-d3-flip-map.md).

## Why the incremental approach reverts, and why this one won't

The reprint-by-default satellites (params, throws, control-condition, ternary, binary-operand,
try-resource) each landed as a small verified commit. The **hub** cannot: four corpus-proven attempts
(chain ×2, args ×2) all reverted, and the reason is structural, not tactical.

Two facts, both corpus-proven:

1. **A pure-AST formatter is idempotent by construction.** If the output is a pure function of the AST
   (no source-shape reads in doc construction), then `format(format(x)) = format(x)`, because formatting
   preserves the AST. The renderer already converges for pure-AST docs — the structural canonical-fan
   (`chainBreaksByRule`) proves it. So the *end state* (hub reads zero source-shape signals) is idempotent
   as a **theorem**.
2. **Every invalid intermediate state is invalid for one of exactly two reasons.** A width-driven level
   *under* a still-source-gated level **oscillates** (pass 1's output flips the outer source read); an
   approximate-column probe (`nodeLine`/fixed budget, which cannot see expression-continuation depth)
   **over-widths**. There is no valid partial retirement: the "subtree" whose reads must go together is the
   *entire hub*, and the C10 true-column measurement is **inert** until the reads are gone (with the reads
   intact, an accurate column produces byte-identical output).

Therefore the hub is a single **atomic** conversion to *pure-AST + true-column*. The revert risk in the
prior attempts came entirely from **validating against goldens and committing mid-conversion**, when every
partial state is either inert or oscillating. This plan removes partial states from the validation path:
it never gates on goldens until the whole hub is converted, and it uses the **idempotence invariant** —
checkable on any input, independent of goldens — as the continuous, real-time signal of *exactly when*
pure-AST has been achieved.

> **The guarantee:** we do not *hope* the rewrite converges. The end state is provably idempotent and
> provably over-width-free; the corpus invariants below tell us, continuously, when we have reached it.
> We commit only then. The residual challenge is **effort/completeness** (convert every one of the ~90 hub
> sites), never **correctness**.

## Failed attempts — what each proved

Five corpus-validated attempts (four hub reverts + one satellite reduction) established the constraints
above. Each passed the **fixture suite** and was caught only by the **corpus** — which is why corpus
validation is mandatory for hub work. They are recorded here so the rewrite does not re-discover them.

| # | Scope attempted | Corpus result | Why it reverted / what it proved |
| --- | --- | --- | --- |
| 1 | Retire the chain `sourceMultilineChain` backbone (`selectorBrokeAfter`), rewrite all ~11 `analysis.sourceMultilineChain()` reads width/structural. | `--verify` 0; **comment −8**; new non-idempotence; new over-width. | Fixture suite passed; corpus caught **three residues** at once: (A) inter-segment `//` comments on a below-fan-threshold type-like-root chain (`URLEncoder.encode(...).replaceAll(...)`) dropped when the fan collapsed; (B) below-threshold broken-final-arg chains oscillate fan⇄flat-head (the flag was a **convergence stabilizer** entangled with `methodCallArgs`); (C) wide-declaration-prefix object-creation-root chains collapse flat and over-width (need `#190` `leftEdgePrefix` in the initializer flat-gate). The flag does triple duty; retiring it alone is impossible. |
| 2 | Attempt 1 **+ fix residue A** (add an AST inter-segment-comment fan gate). | `--verify` 0; **comment 52104 == 52104 (neutral)**; idempotence **154 → 334**; 8 new over-width files. | Residue A **solved** — `chainHasInterSegmentLineComment` (root→first-selector / dot-gap / between-selector line comments) is comment-neutral on the full corpus and is the correct structural fix. But B and C remain: `chainBreaksByRule` (link-count) does **not** cover two chain classes the flag was the only thing fanning — **2-selector plain/field-receiver** chains and **lambda/cast-rooted** chains — so they collapse flat → oscillation + over-width. |
| 3 | Make arguments width-driven: rewrite `BreakableArgumentExpressionPrinter` from the `nodeLine`/fixed-budget probe to renderer-measured `Doc.conditionalGroup([flat, broken])`. | `--verify` 0; comment neutral (**fixed 2 pre-existing drops**); over-width **−39 files (bug fixed)**; idempotence **154 → 206 (+53)**. | The renderer-measured approach **fixes the over-width** (true column, C10) — but **oscillates**: a nested concat arg renders broken pass 1 → flat pass 2, because the arm choice depends on the enclosing group's flat-probe / `bestFitting` depth, which pass-1's own output changes. Confirmed for **both** `conditionalGroup` and `bestFitting`. This is the **bind**: `nodeLine`-probe = idempotent-but-over-width; renderer-measured = correct-width-but-oscillates. Neither works while the enclosing level is source-gated (`#191`). |
| 4 | Retire the arg-**list** source read (`MethodCallPrinter.sourceMultilineArguments`) **and** the arg-level together (the "whole method-call subtree" hypothesis). | `--verify` 0; comment neutral; idempotence **154 → 378**; +232 non-idempotent (57 **plain-call**, 175 chain/lambda). | The subtree hypothesis is right in direction but the boundary is **larger than `{arg-list + arg}`**: the plain-call path hides more source reads one level deeper — the single-arg **hug gates** (`hasSingleAttachable{MethodCall,ObjectCreation}Argument`, reading `methodCallArgs` on the *inner* call via fixed-budget probes) and the fan-chain-argument probes. **Retiring any strict subset re-oscillates at the next remaining read** ⇒ the atomic unit is the whole hub. |
| 5 | (Satellite, shipped then reduced.) The `args` + `object-creation` canonicalization commits landed in the milestone, then were reduced out of PR #266. | Whole-milestone corpus: **25 files gained new over-width** (deeply-nested `+`-concat args collapsing via the `nodeLine` probe). | Even "satellite" argument-collapse is **hub-class**: the `nodeLine` probe under-measures deeply-nested args (it counts block/type nesting, not expression-continuation depth), so retiring the arg source read over-widths. Confirms arg layout is C10-gated and belongs in the atomic hub, not the satellite slice. Parked on `wip/canonicalize-args-objcreation-c10gated`. |

The through-line: **the fixture suite is necessary but not sufficient for hub changes** — every one of these passed it. Idempotence, over-width, and comment parity must be measured on the corpus, and the atomic unit is the whole hub.

## The invariants that replace goldens during the build

Goldens encode the *old* shapes, so they go all-red the moment the rewrite starts — useless as a gate
mid-flight. Replace them with four golden-independent corpus invariants (kafka ~6033 files; camel as a
second corpus at milestones):

| Invariant | Check | When it must hold |
| --- | --- | --- |
| **AST-equivalence** | `--write --verify` → 0 "not equivalent" failures | throughout (meaning never changes) |
| **Comment parity** | corpus `//` token count == base | throughout (never drop a comment) |
| **Idempotence** | pass-1 vs pass-2 diff count, delta vs base == 0 | at completion (the *pure-AST* signal) |
| **Over-width** | `>120` file set ⊆ base | at completion (the *true-column* signal) |

Idempotence is the north star: while any hub source read remains live, idempotence delta is non-zero; when
it hits zero, the hub is pure-AST. A **source-read tripwire** (instrument each `RETIREMENT_TARGET`
`SourceShapePolicy` method to count hub call-sites hit per format) turns "idempotence is non-zero" into
"here is the exact read still firing — convert it."

## Phases

### Phase 0 — Instrumentation (the safety net)
- **Corpus harness**: one command formats a corpus with the working-tree CLI and reports all four invariants
  vs a cached base CLI. A ~500–1000-file representative subset for fast iteration (seconds–minutes); full
  kafka+camel for milestone/completion gates.
- **Source-read tripwire**: per-read hub call-site counters, dumped after a format, so a non-zero idempotence
  delta is traced to the specific remaining read in one step.

### Phase 1 — True-column measurement substrate (byte-identical, incrementally committed)
Convert every hub flat/broken **decision** from a fixed-budget / source-column probe to a **renderer-measured**
`Doc` (`group` / `conditionalGroup` / `bestFitting`), threading `leftEdgePrefix` / `trailingContent` so the
renderer sees the true column — including the assignment/return prefix, the trailing separator, and
**expression-continuation depth** (the args-over-width root cause the `nodeLine` probe misses).

Because the source reads **still gate** at this phase, output is **byte-identical** — so each conversion is
verified as *parity* (goldens unchanged, full suite green) and committed normally. This de-risks the entire
measurement half before it is ever activated: the scaffolding is built and tested while inert, and its
correctness *when activated* follows from the renderer's already-established true-column correctness.

Order, leaf → root, each a byte-identical commit: argument width → single-arg hug (`hasSingleAttachable*`)
→ object-creation args → chain-segment render → chain-root compact/fit → chain fan/attach → lambda-hug.

### Phase 2 — Structural residue gates (byte-identical, incrementally committed)
Every source read that does **double duty** (source-preservation *plus* a structural need) gets its
structural replacement added **alongside** the read, firing exactly where the read did → byte-identical.
This pre-builds every residue the flip must not lose:
- **Comment-safety**: the inter-segment-comment fan gate (`chainHasInterSegmentLineComment`, already
  prototyped and corpus-proven comment-neutral), plus comment-bearing arg/list gates.
- **The two fan classes `chainBreaksByRule` does not cover**: 2-selector plain/field-receiver chains, and
  lambda/cast-rooted chains — as structural or renderer-measured **fixpoint** fan rules.
- **The segment-lambda fan**: break a lambda body inside a broken chain segment by width (the
  `StreamsCoordinatorRecordHelpers` residual class).
Verify byte-identical per gate.

### Phase 3 — The atomic flip (one step, validated against invariants, not goldens)
With the substrate (Phase 1) and structural gates (Phase 2) in place and tested, **remove the source-read
gates** in the hub — all at once. The renderer-measured substrate + structural gates now drive everything →
pure-AST + true-column. Iterate **in-tree, uncommitted**, against the corpus invariants:
- **Idempotence delta → 0**: each non-zero hit pinpoints (via the tripwire) a read still firing; convert it.
- **Over-width ⊆ base**: each new over-width is a decision not yet renderer-measured; fix it Phase-1-style.
- **AST-equivalence 0** and **comment parity** hold throughout, or stop and fix.

When all four pass on the **full** corpus (kafka + camel), the hub is pure-AST. *Then* rebaseline all goldens
at once (via a `formatVerified` helper: rewrite only where idempotent + AST-equivalent), run the full suite
green, and commit.

Because Phases 1–2 already built and tested the substrate byte-identically, the flip is mechanically small —
it deletes `if (sourceRead) preserve; else <substrate>` gates, keeping the `<substrate>` branch — so its only
new behavior is "the preservation branch is gone," which the substrate + structural gates already cover.

### Phase 4 — Retire + govern
Delete the now-dead `SourceShapePolicy` read methods, drop their `SourceShapeException` `RETIREMENT_TARGET`
entries (count → 0), flip the governance ratchet to 0, and update `ARCHITECTURE.md`.

## Deliverables (how the phases split into landable work)

Phases 0, 1, 2, 4 split into independent, byte-identical (or tooling-only) deliverables that land as normal
verified PRs. Phase 3 is the **one** deliverable that cannot be split — it is the atomic flip. D1.* and D2.*
touch disjoint hub sub-areas and are parallelizable; each is verified as parity (goldens unchanged, full
suite green) so it carries **zero revert risk**.

| ID | Phase | Scope | Gate | Split? |
| --- | --- | --- | --- | --- |
| **D0** | 0 | Corpus harness (idempotence / `--verify` / comment-parity / over-width, base-vs-worktree, one command; subset + full modes) **+** source-read tripwire (per-`RETIREMENT_TARGET`-read hub call-site counters). | Tooling only, no formatter output change; harness reproduces the known base numbers. | tooling |
| **D1a** | 1 | Argument width → renderer-measured (`BreakableArgumentExpressionPrinter` `conditionalGroup`/`group`), threading `leftEdgePrefix`/`trailingContent` for the true continuation column. | byte-identical | ✓ |
| **D1b** | 1 | Single-arg **hug** gates (`hasSingleAttachable{MethodCall,ObjectCreation}Argument`) → renderer-measured (drop the fixed-budget inner-call probe). | byte-identical | ✓ |
| **D1c** | 1 | Object-creation argument list → renderer-measured group. | byte-identical | ✓ |
| **D1d** | 1 | Chain **segment** render → renderer-measured (segment open/hug/break at true column). | byte-identical | ✓ |
| **D1e** | 1 | Chain-**root** compact/fit (`compactRootLineWidth`) → true column; complete `#190` `leftEdgePrefix` threading into the statement/initializer chain flat-gates (retire the source-column floors). | byte-identical | ✓ |
| **D1f** | 1 | Chain **fan/attach** decision (`canAttachFirstSegmentToSimpleRoot`, fan shape) → renderer-measured `bestFitting` / structural, at the true column. | byte-identical | ✓ |
| **D1g** | 1 | Lambda-**hug** width → renderer-measured. | byte-identical | ✓ |
| **D2a** | 2 | Inter-segment-comment fan gate (`chainHasInterSegmentLineComment`) — **prototyped, corpus comment-neutral**. | byte-identical | ✓ |
| **D2b** | 2 | Structural **fixpoint** fan rule for 2-selector plain/field-receiver chains (a class `chainBreaksByRule` doesn't cover). | byte-identical | ✓ |
| **D2c** | 2 | Structural **fixpoint** fan rule for lambda/cast-rooted chains. | byte-identical | ✓ |
| **D2d** | 2 | Segment-lambda fan — break a lambda body inside a broken chain segment by width. | byte-identical | ✓ |
| **D2e** | 2 | Comment-bearing arg/list structural gates (the comment-safety residue of the arg/list reads). | byte-identical | ✓ |
| **D3** | 3 | **The atomic flip.** Remove every hub source-read gate at once; iterate in-tree against the corpus invariants until idempotence Δ = 0 and over-width ⊆ base; rebaseline all goldens; full suite green; commit. | idempotence Δ=0 · over-width⊆base · `--verify` 0 · comment parity · full suite green | **atomic — do not split** |
| **D4** | 4 | Delete dead `SourceShapePolicy` reads; `SourceShapeException` `RETIREMENT_TARGET` → 0; governance ratchet → 0; `ARCHITECTURE.md`. | full suite green | ✓ |

**Sequencing:** D0 first (it gates everything after). D1.* and D2.* in any order / in parallel (disjoint,
byte-identical). D3 only after **all** of D1 + D2 land — it is the point of no partial state, gated on the
corpus invariants, not goldens. D4 is mechanical cleanup after D3. The bulk of the effort (D1 + D2) is
safe, incremental, and reviewable; the single concentrated risk (D3) is de-risked by everything before it
already being built and tested.

## The specific hard sub-problems (must be solved inside Phases 1–2)
1. **True-column for deeply-nested arguments** — thread `leftEdgePrefix`/`trailingContent` through arg-lists
   so the renderer measures the continuation column (fixes the concat-arg over-width the `nodeLine` probe
   under-measures).
2. **Segment-lambda fan** — break a lambda body in a broken chain segment by width; needed for the
   lambda/cast-rooted chain class and the `StreamsCoordinatorRecordHelpers` residual.
3. **Fan convergence for the two uncovered classes** — 2-selector plain/field-receiver and lambda/cast-rooted
   chains need a **fixpoint** fan decision (structural, or renderer-measured `bestFitting` — never a
   fixed-budget probe, which is the oscillation source).
4. **Inter-segment-comment fan** — the structural gate that forces the comment-preserving fan for type-like
   roots carrying gap `//` comments (prototyped; comment-neutral on the corpus).

## Effort shape and sequencing discipline
- Phases 1–2 are **large but safe** — byte-identical, incrementally committed, zero revert risk. They are the
  bulk of the work and can be parallelized across the hub sub-areas (they don't interact until the flip).
- Phase 3 is a **concentrated, single-developer iteration** against the corpus invariants — not a fire-and-
  forget agent pass (that is what reverted four times).
- Never commit an oscillating or over-widthing state. The idempotence + over-width invariants are hard gates
  for the Phase-3 commit; AST-equivalence + comment parity are hard gates throughout.
