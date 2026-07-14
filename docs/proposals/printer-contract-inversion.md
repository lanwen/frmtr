# Printer-Contract Inversion: Ranked Candidate Sets to Dissolve the Callback Mesh

Status: 🟢 In progress — the enabler and pilot landed; per-construct generalization (Stage 3) remains. **Consumes and sequences** three existing proposals rather than
competing with them: [layout-decision-model.md](layout-decision-model.md) (the "Context as data" seam analysis and
the ranked-broken-layout mechanism, LDM-1/LDM-3), [reprint-by-default-break-rules.md](archived/reprint-by-default-break-rules.md)
(the named `BreakRule` authoring model, Stage 1), and [chain-path-unification.md](archived/chain-path-unification.md) (routing
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

## Already landed

- **Step 0 — nested-render collapse (#301).** The `(expression, layout) -> expression(expression)` capability threaded
  into ~25 classes was bundled into one injected dispatcher facade, deflating the widest constructors byte-identically.
- **The enabler — claim-free candidate rendering (Phases A–D, #324–#336).** Comment ownership now runs through a
  claim-neutral `ownedComment` rail (a record-only pre-pass + `ownsHere` filter); every family was migrated
  (#331/#333/#334), `CommentTracker.speculatively` was retired (#335), and `strict-claims` is a CI gate
  (`strict-claims=true` in `frmtr-core/build.gradle.kts`). Eagerly-built candidate arms are now claim-neutral, so
  ranked conversion of comment-bearing nodes is unblocked.
- **Chain pilot (partial, #336).** Comment-bearing method-call chains now rank via `bestFitting`.

The gating enabler that once blocked the whole program (comment double-claim on eager multi-arm building) is therefore
resolved. What remains is the mechanical generalization below.

## Remaining work — Stage 3: per-construct generalization

**Update (enabler landed).** Every comment family now renders through the claim-neutral `CommentTracker.ownedComment`
rail, `CommentTracker.speculatively` has been retired as redundant, and `strict-claims` is enabled by default in
`frmtr-core/build.gradle.kts`. The infrastructure this section called for now exists and is corpus-verified
byte-identical. What remains open is Migration plan Stages 2–3 below: converting the actual chain/construct call sites
to build both `bestFitting` arms for comment-bearing values instead of routing them through the imperative ladder —
see [chain-path-unification.md](chain-path-unification.md) for the chain-specific residual.

## Migration plan

- Turn each mega-printer's `Optional<Doc>` shape dispatch into a per-construct `BreakRuleRegistry` whose
  `format(node, ctx)` returns `bestFitting([...])`, so its cross-printer shape callbacks disappear from consumers'
  constructors and the imperative width ladder collapses into "emit the candidates."
- Targets, in order of leverage: `VariableInitializerLayout` (context→enum, LDM-4 / B5/B6), assignment RHS, ternary,
  binary operands, lambda body. Each becomes a registry + small fixtured rules; each consumer sheds its callbacks.
- Gate every slice on `CommentPresenceDiagnosticTest` (the `--verify`/AST net is blind to comment drops), plus the
  idempotence + AST-equivalence corpus net.

This is the same work [layout-decision-model.md](layout-decision-model.md) tracks as LDM-4 (context→enum). The
success metrics below (`::`-ref counts trending down, one `BreakRuleRegistry` per multi-shape construct, mega-printers
shrinking) are the acceptance bar for this remaining stage.

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
| Per-construct `BreakRuleRegistry` default+diverge | [reprint-by-default-break-rules.md](archived/reprint-by-default-break-rules.md) | Generalizes Stage 1 from chains to every construct; the registry is the authoring surface. |
| Chain pilot (Stage 2) | [chain-path-unification.md](archived/chain-path-unification.md) | Its destination, reachable now that true-column/`widthBudget` retirement landed. |
| Ranking primitive + priority key | [convergence-redesign.md](archived/convergence-redesign.md) | Consumes `bestFitting(list, int[])`; does not change it. |
| Claim-free candidate rendering | B2 `strict-claims` / [comment-handling-findings.md](comment-handling-findings.md) | Reframes it as the gating enabler for modularity, not only comment correctness. |
