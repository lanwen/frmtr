> **Status: Implemented.** Landed on `main`: the `BreakRule` model (#259/#260), the closed `SourceShapeException` set + ratchet (#261, terminal after #291/#293), the `MethodCallChainPrinter` split (#281), and the reprint-by-default read retirement (satellites #262–#266, hub atomic flip #279). `SourceShapePolicy.wasMultiline` is gone (grep = 0). Archived 2026-07-14; retained as a provenance record. The data-driven-path evolvability (§5) is a deliberate, unstarted future option, not pending work.

# Reprint by Default: Structural Break Rules, a Closed Source-Shape Exception Set, and Layout Provenance

Status: ✅ Implemented — architectural direction now realized (see banner). Successor to [B1](source-shape-policy-consolidation.md)
(landed) and sibling to [the layout decision model](../layout-decision-model.md). B1 centralized every
"respect the source shape here?" read into one `SourceShapePolicy`. This proposal takes the next step:
**flip the default from preserve-the-author's-shape to reprint-from-scratch**, keep only a *closed,
enumerated, justified* set of source-shape exceptions, and give the formatter a first-class way to
**express where code breaks with complex, context-dependent AST rules** — so the source reads being
retired have a structural replacement to land on. It also adds **layout provenance**: for any
decision, the ability to point at *why* — which structural rule fired, or which source-shape exception
was consulted.

**Explicitly out of scope:** AST-changing rewrites (e.g. converting an expression lambda `x -> e` into
a block lambda `x -> { return e; }`). Those are semantics-preserving *code transforms* in the
[`AstEquivalence` sanctioned-rewrite set](#relationship-to-the-sanctioned-rewrite-set), carry a
type-resolution safety requirement frmtr deliberately does not meet, and are not part of this work.
This proposal is only about **where existing code breaks**, never about changing what tokens exist.

## Summary

Two moves, one throughline.

1. **Governance — reprint by default, exceptions closed.** frmtr's historical posture is
   preserve-by-default: printers read the input's line layout (`SourceShapePolicy.wasMultiline` and
   friends, 37 call sites) and keep the author's breaks. That makes output a function of incidental
   input shape, which is the root of the non-one-pass-idempotence the corpus sweeps keep surfacing.
   Invert it: **the formatter reprints from scratch**, and the only permitted source-shape reads are an
   enumerated `SourceShapeException` set, each with a written idempotence justification and each
   guarded by a ratchet test so a new one cannot be added casually.

2. **Capability — a structural break-rule model.** Reprint-by-default is only *feasible* if the break
   decisions the source reads used to make can be re-expressed structurally. Today those decisions are
   hand-rolled imperatively and scattered across ~2000-line printers (`MethodCallChainPrinter` is the
   worst). Extract them into named, composable **`BreakRule`s**: a pure-AST predicate plus a
   source-neutral layout, ranked for fit at the true output column by the renderer (never
   width- or source-conditioned in the rule itself). This is what lets a maintainer say, declaratively,
   *"a method chain of four or more links rooted at a constructor, nested inside another chain, fans
   like this"* — a complex, context-dependent rule that is idempotent by construction.

The throughline: **every layout decision becomes a pure function of the AST**, either through a named
`BreakRule` or through a named, closed `SourceShapeException`, and **`--explain` can attribute every
decision to one of them.** The canonical-fan cutover (End-state A, #256) already proved the recipe for
one construct; this generalizes it into a model and a governance boundary.

## Motivation

Three problems, all observed in the codebase today.

- **Output depends on input shape.** `SourceShapePolicy.wasMultiline(node)` (37 call sites) is
  "the author broke this across lines, so keep it broken." Because the formatter overwrites the very
  line breaks it read, a second pass can read a *different* shape and decide differently. This is the
  documented source of the non-one-pass cases (`semantic-preservation-safety-net.md`: a `return`
  collapsed onto one line wraps flat first and needs a second pass). It is also where genuine
  oscillation bugs live (the imperative chain rule, #163, ping-ponged 432↔782 files before the
  structural cutover).

- **Break logic is imperative and scattered.** There is no single place that expresses "how does a
  chain break." `MethodCallChainPrinter` interleaves dozens of `root instanceof ObjectCreationExpr`,
  `calls.size() == 2`, `promotesFirstCall(root)` predicates with `Doc` emission across ~2000 lines.
  Adding a context-dependent rule means editing deep printer internals, and the rule's idempotence is
  re-argued from scratch each time. There is no reusable notion of "an AST break predicate."

- **No provenance.** When a line breaks a certain way, there is no way to ask *why*. Was it width? A
  source-shape read? A structural rule? `--explain` exists (`ExplainResult`, `DocExplainRenderer`) but
  it explains the *`Doc`*, not the *decision* that produced the `Doc`. A maintainer debugging an
  unexpected break, or a user wondering why their formatting "stuck," cannot see the cause.

## Where frmtr stands today (grounded)

The foundation this proposal needs is already in place — this is an extraction and inversion, not a
rewrite.

- **`SourceShapePolicy` (B1, ✅ landed).** One per-run home for source-shape reads: ~15 methods,
  ~110 call sites, funneled through named questions (`wasMultiline`, `hadBlankLineBetween`,
  `selectorBrokeAfter`, `tryResources`, …). The scattered `getRange().*.line` and
  `rawSource…contains("\n")` reads are already consolidated behind it. **This is the escape hatch the
  exception set will formalize — it exists; it is not yet *closed*.**
- **The canonical fan (End-state A, #256).** The first fully-structural break rule: one source-neutral
  `chainFanOut` `Doc` built from the AST, ranked against the compact alternative by `Doc.bestFitting` /
  `Doc.conditionalGroup` **at the true output column** (`DocRenderer` measures fit at the live
  `column`, not a fixed probe). Net idempotence-positive on kafka + camel. This is the recipe every
  `BreakRule` follows.
- **The ranking mechanism ([layout-decision-model.md](../layout-decision-model.md)).** That doc names the
  one gap: `ConditionalGroup` offers N flat candidates + one broken fallback chosen by flat fit and
  **cannot rank two *broken* layouts against each other**. The break-rule model sits *above* the
  ranking mechanism and depends on it for the hardest rules (those whose alternatives are all broken).
  This proposal does not re-solve ranking; it consumes whatever the decision-model doc lands.
- **The sanctioned-rewrite set (`AstEquivalence.canonicalizePresentation`).** frmtr already performs a
  *closed* set of five semantics-preserving AST rewrites (import order, redundant-paren add/remove,
  lambda-parameter parens, modifier order, stray-`;` drop), each canonicalized so verify does not
  false-positive. This is the exact governance shape — a closed, justified allowlist enforced by the
  safety net — that the source-shape exception set will mirror for *reads*.
- **No type resolution.** frmtr runs on the bare JavaParser AST; there is no `SymbolSolver`. This
  bounds what rules can decide (see non-goals) and is why AST-changing rewrites are out of scope.
- **The ratchet precedent (`strict-claims`).** The comment-ownership work already ships a guardrail
  that flips from off to a CI gate once an invariant holds. The exception-set ratchet reuses this
  pattern.

## Proposal

### 1. Reprint by default

State the inverted philosophy in `ARCHITECTURE.md` and the proposals: **frmtr reprints from scratch;
it does not preserve the author's line layout except through the enumerated `SourceShapeException`
set.** This is a documentation and governance change first — it does not, by itself, move output. What
moves output is the per-construct retirement of the fragile reads (stage 3+), each a corpus-gated
cutover like the chain fan.

### 2. A closed `SourceShapeException` set

Turn the open bag of `SourceShapePolicy` methods into a closed enumeration. Each source-shape read
associates with exactly one exception value carrying a **written idempotence justification** (why it is
a fixed point — because the output reproduces the signal, or normalizes it to a canonical form).

Classified from today's policy:

| Exception (permitted) | Backing method(s) | Why it is a fixpoint |
| --- | --- | --- |
| `DELIBERATE_BLANK_LINE` | `hadBlankLineBetween` (3) | collapses to ≤1 blank; re-reading yields the same answer |
| `TRY_RESOURCES_TRAILING_SEMICOLON` | `tryResources` (semicolon) | faithfully reproduced in output |
| `COMMENT_PRESENCE_GATE` | `hasContainedComments` | correctness gate, not aesthetics; comments preserved verbatim |
| `WIDTH_FIT` | `fitsOnOneLine` (9) | width-driven over stable compact text |

| Fragile read (retirement target) | Backing method(s) | Structural replacement |
| --- | --- | --- |
| author-broke-this-call | `wasMultiline` (**37**) | per-construct `BreakRule` |
| author-broke-the-args | `*ArgumentsSpanMultipleLines`, `callableParametersSpanMultipleLines` (~8) | argument/parameter `BreakRule`s |
| author-broke-the-chain | `selectorBrokeAfter` (3) | already superseded by the canonical fan — residual deletes |
| misc `wasMultiline`-flavored | `startsOnSameLine`, `sourceMultilineLogicalCondition`, `containsSourceMultilineMethodCallArgument`, `expressionLambda…` (~10) | construct-specific `BreakRule`s |

The permitted set stays; the fragile set is retired as its `BreakRule` replacement lands. End state:
`SourceShapePolicy` is the four permitted rows, all fixpoint-safe, and `wasMultiline` is gone.

**The ratchet.** Promote B1's proposed grep guard (`rawSource.*contains("\n")`,
`getRange().*.line` outside the policy → zero matches) into an automated test, and extend it to assert
the policy's method set is exactly the declared `SourceShapeException` set. Adding a read then requires
a new enum value + justification + review — the forcing function that makes reprint-by-default real.

### 3. The structural break-rule model

A `BreakRule` is a named, source-neutral layout decision for a construct:

```java
/**
 * A named, source-neutral rule for how one AST node breaks. Pure function of the AST: {@link #matches}
 * reads no source shape and no width, and {@link #layout} emits ONE shape whose flat-vs-broken fit is
 * ranked by the renderer at the true output column. Both properties make the rule idempotent by
 * construction. The name is used for provenance (--explain) and to key the rule's golden fixtures.
 */
sealed interface BreakRule<N extends Node> {
    String name();
    boolean matches(N node, RuleContext ctx);   // pure AST predicate — NO SourceShapePolicy, NO width
    Doc layout(N node, RuleContext ctx);         // one source-neutral Doc; arms ranked by the renderer
}
```

with **composable, reusable predicates** so complex rules read declaratively:

```java
// The worked example: "a >=4-link method chain rooted at a constructor, nested inside another chain."
BreakRule<LambdaExpr> nestedConstructorChainBody = rule("nested-lambda-constructor-chain-body")
    .when((lambda, ctx) -> lambda.getExpressionBody()
            .map(body -> ctx.chain(body)
                    .rootedAt(ObjectCreationExpr.class)
                    .hasAtLeastLinks(4))
            .orElse(false)
        && ctx.isArgumentOfEnclosingChain(lambda))
    .layout((lambda, ctx) -> ctx.fan(lambda.getExpressionBody().orElseThrow()));
```

Design constraints — all learned from the canonical-fan cutover, all load-bearing for idempotence:

- **Rules are pure AST functions.** `matches` may not call `SourceShapePolicy` and may not measure
  width. A rule that needs source shape is not a rule — it is a `SourceShapeException`, and must be
  declared as one. This is what keeps the model on the idempotent side of the line.
- **The rule picks the *shape family*; the renderer picks the *fit*.** `layout` emits one
  source-neutral `Doc`; flat-vs-broken is resolved by `bestFitting`/`conditionalGroup` at the live
  column. Rules never branch on "does it fit" themselves — that reintroduces the fixed-column-probe
  bug the fan cutover removed.
- **Rules are enumerable, ordered, and first-match-wins per construct.** A per-construct
  `BreakRuleRegistry` is consulted by the dispatcher; the first matching rule owns the layout. Ordering
  is explicit and tested (no ambient priority).
- **Predicates and layouts are a closed combinator vocabulary, not arbitrary code.** A rule's `matches`
  is composed from named predicate primitives (`rootedAt`, `hasAtLeastLinks`, `isArgumentOfEnclosingChain`,
  …) and its `layout` from named strategies (`fan`, `hugOpener`, `breakEach`, …) — not free-form Java
  lambdas or raw `Doc` construction. Expressiveness comes from *composing* the vocabulary, and the
  vocabulary is extended deliberately. This is what keeps rules auditable, keeps `layout` from ever
  dropping tokens, and — see [§5](#5-keeping-the-data-driven-path-open-evolvability) — keeps the
  data-driven surface reachable without a rewrite.
- **Every rule has a golden fixture** named for the decision it guards (per the `adopt-fixture`
  convention), and its idempotence is covered by the existing corpus property test.

This is deliberately **not** a runtime-pluggable DSL or a user config surface (see non-goals): it is an
internal authoring model. It generalizes what `MethodCallChainPrinter` already does implicitly into a
named, testable, provenance-carrying form — the same way `SourceShapePolicy` generalized the implicit
source reads.

### 4. Layout provenance

Extend `--explain` from "explain the `Doc`" to "explain the *decision*." For each node whose layout was
chosen by a rule or an exception, record `(node, decisionKind, name, reason)`:

- `BreakRule "nested-lambda-constructor-chain-body" fired at L42` — a structural decision, and
- `SourceShapeException DELIBERATE_BLANK_LINE consulted at L108` — the pointer the maintainer asked
  for: *this was formatted this way only because the source shape is like that.*

`DocExplainRenderer`/`ExplainResult` already carry the plumbing; this adds a decision tag to the
node-to-`Doc` mapping. The result: every break is attributable, source-dependent decisions are
greppable (which is also how you find the remaining ones to retire), and rule regressions like the
ternary `--explain` break (#197) become visible as decision-attribution diffs.

### 5. Keeping the data-driven path open (evolvability)

This proposal ships rules as **internal, compiled** Java (L0). It does not build a user-facing surface.
But a later evolution to **data-driven rules** — users declaring rules in a config file from a fixed
vocabulary (L2) — is a natural, low-cost extension *if and only if* the model above is built as a
closed combinator vocabulary from the start. The dividing line:

- **Reachable cheaply.** Because predicates are composed from named primitives and layouts from named
  strategies (design constraint above), a config surface is a *front-end*: a parser/validator that maps
  a declared rule onto the exact vocabulary the compiled rules already use. The registry, ranking,
  provenance, and the double-format idempotence check are all shared; only the parser is new. That is a
  serialization layer, not a re-architecture.
- **What would foreclose it.** Any rule that reaches for an arbitrary `matches` lambda or pokes raw
  JavaParser types cannot be serialized, degrading a future surface to "data rules plus a code
  fallback." So two things must be decided before rule #1, because they do not retrofit cheaply:
  1. **Is the vocabulary total** (every rule must be expressible in it) **or is a code escape hatch
     allowed?** A hatch is pragmatic but permanently downgrades the future surface.
  2. **Route predicate AST access through a formatter-owned view** (the held
     [`formatter-owned-syntax-view.md`](../formatter-owned-syntax-view.md)) so the eventual config language
     names frmtr's concepts, not JavaParser's version-locked types.

What evolvability does **not** buy: the idempotence *guarantee* intrinsically weakens at L2 —
user-composed rules can conflict and oscillate, so "proven once in CI" becomes "checked per-config"
(double-format-compare at config load, ideally plus a static conflict check across the declared set).
That is a property of letting users compose rules, not of how the evolution is staged. Native-image
stays intact at L2 (the engine is compiled; config is data); it only breaks at full plugins (L3), where
arbitrary runtime code is incompatible with the closed-world image.

## Idempotence and correctness discipline

Why the model stays a fixpoint, and how it is enforced:

- **Purity ⇒ idempotence.** A `BreakRule` whose `matches` is a pure AST predicate and whose `layout`
  is source-neutral produces a shape that satisfies the same predicate on the next pass (the fanned
  chain is still a 4-link constructor-rooted chain). The output is a fixed point of the rule by
  construction — the object-expansion property, but for structural rules.
- **Ranking at the true column, not a probe.** Fit is decided by the renderer at the live `column`
  (`DocRenderer`), which is where the fan cutover moved it. Rules never carry their own width branch.
- **Two closed allowlists + one open space.** Source-shape *reads* → closed `SourceShapeException`
  set (ratchet-guarded). AST *rewrites* → closed `AstEquivalence` sanctioned set (verify-guarded, and
  untouched by this proposal). AST-driven *layout* → open, unlimited `BreakRule`s (the encouraged
  default). A decision that is none of these is a bug the ratchet or verify catches.
- **Same gates as #256.** Every retirement is corpus-gated: `IdempotencePropertyTest`,
  `AstEquivalence` verify, `CommentPresenceDiagnosticTest`, and the kafka+camel idempotence sweep.
  `--verify` is blind to comment drops, so `CommentPresenceDiagnosticTest` remains the comment gate.

## Migration plan (all stages landed)

_Historical._ The staged rollout completed: Stage 0 `BreakRule`/`BreakRuleRegistry` + canonical-fan rule
(#259/#260); Stage 1 extraction of the implicit chain/argument rules (the `MethodCallChainPrinter` split,
#281); Stage 2 closed `SourceShapeException` set + ratchet + provenance (#261); Stage 3 fragile-read
retirement one construct at a time (satellites #262–#266, hub atomic flip #279, governance #291/#293).
`SourceShapePolicy.wasMultiline` is retired (grep = 0) and the ratchet is at its terminal state.

## Risks and non-goals

Non-goals:

- **No AST-changing rewrites.** Expression↔block lambda conversion and any other token-inserting
  transform are explicitly excluded. They belong to the `AstEquivalence` sanctioned set, need type
  resolution frmtr does not have (the `return`-vs-void decision is type-dependent for statement
  expressions such as method chains), and are a separate decision.
- **No user-facing rule surface is built now — but the data-driven path is kept open.** `BreakRule`s
  ship internal, compiled, and native-image-safe (L0). This proposal does not build config-selected
  variants (L1), a data-driven rule language (L2), or plugins (L3). It *does* deliberately keep L2
  reachable by authoring rules as a closed combinator vocabulary, so a later config front-end is a
  serialization layer rather than a rewrite (see [§5](#5-keeping-the-data-driven-path-open-evolvability)).
  Full plugins (L3) stay excluded on purpose: arbitrary runtime code is incompatible with the
  native-image closed world and makes idempotence unprovable. Config-selected variants (L1) are a small,
  compatible add-on that can land later using the existing option-variant fixtures if desired.
- **Not a re-solve of ranking.** The mechanism for ranking multiple broken layouts is
  [layout-decision-model.md](../layout-decision-model.md)'s job; this proposal consumes it.
- **Not a change to blank-line, comment, or raw-recovery behavior.**

Risks:

- **A "god registry."** The registry could accrete unrelated logic. Mitigation: rules are
  per-construct, first-match-wins, individually fixtured; the registry only dispatches.
- **Rule conflicts / ordering fragility.** Two rules matching one node. Mitigation: explicit ordering,
  a test that asserts at most one rule fires per node in the fixture corpus, and provenance to catch
  surprises.
- **Big-diff product tradeoff.** Reprint-by-default produces larger adoption diffs than
  preserve-by-default — the same tradeoff already chosen deliberately for chains (End-state A). This
  should be an explicit, global product decision before retiring construct #2, not drifted into.
- **Extraction masking behavior change.** A stage-0/1 extraction that is not byte-identical is a silent
  regression. Mitigation: the extraction stages assert byte-identical corpus output; any move is an
  explicit, reviewed decision.

## Success metrics

- `SourceShapePolicy.wasMultiline` call sites trend to **0**; the policy is exactly the declared
  `SourceShapeException` set (ratchet-enforced).
- Greps for `getRange().*.line` and `rawSource.*contains("\n")` layout reads outside the policy and the
  recovery helpers return zero — as an automated test, not a checklist.
- Every break decision in the fixture corpus is attributable via `--explain` to a named `BreakRule` or
  a named `SourceShapeException`.
- Each named rule has ≥1 golden fixture; the whole suite plus the kafka+camel idempotence sweep stays
  green with the ratchet on.
- `MethodCallChainPrinter` (and peers) measurably shrink as implicit break logic moves into named
  rules.

## Relationship to other proposals

- **[source-shape-policy-consolidation.md](source-shape-policy-consolidation.md) (B1, landed)** — this
  is its successor. B1 centralized the reads into `SourceShapePolicy`; this closes the set, flips the
  default, and adds provenance. B1's "What stays source-aware on purpose" section (features that "live"
  in the policy) is narrowed here to "the fixpoint-safe ones live; the fragile ones are retired."
- **[layout-decision-model.md](../layout-decision-model.md)** — the ranking substrate. That doc closes the
  "rank two broken layouts" gap; the break-rule model is the authoring layer above it. Explicit
  dependency for stage 3 constructs whose alternatives are all broken.
- **[doc-ir-combinators.md](doc-ir-combinators.md) (B2, landed)** — supplies `bestFitting`,
  `conditionalGroup`, `lineSuffix`, `breakParent`, `fill`, the primitives a `BreakRule`'s `layout`
  emits.
- **[semantic-preservation-safety-net.md](semantic-preservation-safety-net.md) (B3, landed)** — the
  verify + idempotence + corpus gates every retirement runs through.
- **[formatter-owned-syntax-view.md](../formatter-owned-syntax-view.md) (held)** — the broad metadata-owner
  vision; the provenance/decision-attribution here is a concrete consumer of it.

### Relationship to the sanctioned-rewrite set

`AstEquivalence.canonicalizePresentation` documents frmtr's *closed* set of five semantics-preserving
AST rewrites (imports, parens, lambda-param parens, modifiers, empty statements). This proposal does
**not** add to it. It is named here only to make the boundary explicit: this work changes *where code
breaks*, never *what tokens exist*. The two closed allowlists — source-shape *reads* and AST *rewrites*
— are the only two places the formatter is allowed to depend on, or change, anything beyond pure
AST-driven layout.
