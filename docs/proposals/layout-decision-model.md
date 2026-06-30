# A Layout Decision Model: Context-Dependent, Idempotent Block Formatting

Status: 🔵 Proposed — cross-cutting synthesis / decision model. This is **not** a re-proposal of
[B1](source-shape-policy-consolidation.md), [B2](doc-ir-combinators.md), or
[B3](semantic-preservation-safety-net.md) (all landed). It sits *above* them: it states the decision
model modern formatters converge on as a concise rule set, and it names the specific prior-art
mechanisms — not yet referenced in this directory — that close the one architectural gap B2
documented as **"not achievable"**: ranking multiple *broken* layouts by context, idempotently.

## Why this doc exists

The maintainer's framing: *"a complicated set of interconnected printers, which limit our flexibility
on defining how a specific code can be formatted idempotently but depending on a specific context."*

frmtr is **not** missing a document IR. Per the roadmap and focused proposals it already has:

- A Wadler/Lindig-style `Doc` IR + width-based `DocRenderer` ([README](README.md) "Where frmtr stands today").
- The four combinators printers used to fake — `LineSuffix`, `BreakParent`, `Fill`, `ConditionalGroup`
  — plus `Group`/`IfBreak` identity, all landed ([doc-ir-combinators.md](doc-ir-combinators.md) Outcomes).
- One consolidated `SourceShapePolicy` for every "respect the source shape here?" decision
  ([source-shape-policy-consolidation.md](source-shape-policy-consolidation.md)).
- An AST-equivalence verify mode + an idempotence property test over a perturbed corpus
  ([semantic-preservation-safety-net.md](semantic-preservation-safety-net.md), Layers 1–2).

So the foundation already matches the modern model. The pain is therefore **narrower and specific**,
and this doc's job is to name it precisely and route it to the mechanism that solves it — rather than
restate the roadmap.

**Research basis (read at source/paper level):** Oppen, *Prettyprinting* (TOPLAS 1980); Wadler,
*A prettier printer* (2003); Lindig, *Strictly Pretty* (2000); Prettier `doc-builders`
(`commands.md`); google-java-format (`Doc`/`OpsBuilder`/`Level`/`FillMode`);
palantir-java-format (`BreakBehaviour`/`Obs`/`State`); dprint-core (`print_items`/`printer`/condition
resolvers); Biome (`biome_formatter` IR + printer, `biome_js_formatter` `AssignmentLikeLayout`).

## The one architectural gap (what B2 could not collapse)

[doc-ir-combinators.md](doc-ir-combinators.md) Outcomes states it exactly. Collapsing
`MethodCallChainPrinter`'s `Optional<Doc>` dispatch (and the `LayoutWidth` probes feeding it) onto
`ConditionalGroup` is neither byte-identical nor expressible:

- **Not expressible.** The chain *ranks multiple broken layouts* and selects among them on
  structural/source predicates. `ConditionalGroup` is Prettier-shaped — **N flat candidates plus
  exactly one final broken fallback, chosen purely by flat fit, with no predicate gating. It cannot
  rank two broken layouts against each other.**
- **Not byte-identical.** The chain probes measure *flattened strings* at fixed-indent baselines and
  at **source columns** (`LayoutWidth`, `range.begin.column`), whereas the IR (`ConditionalGroup` /
  `DocWidths`) measures *flat fit at the actual output column*. The two measurements disagree, so a
  swap moves break points and changes goldens.

The same node-local-fit-vs-trailing-context limitation blocked migrating `ThrowsClausePrinter` too
(its probe measures the signature width *including the trailing `" {"`/`";"` opener* the caller emits
after the clause; an IR alternative is sized by its own flat width and cannot see that trailing
same-line content).

**This is the locus of the "interconnected printers."** The constructs that need to *rank broken
shapes by context* — method/builder chains, lambdas, assignment initializers, ternaries, `throws` —
are precisely the ones still hand-rolled with `Optional<Doc>` ladders, `LayoutWidth` string probes,
and `SourceShapePolicy` reads, because the current IR has no way to express "of these *broken*
shapes, choose the one that fits *this* context." Every liability the maintainer named
(scattered width arithmetic, candidate-rollback coupling, context-implicit-in-callbacks,
source-shape gating) is downstream of that one missing capability.

## Two philosophies — and where frmtr deliberately sits

The modern formatters get idempotency *for free* by **canonicalizing**: they discard the input's
line breaks entirely and re-derive every wrap from width, so the output is in the image of the
decision function and re-formatting is a fixed point. They converge to one canonical shape.

frmtr has made the **opposite, deliberate product choice**: preserve author intent — deliberately
multiline calls/constructors/ternaries/lambdas, deliberate blank lines
([source-shape-policy-consolidation.md](source-shape-policy-consolidation.md) "What stays
source-aware on purpose"). Its safety net therefore asserts **idempotence and AST-equivalence but
deliberately *not* convergence** ([semantic-preservation-safety-net.md](semantic-preservation-safety-net.md)).

This fork is the reason frmtr cannot simply adopt Biome/gjf wholesale, and it must be stated up front
because it changes the rules below:

> Because frmtr **chooses** to read a bounded set of source-shape signals, it cannot inherit
> idempotency for free. It must **engineer** it as an explicit invariant — *every preserved
> source-shape signal must be reproduced by frmtr's own output under re-parsing* — so that applying
> the decision function to the output is a no-op even though the output is not canonical. This is
> Biome's `case_clause.rs` invariant ("a layout decision is safe to base on a source signal only if
> the output preserves that signal under re-parse"), generalized into frmtr's design constraint.

Keep this distinction sharp: **idempotence ≠ convergence.** The rules pursue idempotence *while
preserving intent*, not canonicalization.

## The convergent mechanism

Stripped to one sentence, every modern formatter (Prettier, Biome, gjf, palantir, dprint) decides
"how a block is formatted" the same way:

> **Classify the context into an explicit layout enum; emit one IR shape per arm; defer width-driven
> flat-vs-broken to a single measuring engine. For a construct with several acceptable *broken*
> shapes, *rank* them — by line count or by resolved position — rather than only picking the first
> flat layout that fits.**

frmtr has the first two-thirds (IR + measuring renderer + `ConditionalGroup` for the flat-fallback
case). The missing third — *ranking broken shapes by context* — is the gap above.

---

## The rule set: how a block gets formatted

Four tiers. Each rule names the mechanism it comes from and frmtr's current status:
**[have]** landed · **[partial]** present but not adopted where it matters · **[gap]** not expressible today.

### A. Structure — what IR a block emits

- **A1 — Every breakable construct is a `group` of optional breaks; no printer emits a newline
  directly.** The group is the unit of the flat/broken decision. *(Oppen `BEGIN/END`; Wadler
  `group = flatten x <\|> x`; gjf `Level`.)* **[have]**
- **A2 — Forced breaks propagate to enclosing groups; never pre-measure to discover them.** A block
  body, a contained line comment, or a multi-line lambda forces its containers open. *(Prettier
  `propagateBreaks`; Biome `propagate_expand`; gjf `FORCED`.)* **[partial]** — frmtr has `BreakParent`,
  but with one consumer; propagation is single-pass/un-targeted (a `BreakParent` inside an
  already-flat-decided sibling won't retroactively break an ancestor — documented limitation in
  [doc-ir-combinators.md](doc-ir-combinators.md) Risks). A Prettier-style build-time upward
  propagation would make A1/A4 deterministic.
- **A3 — Consistent vs. inconsistent breaking is a declared knob per construct.** "One item per line
  if it breaks" (consistent group) vs. "pack as many per line as fit" (`Fill`). *(Oppen
  consistent/inconsistent; gjf `FillMode.UNIFIED|INDEPENDENT`; Prettier `group` vs `fill`.)*
  **[partial]** — `Fill` landed but its only consumer is `ThrowsClausePrinter`; arrays/arguments were
  not migrated and enum packing was deliberately declined (source-shape-preservation bias).

### B. Context — which layout, given where the block sits *(the centerpiece)*

- **B5 — Classify context into an explicit layout enum; emit one IR shape per arm. Do not vary
  layout via the call path.** This is the single most important rule and the direct antidote to the
  callback thicket. *(Biome `AssignmentLikeLayout` — 9 arms: `OnlyLeft`, `Fluid`, `BreakAfterOperator`,
  `NeverBreakAfterOperator`, `BreakLeftHandSide`, `Chain`, `ChainTail`, … each arm is one emit.)*
  **[gap, latent]** — `VariableInitializerLayout`'s `Optional<Doc>` ladder is the latent form of this;
  making it a named enum with one IR shape per arm is a concrete, mostly-mechanical first slice.
- **B6 — The enum's input signals must be pure functions of AST + measured width.** Allowed signals:
  RHS/operand node kind, child count, key length, "can the LHS itself break," "is the RHS a single
  huggable block," "is this a poorly-breakable chain." **That enumerated signal list IS the spec for
  the construct** — testable directly, not emergent from printer wiring. *(Biome
  `layout(is_left_short, left_may_break, …)`.)* **[gap]**
- **B7 — Express cross-construct coupling by naming the deciding group and reading its resolved mode.**
  Trailing comma iff the list broke; continuation-indent iff the operator broke; ternary `?`/`:`
  placement iff the condition broke. *(Prettier `ifBreak`/`indentIfBreak` + `groupModeMap`; gjf
  `BreakTag` + `Indent.If`.)* **[partial]** — `IfBreak` + `groupId` landed; `LineSuffix` already
  retired the trailing-comment/comma coupling. Extend the same id-keyed pattern to
  continuation-indent and ternary placement so those, too, stop reading source shape.
- **B8 — Rank multiple *broken* layouts by an explicit metric; this is the capability `ConditionalGroup`
  structurally lacks.** When a construct has several acceptable broken shapes (chain: all-flat /
  break-last-call / fan-out), generate the candidates and pick the one minimizing line count, gated by
  per-node intent. *(palantir `BreakBehaviour` on `OpenOp` + `Obs.maybeExplore`/`markAccepted`,
  ranking by `State.numLines()`.)* **[gap — the central one]** See "Prior art" below.
- **B9 — When context is "a region elsewhere ended up multiline / past column C," branch on resolved
  layout state, not on source shape.** Label a point in the IR; branch on its realized line/column;
  cap re-evaluation to guarantee termination. *(dprint `ConditionResolver` + `Info`/`resolved_*` +
  `InfiniteReevaluationProtector`.)* **[gap]** — this is the principled replacement for the
  `selectorBrokeAfter`/`sourceMultilineChain` source reads in the chain planner.

### C. Width — how fit is measured

- **C10 — Measure every group at its *rendered* start column, from the engine's running column —
  never the node's source column, never a fixed per-depth budget.** *(Lindig `fits(w − k, …)`; gjf/
  palantir thread `State.column`.)* **[split — the byte-identity blocker]** The IR path
  (`DocWidths`/`ConditionalGroup`) already measures at the output column *correctly*; the chain path
  (`LayoutWidth`, `range.begin.column`) measures at source-column/fixed-indent baselines, and the
  goldens are pinned to that. The two disagreeing is *why* B2's collapse wasn't byte-identical, and is
  the same family as the historically-tracked width-at-wrong-column non-idempotency. **Resolving this
  is a prerequisite for retiring `LayoutWidth`** (see Next Slices §1).
- **C11 — One tab model for both measurement and rendering.** A tab counts identically in `fits` and
  `write`. **[verify]** — guard with a fixture; this class has bitten before.
- **C12 — Trailing line comments are zero-width for measurement and break-forcing for layout.**
  *(Biome `line_suffix + expand_parent`; Prettier `lineSuffix`.)* **[have]** — `LineSuffix` landed with
  exactly this width semantics (contributes 0 to `fits`/`flatWidth`); keep it the canonical path and
  do not let any remaining inline-concat trailing-comment site widen a group.

### D. Idempotency-without-convergence — frmtr's engineered invariant

- **D13 — Every preserved source-shape signal must be a re-parse fixpoint.** This is frmtr's
  load-bearing rule (it replaces canonicalizers' "just re-derive from width"). For each signal
  `SourceShapePolicy` reads — `wasMultiline`, `hadBlankLineBetween`, `selectorBrokeAfter`,
  `argumentsWereMultiline` — there must be a proof/fixture that frmtr's own output reproduces that
  signal under re-parse, so the second pass decides identically. *(Generalized Biome `case_clause.rs`
  invariant.)* **[partial]** — the idempotence property test exists ([B3 Layer 2]); this rule asks for
  it to be asserted **per preserved signal**, with the signal's fixpoint as the explicit unit under
  test, not just whole-file idempotence.
- **D14 — Collapse, don't echo, count-like signals.** Blank lines read as a count but emit as 0-or-1;
  never reproduce N. *(Biome `get_lines_before_token`; gjf `BlankLineWanted` caps at one.)* **[have]** —
  `hadBlankLineBetween` consolidation already does this; keep it the only definition.
- **D15 — No feedback from synthesized punctuation; no fallback-to-original-source path.** A trailing
  comma is an *output* of the break decision, never an input. A valid parse always renders fully
  through the IR (frmtr has no rustfmt-style "rewrite failed → paste original snippet" path —
  preserve that). **[have]**
- **D16 — Any ranking/exploration engine needs a deterministic tie-break and a bounded
  exploration/re-eval cap.** Prefer fewer lines, then the flatter/earlier candidate. *(palantir caps
  `LAST_LEVELS_TO_EXPLORE`, tracks `branchingCoefficient`; dprint caps condition flip-flops.)*
  **[gap — lands with B8]** This is also the convergence-safety the existing renderer needs before
  ranked exploration can be trusted under the idempotence net.

---

## Prior art that closes the gap (new to this directory)

The existing proposals reference Prettier and the Wadler/Lindig lineage. The mechanisms that
specifically resolve B2's "not expressible" finding come from **three systems the directory does not
yet cite**:

### palantir-java-format — ranking broken layouts (the direct answer to B8)

palantir is a **fork of google-java-format** that keeps the same `Op → Doc → computeBreaks → write`
skeleton but replaces gjf's single hardcoded `Level` policy (the same one-policy limitation frmtr's
`Group` has) with:

- **Per-node layout intent declared on the open marker** (`OpenOp`): `BreakBehaviour` — a sum type
  with `breakThisLevel` / `preferBreakingLastInnerLevel` / `inlineSuffix` /
  `breakOnlyIfInnerLevelsThenFitOnOneLine` — plus `LastLevelBreakability`, `PartialInlineability`, and
  a `complexity()` "poison" flag. The visitor *declares* "this is a chain; prefer breaking the last
  call; don't partially inline if an ancestor was complex" as **data**, not as printer code.
- **A backtracking engine** (`Obs.explore`/`maybeExplore`/`markAccepted`) that genuinely produces
  several candidate layouts and **picks the one minimizing `State.numLines()`** — e.g.
  `preferBreakingLastInnerLevel` computes `breakNormally` *and* `tryBreakLastLevel` and keeps whichever
  has fewer lines. **This is exactly what `ConditionalGroup` cannot do** (rank two broken layouts), and
  it is what `MethodCallChainPrinter`'s `Optional<Doc>` ladder is hand-rolling.
- **Immutable threaded `State`** (`column`, `indent`, `numLines`, `branchingCoefficient`, persistent
  break maps). Where gjf *mutated* `Doc` fields, palantir threads state functionally — which is what
  makes "try a candidate, measure it, discard it" sound and rollback-free (directly retiring frmtr's
  `speculatively()` candidate-rollback coupling).

It is **Java**, in the same Oppen/`Doc` family frmtr already lives in, so the algorithm ports far more
directly than Biome's Rust. It is the reference implementation for B8 + D16.

### dprint-core — branching on resolved position (the answer to B9)

dprint's IR carries **`Condition`s with a resolver** that branches on *resolved layout state*: a
condition can ask "did the point I labeled earlier end up on a new line / past column C / at indent
L?" via `Info` markers + `resolved_line_number`/`resolved_column_number`, with the printer resolving
forward references by save-point rewind and an `InfiniteReevaluationProtector` capping flip-flops. A
Wadler/Lindig/gjf `group` can only ask "does my content fit *here*"; dprint generalizes to arbitrary
resolved positional state. This is the principled replacement for the chain planner's
`selectorBrokeAfter`/`sourceMultilineChain` *source* reads — the same question ("did this segment end
up broken?") answered from frmtr's **own resolved output** instead of the input, which is what makes
it idempotent under D13.

### Biome — the classify-into-enum discipline (the form for B5/B6)

Biome's `AssignmentLikeLayout` is the cleanest worked example of B5/B6: one decision function turns
explicit context signals into a 9-arm enum, and each arm emits one specific IR shape. It is the
template for refactoring `VariableInitializerLayout`'s ladder into a named enum.

---

## Recommended next slices (concrete, incremental, B3-gated)

Each is independently shippable, output-pinned by the existing idempotence/AST-equivalence net, and
attacks the gap without a big-bang rewrite.

1. **Unblock measurement parity (C10).** Make the chain/initializer path measure flat fit at the
   *output column* (via `DocWidths`) instead of `LayoutWidth`/source-column baselines, and
   verified-rebaseline the affected chain goldens under the idempotence net (every move individually
   justified, per B1's stage-2 discipline). This is the prerequisite that makes any IR-based ranking
   byte-comparable and lets `LayoutWidth` eventually retire.

2. **Introduce a ranked-broken-layout capability (B8 + D16).** The thing `ConditionalGroup` cannot be:
   a primitive/engine that emits several *broken* candidates and selects by a deterministic metric
   (line count, then flatter-first), with a bounded exploration cap. Model it on palantir's
   `BreakBehaviour` + `Obs` exploration over immutable state. **Pilot on `MethodCallChainPrinter`** —
   the construct B2 proved needs it — behind the verify net; success = the `Optional<Doc>` ladder and
   its `LayoutWidth` probes collapse onto it.

3. **Make `VariableInitializerLayout` an explicit layout enum (B5/B6).** Port the `AssignmentLikeLayout`
   shape: a `layout(...)` function from pure AST+width signals to a named enum, one IR emit per arm.
   Mostly mechanical, high readability win, and it turns the implicit context into a testable spec.

4. **Replace chain source-shape reads with resolved-state conditions (B9).** Where the chain planner
   reads `selectorBrokeAfter`/`sourceMultilineChain`, branch on frmtr's *own* resolved break state
   (dprint-style), with a re-eval cap. Keeps the "preserve a deliberately-broken chain" behavior while
   making it a fixpoint (D13) rather than an input echo.

5. **Assert the fixpoint property per preserved signal (D13).** Extend B3's idempotence test so each
   `SourceShapePolicy` signal has a fixture proving frmtr reproduces it under re-parse. This is what
   lets slices 1–4 touch the chain/initializer machinery "aggressively" without fearing a
   non-idempotent regression.

The throughline: frmtr's residual "interconnected printers" are the constructs that **rank broken
layouts by context**. Give the IR/engine that one capability (palantir-style), fix the measurement
baseline so it's byte-comparable, express the per-construct choice as a context→enum classifier
(Biome-style), and route the remaining context-from-elsewhere reads through resolved state
(dprint-style) under the per-signal fixpoint invariant — and the callback thicket, the candidate
rollback, the scattered width arithmetic, and the source-shape gating all collapse together, *without*
giving up frmtr's deliberate author-intent preservation.

## Context as data: `JavaFormatContext` + `LayoutContext` + `JavaFormatRule.format(node, ctx)`

The maintainer names the symptom — printer constructors with dozens of parameters:
`VariableInitializerLayout`'s constructor takes **~50** parameters (≈40 of them cross-printer
callbacks), and its construction site `FieldDeclarationPrinter` takes ~55. (`MethodCallChainPrinter`
and `MethodCallPrinter` already take `JavaFormatContext` and have done the service-dedup — so M1's
service work is mostly `VariableInitializerLayout`/`FieldDeclarationPrinter` plus a small shared
width-closure dedup.) Those parameters are **three different concerns** tangled into one
constructor, and frmtr already has clean homes for two of them:

| Concern | Home | Status |
| --- | --- | --- |
| Run-scoped **services** (options, comments, `sourceShapePolicy`, `rawSource`, `compactSource`, `layoutWidth`, …) | one shared `JavaFormatContext` | **exists** — its Javadoc already forbids it from being a printer/dispatcher service locator |
| Per-node-**type** formatting (render an `AssignExpr` vs a `LambdaExpr`) | `JavaFormatRule<T>` + `ExpressionDispatcher`/`BodyDeclarationDispatcher` | **exists** — the Biome `FormatRule<T>` shape |
| Per-node **positional context** (am I a return value? an initializer RHS? what is my left-edge prefix / column?) | an immutable `LayoutContext` value threaded down | **missing** — leaks into constructor params |

The missing third role is the disease, and the seam is one signature:

```java
@FunctionalInterface
interface JavaFormatRule<N extends Node> { Doc format(N node); }    // today: node in, ONE Doc out, no context
//                                         Doc format(N node, LayoutContext layout);   // proposed
```

`VariableInitializerLayout`'s ~50 fields decompose along the three concerns:

- **~10 re-passed services** — `comments`, `commentPlacement`, `sourceShapePolicy`, `rawSource`,
  `options`, `layoutWidth`, and the `compact*` wrappers (just `compactSource.*`). All already on
  `JavaFormatContext`; pure duplication → deleted by taking the context.
- **~6 context predicates** — `methodCallChainIsSourceMultiline`,
  `shouldBreakBeforeConditionalInitializer`, `methodCallChainRootIsObjectCreation`,
  `binaryExpressionHasLineComments`, `methodCallChainInitializerShape`, … These are pure
  node-functions, **not** positional, so they do **not** belong on `LayoutContext`; they collapse
  into the rule (which now holds `JavaFormatContext` services), computed directly rather than injected.
- **~33 cross-printer *shape* callbacks** — the tell: **seven** are for the single node type
  `MethodCallExpr` (`methodCall`, `brokenMethodCall`, `mixedFieldMethodCallChain`,
  `forcedMethodCallChain`, `packedMethodCallChain`, `methodCallWithSemicolon`,
  `methodCallChainFirstLine`), because each is a different *broken shape* of the same node and
  `format(call)` can only return one. The `BiFunction<MethodCallExpr, ToIntFunction<String>,
  Optional<Doc>>` variants even inject a `ToIntFunction<String>` width probe — a hand-rolled
  `LayoutContext.widthAt(...)` carrying the assignment prefix (the historical prefix-into-width lineage).

Adding the `LayoutContext` parameter collapses each bucket: services come from `JavaFormatContext`;
predicates fold into the rule; the shape callbacks collapse to a single `JavaFormatRule<MethodCallExpr>`
**once the rule can rank broken shapes (B8) and measure at the real column (C10)**. So:

> **`LayoutContext` is the *input* seam; ranked-broken-layout (B8) is the *output* seam. The
> parameter-explosion constructor is the absence of both.** A context value alone melts the service +
> predicate buckets (~16 params) and *enables* the shape-callback collapse, but the bulk only melts
> when B8 + C10 land.

**Discipline (so `LayoutContext` does not become the next god-object):**

- An **immutable value, passed as a parameter — never a field, never mutated.** Descent derives copies
  (`ctx.inInitializerRhs(prefix)`, `ctx.asArgument()`, `ctx.indented()`). This immutability is also what
  retires `CommentTracker.speculatively()` candidate-rollback: trying a layout is calling the rule with a
  derived `ctx` and discarding the result — no shared mutation to undo.
- **Positional facts only — ~4–6 fields, bounded by design:** enclosing-construct kind (enum),
  continuation/indent intent, the left-edge prefix contribution, a "trailing same-line content follows"
  flag (the `throws … {` opener problem). Nothing else.
- **Distinct from `JavaFormatContext`** (run services) and the dispatcher (per-type formatting) — three
  roles, three homes, which is the boundary `JavaFormatContext`'s own Javadoc already insists on.
- **No width budgets in the end state.** Post-C10 the column comes from the renderer; `LayoutContext`
  carries structure, not the five `LayoutWidth` budgets (a budget selector is a transitional crutch
  removed when C10 lands).
- **Native-image safe:** typed `JavaFormatRule<T>` dispatch (already true) + a record context, no reflection.

## Milestones & execution plan

Each milestone is gated on [B3](semantic-preservation-safety-net.md)'s AST-equivalence + idempotence
net; every step lands behind `--verify`. LDM-1 is mechanical, parallelizable, and byte-identical — it
is the first to orchestrate. (IDs are namespaced **LDM-N** to avoid collision with the roadmap's
unrelated M1–M4 in [README.md](README.md).)

| # | Milestone | Scope | Output | Depends on |
| --- | --- | --- | --- | --- |
| **LDM-1** | **Context as data** | Service-dedup the bloated printers onto `JavaFormatContext`; introduce immutable `LayoutContext`; thread it via `JavaFormatRule.format(node, layout)` + the dispatchers; move context predicates onto it (transitionally carrying the width-budget selector). | byte-identical | — |
| **LDM-2** | **Measurement parity (C10)** | Chain/initializer width measured at the real output column (`DocWidths`) instead of `LayoutWidth`/source-column baselines; verified-rebaseline affected goldens. | rebaseline (reviewed) | LDM-1 |
| **LDM-3** | **Ranked broken layouts (B8 + D16)** | A primitive/engine that ranks broken candidates (palantir `BreakBehaviour`/`Obs` over immutable state) with a deterministic tie-break + bounded exploration; pilot on `MethodCallChainPrinter`, collapsing its `Optional<Doc>` ladder + per-shape callbacks. | possible diffs (reviewed) | LDM-1, LDM-2 |
| **LDM-4** | **Context→enum layouts (B5/B6)** | `VariableInitializerLayout` (then assignment/ternary) refactored into explicit layout enums driven by `LayoutContext` + measured width. | byte-identical → reviewed | LDM-1, LDM-3 |
| **LDM-5** | **Resolved-state + per-signal fixpoint (B9 + D13)** | Replace chain source-shape reads with resolved-state conditions; add per-`SourceShapePolicy`-signal fixpoint assertions to the idempotence net. | reviewed | LDM-3 |

The throughline holds: LDM-1 makes the *input* explicit (where am I), LDM-3 makes the *output*
explicit (which broken shape), LDM-2 makes them byte-comparable, LDM-4 expresses the per-construct
choice as a classifier, LDM-5 keeps source-shape preservation idempotent. Together they retire the
callback thicket, the candidate rollback, the scattered width arithmetic, and the source-shape
gating — without giving up frmtr's deliberate author-intent preservation.

## Relationship to existing proposals

| This doc's rule / slice | Existing proposal | Relationship |
| --- | --- | --- |
| A1–A3, C12 (IR vocabulary) | [doc-ir-combinators.md](doc-ir-combinators.md) (B2) | **Landed.** This doc builds on the primitives; does not re-propose them. |
| B8/D16 (rank broken layouts) | [doc-ir-combinators.md](doc-ir-combinators.md) Outcomes | **The gap B2 documented as "not achievable" with `ConditionalGroup`.** This doc supplies the palantir mechanism that resolves it. |
| C10 (measure at output column) | [doc-ir-combinators.md](doc-ir-combinators.md) (byte-identity finding); [linear-time-doc-renderer.md](linear-time-doc-renderer.md) (M2) | The source-column-vs-output-column split is B2's byte-identity blocker; M2's bounded `fits` is the renderer surface any ranking engine must stay linear within. |
| B7 (id-keyed conditionals) | [doc-ir-combinators.md](doc-ir-combinators.md) | `IfBreak`+`groupId` and `LineSuffix` landed; extend to continuation-indent / ternary placement. |
| D13–D15 (idempotence ≠ convergence) | [semantic-preservation-safety-net.md](semantic-preservation-safety-net.md) (B3); [source-shape-policy-consolidation.md](source-shape-policy-consolidation.md) (B1) | B1 consolidated the reads; B3 asserts idempotence (not convergence). This doc adds the **per-signal fixpoint** invariant as the discipline that keeps preservation idempotent. |
| B5/B6 (context→enum) | — | New. Closest existing surface is `VariableInitializerLayout`; no proposal frames it as an explicit layout enum yet. |
| B9 (resolved-state conditions) | [source-shape-policy-consolidation.md](source-shape-policy-consolidation.md) | The principled replacement for the chain planner's `selectorBrokeAfter`/`sourceMultilineChain` source reads. |
| formatter-owned context object | [formatter-owned-syntax-view.md](formatter-owned-syntax-view.md) | A `LayoutContext` carrying rendered column + enclosing-construct kind (replacing callback-implicit context) is a natural facet of that held proposal. |
