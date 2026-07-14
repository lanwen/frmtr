# Printer-Contract Inversion: Ranked Candidate Sets to Dissolve the Callback Mesh

Status: 🔵 Proposed — execution synthesis. **Consumes and sequences** three existing proposals rather than
competing with them: [layout-decision-model.md](layout-decision-model.md) (the "Context as data" seam analysis and
the ranked-broken-layout mechanism, LDM-1/LDM-3), [reprint-by-default-break-rules.md](reprint-by-default-break-rules.md)
(the named `BreakRule` authoring model, Stage 1), and [chain-path-unification.md](chain-path-unification.md) (routing
every chain host through one ranked engine). It is the plan that connects them into a single throughline and drives it
to the outcome the maintainer asked for: **small constructors, decomposable printers, and a first-class "default shape,
then diverge with full context" capability.**

This doc owns one claim the three parents state in pieces but never as one program: **the huge constructors, the
undecomposable mega-printers, and the barely-used `BreakRule` are the same defect — a printer's `format(node)` returns
exactly one `Doc` — and flipping that one contract dissolves all three at once.**

## Summary

Today a printer method returns the single shape it *chose*. Because Java constructs have several legitimate broken
shapes whose choice depends on width and surrounding context, "return one shape" forces the choice to happen at **build
time, imperatively, inside the printer** — and that single fact metastasizes into every symptom the maintainer named:

- **Huge constructors / the callback mesh.** Each alternative broken shape of a node is exposed as its own
  cross-printer callback (`brokenMethodCall`, `forcedMethodCallChain`, `packedMethodCallChain`, …) so a consumer can
  request the specific shape it pre-decided it wants. Every new layout feature adds another such callback, threaded
  through the composer to every consumer.
- **Imperative width cascades.** The shape is picked by pre-measuring flat width with a probe and walking an
  `Optional<Doc>` "build a candidate → measure → keep or fall to the next" ladder.
- **Comment-rollback complexity.** A discarded candidate must un-claim the comments it claimed while being built
  (`CommentTracker.speculatively`). *(Update: this specific pain point is resolved — `speculatively` has since been
  retired and every comment family now renders through the claim-neutral `CommentTracker.ownedComment` rail, so a
  discarded candidate commits no claim to roll back. See "The one remaining enabler" below.)*

Flip the contract: **a printer/rule emits a renderer-ranked *set* of candidate shapes (`Doc.bestFitting` /
`conditionalGroup`); `DocRenderer` picks by fit at the true output column.** The moment `format(node, ctx)` does this
for a node type:

- its N shape-callbacks collapse to **one** `format(node, ctx)` → the mesh dissolves and constructors shrink to
  *(context services, one nested-render entry, the construct's rule registry)*;
- its imperative width ladder collapses to *emitting the candidates* → the build-time/render-time ratio inverts;
- the per-construct **`BreakRuleRegistry`** becomes the natural home for "which candidates does this construct offer" —
  a terminal default rule plus higher-priority, AST-keyed divergence rules, first-match-wins. That *is* "describe a
  default shape, then diverge for specific cases with the full context of the surroundings," delivered by construction.

`ChainFanLayout` already proves the whole recipe for one construct (`fanShapeRules`: `chain-fan-selectors` as the
`candidate -> true` default, `factory-root-fold` / `single-selector` / `trivial-receiver-attach` as divergences, each a
pure function of a `ChainFanCandidate`, byte-identical extraction from a former `if` cascade). This proposal
generalizes that proven pattern from chains to every construct.

## Motivation (grounded on `feat/f2-bare-object-creation-body`)

The enablers this needs are already in place — the true-column measurement work (`LayoutContext.widthBudget` /
`LayoutWidth.LineBudget` retired, `SourceShapeException` governance ratchet landed, the chain printer split into
`ChainFanLayout`). What has **not** happened is the contract flip, so the mesh is intact and measurable:

| Symptom | Measure on the base |
| --- | --- |
| Callback mesh | `ExpressionPrinters` **133** `::` refs, `DeclarationPrinters` **124**, `StatementPrinters` **44** (~**301** hand-wired capabilities) |
| Constructor bloat | `FieldDeclarationPrinter` ~51-param ctor forwarding ~44 args straight into `VariableInitializerLayout` (~43 params); `ReturnExpressionPrinter` ~33; across the top-12, **cross-printer shape producers + source-shape predicates = ~52% of all constructor params**, and **61–80%** for every initializer/return/call printer |
| Imperative dominance | **519** `Optional<Doc>` candidates, **91** `LayoutWidth` probes, **57** `speculatively(...)` scopes vs. only **24** `bestFitting` + **15** `conditionalGroup` renderer-resolved decisions (~14:1) |
| Undecomposable printers | `MethodCallChainPrinter` 3291, `VariableInitializerLayout` 2925, `MethodCallPrinter` 1813, `ExpressionLambdaArgumentLayout` 1661 LOC |
| `BreakRule` underused | exactly **2** registries, both in `ChainFanLayout` (chains only) |

The "what grows" evidence is decisive: recent feature commits (`#256`, `#221`, `#190`, `U7`/`U8`, the canonical-fan
cutover) add *only* shape-producer and source-shape-predicate callbacks; the service, nested-render, width, and
compact-text buckets have not grown. **Constructor bloat scales 1:1 with formatting features** — the unsustainable
part — because there is no renderer-ranked output seam to absorb a new shape as one more candidate instead of one more
callback.

## Where the seams already are

frmtr has clean homes for two of the three concerns the LDM "Context as data" section names, and the third is now
unblocked:

- **Run-scoped services** → `JavaFormatContext` (its Javadoc rightly forbids becoming a service locator; this proposal
  does **not** change that — nothing is resolved by type).
- **Per-node positional context** → `LayoutContext`, threaded via `JavaFormatRule.format(node, ctx)`. Exists and is
  slimmed post-C10 (no width budgets). This is the **input seam**.
- **The ranked output** → `Doc.bestFitting(alternatives, int[] priorities)` (fit → priority → fewer-lines →
  less-overflow → earliest; bounded, deterministic, native-image safe) and `conditionalGroup`. Exists as a primitive,
  used at 39 sites, **not yet the default contract**. This is the **output seam**.

LDM's verdict, restated: *"`LayoutContext` is the input seam; ranked-broken-layout is the output seam; the
parameter-explosion constructor is the absence of both."* The input seam and true-column measurement have landed. This
proposal is the disciplined generalization of the **output seam**, plus the `BreakRule` registry as the authoring
surface that makes it decompose the printers.

## Proposal

### 1. The contract

Every construct that has more than one legitimate broken shape returns a renderer-ranked candidate set:

```java
// today: one node → the one Doc the printer already chose (imperatively, at build time)
Doc format(N node, LayoutContext ctx);

// after: the printer offers the candidates; the renderer picks by fit at the true column
//   format(node, ctx) internally resolves the construct's BreakRuleRegistry and returns
//   Doc.bestFitting([defaultShape, ...divergences], priorities)  — or a single Doc when only one shape is legal.
```

The renderer already measures fit at the live column, so the candidates are ranked where they actually render — no
fixed-column probe, no source-column floor. A construct with exactly one legal shape still returns one `Doc`; the
contract change is only that *choosing among broken shapes* is no longer the printer's job.

### 2. Per-construct `BreakRuleRegistry` as the authoring surface

Generalize the `ChainFanLayout` pattern. For each multi-shape construct, its candidates are the layouts of an ordered,
first-match-wins `BreakRuleRegistry<Candidate>`:

- The **terminal rule** (`candidate -> true`) is the **default shape**.
- **Earlier rules** are the **divergences**, keyed purely on AST facts + `LayoutContext` positional context (never on
  width, never on source shape — those stay `SourceShapeException`s).
- `format(node, ctx)` resolves the registry and hands the selected candidates to the renderer to rank by fit.

This is exactly "default shape, then diverge for specific cases with the full context of the surroundings": the default
is the terminal rule; divergence is a higher-priority rule that reads the AST and the `LayoutContext`; idempotence is
free because each candidate is a pure AST function ranked at the real column (the `chainFanOut` source-neutrality
lesson). It is **not** a service locator and **not** a runtime DSL — it is the closed combinator vocabulary
`reprint-by-default-break-rules.md` §5 already requires, now used everywhere instead of only for chains.

### 3. What collapses, mechanically

Once a node type returns a ranked set:

- The cross-printer **shape callbacks for that node disappear** from consumers' constructors — a consumer that wanted
  `brokenMethodCall` / `forcedMethodCallChain` / `packedMethodCallChain` now calls one `render(call, ctx.asX())` and
  lets the renderer rank. The `MethodCall`/chain hub alone produces ~16 such callbacks consumed across
  Return/Var/Field/Binary/Assignment/Statement.
- The **nested-render capability** — the generic `(expression, layout) -> expression(expression)` lambda, today a
  constructor parameter of ~25 classes and instantiated 9× in `ExpressionPrinters` alone — collapses to **one** injected
  dispatcher entry. (This is Step 0 below; it is independent of the ranked work and lands first.)
- The **`Optional<Doc>` ladder + `LayoutWidth` probe** for that node collapse into "emit the candidates." (The
  `speculatively` rollback itself has already collapsed repo-wide, independent of per-construct conversion — see "The
  one remaining enabler" below.)
- The **13 forward-reference `this::` bridges** that work around construction-order circular deps in `ExpressionPrinters`
  disappear, because a registry is pulled at format time, not wired as a blank-final field.

Printers become independently unit-testable: feed a node + a stub `LayoutContext`, assert the candidate `Doc`s — no
composer to stand up.

## The one remaining enabler

The output seam is blocked on comment-bearing subtrees by a single mechanism, and it is the critical path:

> `bestFitting`/`conditionalGroup` build **all** their arms eagerly, and building a `Doc` **claims** the comments in it
> (identity-based, first-builder-wins). So a ranked node whose arms both contain a comment double-claims it, and only
> one arm reaches output — the two passes diverge. Today this is why both big gates run a comment/source-shape *preempt
> tier* first and only hand the renderer the "residual, comment-free" verdict, and why there are **57 `speculatively`
> scopes**.

The fix is **claim-free candidate rendering**: comment ownership is already decided in a record-only pre-pass
(`CommentTracker.beginRecording` → `ownsHere`); finish moving *all* families there so the real render — and therefore
every eagerly-built candidate arm — is claim-neutral and idempotent. This is the same work
`reprint-by-default`/B2 track as `strict-claims`; this proposal reframes it as **the gating enabler for the whole
modularity program, not a comment-correctness side-quest.** Gate on `CommentPresenceDiagnosticTest` (the `--verify`/AST
net is blind to comment drops).

**Update (enabler landed).** Every comment family now renders through the claim-neutral `CommentTracker.ownedComment`
rail, `CommentTracker.speculatively` has been retired as redundant, and `strict-claims` is enabled by default in
`frmtr-core/build.gradle.kts`. The infrastructure this section called for now exists and is corpus-verified
byte-identical. What remains open is Migration plan Stages 2–3 below: converting the actual chain/construct call sites
to build both `bestFitting` arms for comment-bearing values instead of routing them through the imperative ladder —
see [chain-path-unification.md](chain-path-unification.md) for the chain-specific residual.

## Migration plan

Staged, byte-identical-first, corpus-gated (the proven recipe), each slice a separate PR for one-by-one merge on top of
`feat/f2-bare-object-creation-body`.

0. **Nested-render collapse (byte-identical, no enabler needed).** Replace the ~9× `(expression, layout) ->
   expression(expression)` lambdas and the expression-render capability threaded into ~25 classes with one injected
   dispatcher entry. Immediately deflates the widest constructors and proves the deflation is behavior-neutral. *(Step 0
   — the prototype.)*
1. **Claim-free candidate rendering (the enabler).** Finish comment-ownership consolidation so eager multi-arm building
   is claim-neutral; enable `strict-claims`. Output-neutral; unblocks ranked conversion on comment-bearing nodes.
2. **Chain/`MethodCallExpr` output seam (pilot).** `format(call, ctx)` returns `bestFitting([...])`; collapse the ~16
   chain shape-callbacks and the ladder into the generalized `ChainFanLayout` registries; consumers drop the callbacks.
   This is `chain-path-unification`'s destination, now reachable because the enablers landed.
3. **Per-construct generalization.** Repeat for `VariableInitializerLayout` (context→enum, LDM B5/B6), assignment RHS,
   ternary, binary operands, lambda body. Each mega-printer becomes a registry + small fixtured rules; each consumer
   sheds its callbacks. One corpus-gated PR per construct.

Stage 0 is safe now. Stages 2–3 sequence behind Stage 1 for comment-bearing constructs (comment-free ones can go
earlier).

## Risks and non-goals

- **Not a service locator.** `JavaFormatContext`/`LayoutContext` keep their documented boundaries; the registry is a
  curated behavioral vocabulary, not type-keyed collaborator resolution.
- **Not a new IR or a re-solve of ranking.** `bestFitting`/`conditionalGroup` already exist; this proposal makes them
  the default contract, it does not add primitives.
- **Not a big-bang rewrite.** Every slice is byte-identical-first and independently mergeable; the chain pilot is the
  only place a reviewed diff is expected, and only if a candidate genuinely ranks differently at the true column (a
  strict improvement, idempotent by construction).
- **"God registry" risk.** Mitigated exactly as `reprint-by-default` states: rules are per-construct, first-match-wins,
  individually fixtured; the registry only dispatches. A test asserts at most one rule fires per node on the corpus.
- **Reviewed-diff product tradeoff.** Where ranking at the true column disagrees with a retired imperative probe, it is
  the same deliberate call the canonical-fan cutover already made; surfaced per golden, not drifted into.

## Success metrics

- `ExpressionPrinters`/`DeclarationPrinters`/`StatementPrinters` `::`-ref count trends sharply down; no construct's
  constructor carries another construct's *shape* callback.
- The imperative:renderer-resolved ratio inverts for converted constructs. (`speculatively` scopes are already at 0 —
  claim-free rendering landed repo-wide; the still-open metric is the imperative:renderer-resolved ratio itself for
  each not-yet-converted construct.)
- `BreakRuleRegistry` count grows from 2 (chains only) to one per multi-shape construct; each rule has ≥1 golden
  fixture named for the decision it guards.
- The mega-printers measurably shrink into registries + small rule bodies.
- The tracked "oscillation"/"deferred" seams and the parked non-idempotent fixtures close as fixpoints — because a
  renderer-ranked, source-neutral candidate at the true column is idempotent by construction (the mechanism the landed
  `#191`/return/initializer `bestFitting` slices already demonstrate).

## Relationship to existing proposals

| This proposal | Existing | Relationship |
| --- | --- | --- |
| The contract flip = generalize LDM's output seam | [layout-decision-model.md](layout-decision-model.md) LDM-1/LDM-3 | Executes "Context as data" + ranked-broken-layout as one program; consumes, does not re-derive. |
| Per-construct `BreakRuleRegistry` default+diverge | [reprint-by-default-break-rules.md](reprint-by-default-break-rules.md) | Generalizes Stage 1 from chains to every construct; the registry is the authoring surface. |
| Chain pilot (Stage 2) | [chain-path-unification.md](chain-path-unification.md) | Its destination, reachable now that true-column/`widthBudget` retirement landed. |
| Ranking primitive + priority key | [convergence-redesign.md](convergence-redesign.md) | Consumes `bestFitting(list, int[])`; does not change it. |
| Claim-free candidate rendering | B2 `strict-claims` / [comment-handling-findings.md](comment-handling-findings.md) | Reframes it as the gating enabler for modularity, not only comment correctness. |
