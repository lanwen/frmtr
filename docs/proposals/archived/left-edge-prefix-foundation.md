> **Status: Implemented.** The post-#279 true-column foundation landed on `main`: F1/F2 segment-column keystone (#276/#284), F3 floor-drop (#285), F5 comment-placement (#286), F7 `widthBudget`/`LineBudget` retirement (#287/#288), F8 satellites (#282). `LineBudget` grep = 0; both `KNOWN_NON_IDEMPOTENT` allowlists are empty. Archived 2026-07-14; retained as a provenance record. (The orthogonal comment × width residual — 2 tracked `KNOWN_DROPS` — is carried by the [printer-contract-inversion](../printer-contract-inversion.md) Phase-D comment work.)

# leftEdgePrefix Foundation: Completing True-Column Measurement Post-Flip

Status: ✅ Implemented — the F1–F8 slices landed (see banner); decisions ratified 2026-07-10 (§3). **Consumes** [chain-path-unification.md](chain-path-unification.md)
(the U1–U9 ranked-engine migration, the still-load-bearing floors inventory, and the A/B product decision),
[hub-canonicalization-atomic-rewrite.md](hub-canonicalization-atomic-rewrite.md) (the D0–D4 flip that shipped
as **#279**), [layout-decision-model.md](../layout-decision-model.md) (C10 true-column / LDM-2f), and
[convergence-redesign.md](convergence-redesign.md) (idempotence-by-construction).

This doc owns the **post-#279 delta only**: what the atomic flip deliberately left unfinished, the handful of
decisions that gate finishing it, and how the remaining work delegates. It does **not** re-derive the ranked-engine
mechanics or the U1–U9 slice bodies — those live in `chain-path-unification.md` and are cited by name below.

> **The reframing that matters for delegation:** the *scary* part — the atomic hub cut that reverted four times —
> is **done**. #279 deleted all six `RETIREMENT_TARGET` source-shape reads; the hub reprints by width; governance
> is at the terminal ratchet 0. What remains (this doc) is the true-column *measurement* half, which is
> byte-identical-first, per-caller, and corpus-gated — i.e. **safe and delegatable**, the exact opposite risk
> profile of the flip.

## 1. Where #279 left us

The flip removed the source-shape **reads** but not the source-column **floors** that the reads used to make moot.
Concretely, on the flip HEAD (`wip/d3-flip-assembly`, `b2bc7e34`):

- **Textual `leftEdgePrefix` is active for two callers only** — the return chain (`ReturnExpressionPrinter`,
  `withLeftEdgePrefix("return ")`) and the initializer chain (`VariableInitializerLayout`,
  `withLeftEdgePrefix(NAME + " = ")`), both consumed by `MethodCallChainPrinter.compactRootLineWidth`. Every other
  caller passes `LayoutContext.root()` and falls to the **empty-prefix branch = the `range.begin.column` source-column
  floor** (`compactRootLineWidth`; and `MethodCallPrinter.methodCallRootLineWidth`, `rootLineWidth`, `selectorLineWidth`
  read the prefix but receive only empty, so they are floor-only today). This is `chain-path-unification.md` Part 1
  "still-load-bearing floors," unchanged by the flip.
- **`LayoutContext.widthBudget` + `LayoutWidth.LineBudget`** (the fixed-per-depth crutch) are still the budget selector.
- **The segment/continuation true-column keystone (#190) is plumbed but inert** — `ExpressionLambdaArgumentLayout.plan`
  receives `columnWidth` and does not consult it (`MethodCallChainPrinter.singleCallLambdaBodyOpenerHug` measures the
  hug body at a fixed budget, not the real fanned segment column).
- **Residuals the flip tracked as follow-ups** (all rooted in the two gaps above):
  - the three foundation-gated PR review threads — `probe.withVirtualTime(...)` receiver-attach; the `)))` nested
    `forEach` closer; the `source-multiline-object-chain-initializer` inter-segment comment;
  - **7 allowlisted inline reads** in `InlineSourceLineReadGuardTest` (type-use annotations, `Type.class` literals,
    the object-creation-initializer chain-attach seam, chain-segment lambda hug/close);
  - **+5 bounded kafka over-width files** and **2 `KNOWN_NON_IDEMPOTENT` fixtures**
    (`lambda-expression-argument-opener`, `source-multiline-object-chain-initializer`).

**The renderer is not the problem.** `DocRenderer` already renders at the true column (the caller emits
`Doc.text("return ")` / `"NAME = "` before the node, so `Doc.Group.fits` and `bestFitting` are inherently
true-column-aware). `leftEdgePrefix` exists only because a few **build-time predicates** decide *structure* (fan vs
compact vs promote-root) by returning an `int` width, before the renderer runs, so they cannot see the live column.
Finishing the foundation = **per-caller prefix activation + converting those predicates to renderer-measured groups**,
never a central renderer change.

## 2. Two problems, four workstreams

Column-measurement and comment-placement are **distinct** problems that intersect at exactly one shape
(the object-creation-rooted chain initializer). Solving column-measurement removes the *width reshape* that triggers
the comment drop but does not, by itself, guarantee comment preservation.

| WS | Problem | Scope | = existing plan |
| --- | --- | --- | --- |
| **W1** | column-measurement, *textual prefix* | Activate `leftEdgePrefix` for the remaining callers (statement, argument, if/control, ternary, assignment RHS); make the sibling gates read it; drop the source-column floors; retire `LayoutWidth.LineBudget` + `widthBudget`. | `chain-path-unification.md` **U3, U5, U9** (post-flip: the reads are already gone, so these are now pure floor-retirement, not "alongside a live read"). |
| **W2** | column-measurement, *segment column* (**keystone, #190**) | Consume the inert `columnWidth` in `ExpressionLambdaArgumentLayout.plan` and `singleCallLambdaBodyOpenerHug`; establish the real fanned-segment continuation column. | `layout-decision-model.md` LDM-2f #190; `chain-path-unification.md` U7 "Realized" deferred slice + Part 5 risk 4 (nested-chain root at `root()`). |
| **W3** | comment-placement (orthogonal) | The fan re-render must *claim* inter-segment / trailing comments, or comment-bearing chains stay on the imperative comment-preserving cascade. | `comment-data-loss.md`, `comment-handling-findings.md`; gated by `CommentPresenceDiagnosticTest` (not `--verify`). |
| **W4** | column-measurement, *satellites* (independent files) | Thread a `LayoutContext` + a width-driven wrap algorithm into `TypePrinter` (type-use annotations) and `ClassExpressionPrinter` (`Type.class` literals); retire their allowlisted inline reads. | Not in the chain plan — genuinely separate; see **Decision D3**. |

**What each unblocks:** W1 retires the floors + `LayoutWidth` and hardens determinism for reindented input (the #137
family). **W2 is the highest-leverage single piece** — it unblocks both `KNOWN_NON_IDEMPOTENT` fixtures, the `)))` and
`withVirtualTime` review residuals, and the deeply-argument-nested `.filter(...)` over-width. W3 closes the last
comment-drop. W4 clears the two satellite over-width classes and 4–5 of the 7 allowlisted reads.

## 3. Decisions (ratified 2026-07-10)

All five resolved with the maintainer. D2/D4/D5 took the recommended option; **D3 was overridden** — build the full
type-renderer width model and delete the reads, do not merely govern them.

**D1 — Measurement mechanism (technical). Ratified: renderer-measured groups; keep `leftEdgePrefix` for fixed
textual prefixes only.** `chain-path-unification.md` already commits to this: build-time `int`-width predicates become
`Doc.bestFitting`/`conditionalGroup` ranked at the live column; the *segment/argument* column is continuation
**indent** applied by `Doc.indent`, **not** a threaded string (threading a string there is explicitly the wrong model
— see `MethodCallPrinter`/`BinaryExpressionPrinter` argument-seam comments). `leftEdgePrefix` stays only for genuine
same-line text (`return `, `NAME = `, `if (`). `widthBudget`/`LayoutWidth.LineBudget` are retired by W1/U9. *This is
already the house design; recorded here for completeness, no new decision needed.*

**D2 — Fan policy for the width-driven positions (PRODUCT — the load-bearing call).** This is
`chain-path-unification.md` Part 4's A/B decision, restated. End-state **A** = fan a multi-link chain by a
link-count/canonical rule *even when it fits* (prettier-java/gjf builder convention); rebaselines a large fraction of
the corpus. End-state **B** = fan only when the compact shape is over-width (pure width-arbiter; no rebaseline).
**Ratified: keep the shipped hybrid — B for the newly-routed width-driven arms, while preserving the existing
structural fan triggers** (`chainBreaksByRule` link-count, the D2b/c 2-selector/enclosed rules, the constructor-root
one-per-line convention already adopted). Rationale: #279 already shipped these structural triggers, so "B for the
residual" is the least-surprise, no-mass-rebaseline path and keeps the idempotence-by-construction story simplest.
Gates U4/U6/U7 (W1's behavioral tail, slice F6) and shapes W2.

**D3 — Type-renderer satellite scope (PRODUCT). Ratified (override): build the width model — fully retire the reads.**
`TypePrinter` (type-use annotations) and `ClassExpressionPrinter` (`Type.class` literals) get a real `LayoutContext`
threaded in **plus** a width-driven wrap algorithm for dotted class literals / type-argument annotations, so their
4–5 allowlisted inline `range.begin.line` reads are **deleted**, not governed. This is a **separate sub-project** from
the hub (disjoint files, no oscillation coupling), so it parallelizes freely — but it is a genuine build, not a
one-liner: no width-driven wrap exists for these shapes today and a naive PROBE→false adds +2 over-width each, so the
wrap algorithm must be designed and corpus-validated like any hub slice. Governed-`FIXPOINT_SAFE` was the safe
alternative; the maintainer chose full retirement so **no source-shape reads survive anywhere, on or off the hub**.

**D4 — Comment-placement policy (PRODUCT). Ratified: never-fan comment-bearing chains (keep them on the
comment-preserving cascade); pursue claim-the-comment only where the corpus later shows it costs a real shape.** The `dotBrokenObjectRootTailChain` case already *claims* a trailing comment; generalizing that is the
ambitious option, but "don't reshape a chain that carries inter-segment comments" is the safe invariant and is
already how `chainHasInterSegmentLineComment` behaves. Gate every change here on `CommentPresenceDiagnosticTest`.

**D5 — Done-bar / idempotence (PRODUCT). Ratified: 1-pass idempotence is required for the hub; the two parked
fixtures must reach it via W2 (that is W2's whole point) — no permanent 2-pass fixpoint in the hub.** Consistent with
the project's idempotence-not-convergence stance (`convergence-redesign.md`). The satellite reads under D3, being
`FIXPOINT_SAFE`, are already idempotent and are exempt.

## 4. Delegation plan

Post-flip, the atomic constraint is gone, so all four workstreams are independently landable and byte-identical-first
(except W2's keystone and the D2-policy tail, which are reviewed). Every slice gates on: full `./gradlew test` +
`scripts/idem-probe.sh`/`scripts/ow-probe.sh` (the reliable format-twice-and-diff / raw-`>120` probes, **not** the
in-harness corpus-check idempotence column, which under-reports) — idempotence ⊆ base, over-width ⊆ base, `--verify` 0,
`CommentPresenceDiagnosticTest` parity. Branch off the flip (`b2bc7e34`); land after #279 merges.

| Slice | WS | Order | Parallel? | Risk | Gate emphasis |
| --- | --- | --- | --- | --- | --- |
| **F1** consume the segment column in `singleCallLambdaBodyOpenerHug` (`)))` path) | W2 | **1st** | — | **low** (2 fixture families; plumbing already inert-present) | the two `)))`/`withVirtualTime` fixtures converge |
| **F2** full #190 segment-column: `ExpressionLambdaArgumentLayout.plan` consults `columnWidth`; nested-root fitting case | W2 | after F1 | — | **med–high** (design; the nested-root-at-`root()` gap, Part 5 risk 4) | both `KNOWN_NON_IDEMPOTENT` fixtures clear; `.filter(...)` over-width gone |
| **F3** activate `leftEdgePrefix` for statement + argument callers; sibling gates read it; drop those floors | W1 (U3) | after F2 | with F5 | med (reviewed; `source-multiline-method-root-chain-initializer` guard) | idempotence ⊆ base |
| **F4** if/control + ternary + assignment-RHS prefix activation | W1 (U5) | after F3 | with F5 | med | if/ternary preservation fixtures unmoved |
| **F5** comment-placement (D4) | W3 | any | ✓ (independent) | med | `CommentPresenceDiagnosticTest` |
| **F6** width-driven arms → `bestFitting` under the D2 policy | W1 (U4/U6/U7) | after F3/F4 **and D2** | — | **policy-dependent** | per-golden review; A/B canary |
| **F7** retire `LayoutWidth.LineBudget` + `widthBudget` | W1 (U9) | after F3/F4 | — | low (byte-identical delete) | empty diff |
| **F8** satellites → build the width model (per D3): thread `LayoutContext` + width-driven wrap into `TypePrinter`/`ClassExpressionPrinter`; delete the reads | W4 | any | ✓ (independent sub-project) | med (new plumbing + wrap algorithm; +2 over-width to erase) | `InlineSourceLineReadGuardTest` shrinks; over-width ⊆ base |

**Delegatability summary:** F1 is the safe first delegation (one agent, narrow). F2 is one strong agent, design-heavy,
**not splittable**. F3/F4/F5/F8 parallelize across agents (disjoint files, byte-identical-first). F6 waits on the D2
ratification. This is a much friendlier fan-out than the flip — no single point of no-return.

## 5. Status — all slices landed

All five decisions were resolved (§3) and every slice F1–F8 subsequently landed on `main` (F1/F2 #276/#284,
F3 #285, F5 #286, F7 #287/#288, F8 #282). The delegation sequencing below is retained only as a record of how the
work was staged.
