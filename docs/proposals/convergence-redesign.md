# Convergence Redesign: source-neutral fan-out + opener-attachment ranking

Status: 🔵 Proposed — focused mechanism proposal. Depends on the ranked-layout foundation
([layout-decision-model.md](layout-decision-model.md) milestones LDM-1 → LDM-3, all landed) and extends it. This doc is
narrow: it defines the **two missing pieces** that keep the fan-out-vs-argument-break (and dot-split-vs-open-arg) chain
decisions from being made by the ranked engine idempotently, and sequences the items they unblock
(#191, #221, #220, #190).

> **Decision (maintainer, 2026-07-03): keep opener-attached.** The house-style question raised under "Mechanism 2"
> is resolved — the prettier-java/gjf argument-break / dotted shape stays; the fewer-lines collapse is **not** adopted.
> **Mechanism 1 (priority ranking) is therefore required** (it exists precisely to preserve the opener-attached shape
> against the fewer-lines collapse), and the slice order below is the plan of record.

## Why this doc exists

The layout-decision-model doc named ranking multiple *broken* layouts as "the one architectural gap." That gap is now
**closed at the IR level**: `Doc.bestFitting` exists (`Doc.java:238`), the renderer ranks its alternatives by
`DocWidths.LineCount.betterThan` (`DocWidths.java:441`) under a bounded, deterministic tie-break
(`DocWidths.java:126` `chooseBestFitting`, bounds `MAX_BEST_FITTING_ALTERNATIVES=8` / `MAX_BEST_FITTING_DEPTH=4` at
`DocWidths.java:27` / `:36`), and `MethodCallChainPrinter.rankedSingleSegmentChain`
(`MethodCallChainPrinter.java:1122`) + `rankedObjectRootSingleSegmentChain` (`:1187`) are live consumers.

So the engine is real. But a #191 (LDM-3) investigation found that **the engine cannot yet absorb the initializer /
return fan-out-vs-argument-break decision**, and the reason is documented verbatim in the code
(`VariableInitializerLayout.java:861-889`, and again in `ARCHITECTURE.md` lines 553-570). There are exactly two
structural blockers, and neither is a ranking-tie-break problem — the overflow gate that #223 added (a fitting layout
beats any overflowing one) is *necessary but not sufficient*:

- **Blocker 1 — the fan-out arm is source-shape-dependent, so the ranked alternative is not always constructible.**
  `variableWithForcedMethodCallChain` → `MethodCallChainPrinter.forcedMethodCallChain` →
  `methodCallChain(…, FORCED, …)` returns `Optional.empty()` for a *flat single-selector* call whose opener fits: a lone
  `ROOT.method(args)` has no `.selector` chain segment to fan out, so the FORCED path has nothing to break and bails
  (`MethodCallChainPrinter.java:430`, the `calls.size() == 1` method-root branch, only reached at all when the opener
  overflows or the source chain was multiline). A `bestFitting` built from that arm therefore *only fires for
  overflow/source-multiline inputs*. On a source-multiline input it picks the fewer-line collapse; the flat-source
  re-format reaches the deterministic argument-break path instead → **the layout oscillates** (the
  `field-init-typelike-root-idempotence` `seenProviders` entry). The overflow gate cannot fix this because both competing
  shapes *fit* — the gate is a no-op and line count decides, differently on the two passes.

- **Blocker 2 — the desired policy is opener-attachment, which is not line-count minimization.** Even given a
  source-neutral collapse arm, the policy the imperative code enforces is: *keep `ROOT.method(` on the assignment line
  whenever its opener fits, breaking only the argument list.* For any call whose whole form fits on one continuation
  line, the collapse (`NAME =` ⏎ `ROOT.method(whole);`) has **strictly fewer lines** than the argument-break
  (`NAME = ROOT.method(` ⏎ `whole` ⏎ `);`). A fit-then-fewest-lines `bestFitting` therefore picks the collapse and moves
  the golden. `rankedSingleSegmentChain` works precisely because *its* preferred alternative (compact-with-broken-final-
  segment) has **no more** lines than its fan-out; this arm's preferred alternative has **more**. `bestFitting` today
  cannot say "prefer the opener-attached shape even though it uses one more line."

The concrete golden this protects — `frmtr-core/src/test/resources/format/field-init-typelike-root-idempotence`:

```java
// seenProviders + collapsedProviders (opener FITS) → argument-break, opener attached:
private final Set<SomeReasonablyLongMarshallerProviderTypeNameHere> seenProviders = Collections.newSetFromMap(
    new WeakHashMap<>(4)
);
// qualifiedRootProviders (opener OVERFLOWS) → collapse, whole call on the continuation line:
private final java.util.Set<SomeReasonablyLongMarshallerProviderType> qualifiedRootProviders =
    java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>(4));
```

Both rows fit on some layout; the difference between them is *only* whether the opener `… = Collections.newSetFromMap(`
fits on the assignment line. That is the opener-attachment rule, and it is exactly the fact a fewest-lines ranker throws
away.

The redesign is therefore two pieces: **(1) a source-neutral fan-out builder** (kills Blocker 1 by making the ranked
alternative always exist), and **(2) an opener-attachment ranking primitive** (kills Blocker 2 by letting the ranker
prefer the opener-attached shape over a fewer-lines collapse when both fit). Both must remain a fixpoint under
`IdempotencePropertyTest`.

---

## Mechanism 1 — a source-neutral fan-out builder

### The shape frmtr already builds

The fan-out `Doc` is trivial to construct; the printers already do. The primitives:

- `chainContinuation(Doc)` = `indent(indent(concat(HARD_LINE, doc)))` (`MethodCallChainPrinter.java:1972`) — the
  fixed-break dotted continuation.
- `objectRootContinuation(Doc)` = `indent(concat(HARD_LINE, doc))` (`:2746`).
- `methodCallChainSegment(call, …)` renders `.selector(args)` for one segment.
- The fan-out alternative in the working rankers is literally
  `Doc.concat(rootDoc, chainContinuation(methodCallChainSegment(call, suffix)))`
  (`MethodCallChainPrinter.java:1154`, `:1221`).

So a fan-out is `root` + `HARD_LINE` + one-`.selector(...)`-per-line, all under continuation indent. Nothing about that
construction reads source shape. The *emptiness* is entirely a gating artifact of routing through the FORCED chain path,
which has an early-out for "single selector, opener fits, nothing to break."

### The builder

Add a package-private builder on `MethodCallChainPrinter` that constructs the fan-out **from the AST alone**, decoupled
from `forcedMethodCallChain`'s FORCED early-outs:

```java
/**
 * Builds the one-segment-per-line fan-out for a chain regardless of source shape or opener fit, so a ranked
 * alternative always exists (convergence-redesign Mechanism 1). Unlike forcedMethodCallChain, it never returns empty
 * for a flat single-selector call whose opener fits: a single selector fans onto its own dotted continuation line
 * (root ⏎ .selector(args)); a multi-segment chain fans one selector per line. It renders each segment through the
 * ordinary segment renderer so a segment whose args still overflow its continuation column breaks its own argument
 * group — the builder owns the dot-split skeleton, not the per-segment argument decision, which the renderer keeps.
 */
Doc chainFanOut(Expression root, List<MethodCallExpr> calls, MethodCallChainTail tail, LayoutContext layout);
```

Contract:

- **Always non-empty** for a chain with ≥1 selector (that is the whole point — Blocker 1). It never gates on
  `openerFits` or `sourceMultilineChain`.
- **Segments rendered through the existing `methodCallChainSegment` group**, so each segment stays flat when
  `.selector(args)` fits at the continuation column and opens its own argument list only when it genuinely overruns
  (`#221` Case B falls out of this — see Unblocks). The builder emits the dot-split skeleton; the renderer owns each
  segment's argument break.
- **Comment-neutral / renders each call exactly once.** It builds one `Doc`; it does not fork the call into two builds
  (that is the double-claim hazard the comment gate below avoids).
- **`leftEdgePrefix`-aware only through the segment renderer**, so the `refuseOpeningSingleSimpleReturnChainTail`
  refinement (`:1694`) — the return-chain compact-tail from #236/#190 — already composes: a single-simple-arg tail on the
  fan-out's dotted line stays compact, matching `objectRootSingleSegmentChain`'s existing behavior (`:2705`).

### Where it plugs in

The existing rankers construct their `fanOut` alternative inline (`:1154`, `:1221`). Slice 1 replaces those two inline
constructions with `chainFanOut(...)` — byte-identical, since it builds the same `Doc` — proving the builder before any
new caller depends on it. Then the initializer arm (Mechanism 2) can build
`bestFitting([argumentBreak, chainFanOut(...)])` with an arm that exists on *every* input, not only
overflow/source-multiline ones. This is what dissolves Blocker 1: the collapse/fan-out arm is now a pure function of the
AST, so both passes see the same two candidates.

> Note on terminology: the initializer's "collapse" (`NAME =` ⏎ `ROOT.method(whole);`) is the **single-selector fan-out**
> — root on the continuation line, no dot-split because there is only one selector. `chainFanOut` produces exactly that
> for `calls.size() == 1`, which is why the same builder serves both the multi-segment chain case and the initializer
> collapse case.

---

## Mechanism 2 — an opener-attachment ranking primitive

This is the load-bearing change. The initializer wants **argument-break (opener attached) preferred over the collapse
(fewer lines) when both fit**, and the collapse only when the opener overflows. That is a *preference that outranks line
count*, which `LineCount.betterThan` (`DocWidths.java:441`) cannot express: its keys are, in order, fits → fewer lines →
less overflow → earliest index.

### Options evaluated against `Doc` / `DocWidths`

**(a) Per-alternative preference weight/priority in `bestFitting` — RECOMMENDED.**
Give each alternative an integer *priority* and make the ranking key `fits → priority → fewer lines → less overflow →
earliest`. A higher-priority *fitting* alternative beats a lower-priority *fitting* one regardless of line count; among
equal priority the existing metric is unchanged; a non-fitting high-priority alternative still loses to a fitting one
(the overflow gate stays primary). The initializer emits
`bestFitting([ argumentBreak@priority1, collapseFanOut@priority0 ])`: when the argument-break opener fits, both fit,
priority breaks the tie for argument-break (opener attached, matches golden); when the opener overflows, argument-break
does not fit, the overflow gate drops it, and the collapse wins — exactly `qualifiedRootProviders`. No source read; the
"opener fits" question is answered by the renderer measuring the argument-break's *first line* at the real column, which
is C10-correct by construction.

**(b) A layout-kind tag the ranker respects.** A closed enum (`OPENER_ATTACHED`, `COLLAPSED`, …) the comparator orders.
Rejected as the primary mechanism: it hard-codes a global cross-construct ordering into the width authority, where
priority is local to one `bestFitting` and reusable. A tag is really priority with a fixed lookup table; priority is the
general form. (A tag could be sugar over priority later if a construct wants named arms — that is LDM-4's enum work, not
this mechanism.)

**(c) Palantir `BreakBehaviour` "prefer-broken-if-last-segment-fits."** The idea that a group can *prefer* its broken
form when a designated tail still fits. Conceptually this is what we want, but it is a *per-group* property in palantir's
model; frmtr's decision is *between two whole candidates*, which is `bestFitting`'s job. Priority expresses the same
"prefer this shape" intent at the node frmtr already ranks, without adding a second break-decision axis to `Group`.

**(d) dprint resolved-condition style.** Branch on a labeled point's realized column ("did the opener land past column
C?"). This is genuinely equivalent in outcome and is the right tool for the *source-shape-elsewhere* reads (LDM-5 / rule
B9). But for opener-attachment it is heavier: it needs a labeled group + a resolved-state condition + a re-eval cap,
where priority is a single comparator key over candidates the renderer already measures. Prefer priority here; keep
resolved-conditions for the "a region elsewhere ended up multiline" family.

### Exact `DocWidths` / `Doc` change (option a)

`Doc.BestFitting` today is `record BestFitting(List<Doc> alternatives)` (`Doc.java:293`). Introduce a parallel priority
vector, defaulting to all-equal so **every existing `bestFitting` call is byte-identical** (equal priority ⇒ the
comparator reduces to today's `betterThan`):

```java
// Doc.java
record BestFitting(List<Doc> alternatives, int[] priorities) implements Doc {
    public BestFitting {
        if (alternatives.isEmpty()) { throw ...; }
        alternatives = List.copyOf(alternatives);
        priorities = priorities.length == 0
            ? new int[alternatives.size()]              // default: all zero → today's behavior
            : priorities.clone();
        if (priorities.length != alternatives.size()) { throw ...; }
    }
}
static Doc bestFitting(List<Doc> alternatives) {                 // unchanged signature
    return new BestFitting(alternatives, new int[0]);
}
static Doc bestFitting(List<Doc> alternatives, int[] priorities) { // new, additive
    return new BestFitting(alternatives, priorities);
}
```

`DocWidths.chooseBestFitting` (`:126`) threads the priority of each candidate into the comparison; `LineCount.betterThan`
(`:441`) gains a *higher-priority-fitting-wins* key placed **immediately after the overflow gate**:

```java
// pseudo — the comparison chooseBestFitting applies, with `p` = this candidate's priority
// 1. overflow gate (unchanged): a fitting layout beats any overflowing one
// 2. NEW: if both fit and priorities differ, higher priority wins
// 3. fewer lines (unchanged)
// 4. less overflow (unchanged)
// 5. earliest index (unchanged, strict → deterministic)
```

Key placement matters and must be exactly this: **after** fit, **before** line count. After fit keeps the overflow gate
primary (a high-priority arm that overflows still loses — that is how the collapse wins for `qualifiedRootProviders`).
Before line count is what lets opener-attachment beat a fewer-lines collapse. Priority is compared *only among fitting
candidates*, so it never rescues an overflowing arm.

Determinism / linearity are preserved: priority is a static integer on the node, read once per candidate, inside the
existing bounded loop (`MAX_BEST_FITTING_ALTERNATIVES` / `MAX_BEST_FITTING_DEPTH` unchanged). The comparator stays a
total order with the earliest-index final tie-break, so the winner is still unique.

`DocExplainRenderer` (rule C-explain surface, `ARCHITECTURE.md:236`) must print the priority alongside the per-
alternative line count so `--explain` still shows *why* a higher-line alternative won (otherwise the explanation reports
a metric that disagrees with the choice — the exact invariant `measureLineCount` protects).

---

## Idempotence guarantee

The hard gate is `IdempotencePropertyTest` (`frmtr-core/src/test/java/dev/lanwen/frmtr/java/IdempotencePropertyTest.java`)
plus the whole-corpus fixture net: the chosen layout must re-format to itself. The design is a fixpoint by three
independent arguments:

1. **The two candidates are pure functions of AST + measured width, on every input.** Mechanism 1 removes the only
   source read that made the arm's *existence* input-dependent (Blocker 1): `chainFanOut` is built from the AST
   regardless of `openerFits`/`sourceMultilineChain`, and the argument-break arm already is. So pass 1 and pass 2 present
   the renderer with the *same two `Doc` candidates* — there is no "arm present on pass 1, absent on pass 2" asymmetry,
   which was the oscillation source (`ARCHITECTURE.md:557-562`).

2. **The ranking key reads only the rendered column, never source line breaks.** `chooseBestFitting` measures each
   candidate's line count/overflow at the live output column via `measureLineCount` (`DocWidths.java:164`), which is a
   side-effect-free mirror of the real render (the congruence invariant pinned at `DocWidths.java:145-159`). Priority is a
   static integer on the node. Neither input changes between passes for identical AST, so the winner is identical. This is
   the same reason the landed `rankedSingleSegmentChain` is idempotent; priority does not add a source-dependent term.

3. **The winner re-parses to the same AST-shape signal the ranker keyed on.** This is rule D13 (per-signal fixpoint).
   The opener-attachment decision keys on "does `NAME = ROOT.method(` fit at the rendered column?" — a width fact, not a
   source line break. When argument-break wins, its output *is* `NAME = ROOT.method(` on the assignment line, so pass 2
   measures the identical opener and picks argument-break again. When collapse wins (opener overflows), its output strands
   `= ` and the opener is *not* on the assignment line — but pass 2 re-measures the *argument-break candidate's* opener
   (which the renderer builds fresh from the AST, at the same rendered column) and still finds it overflows, so collapse
   wins again. The decision never consumes its own synthesized punctuation (rule D15).

**Why this specifically kills the break-after-`=` oscillation (Blocker 1).** The old failure was: source-multiline
input → `forcedMethodCallChain` non-empty → collapse chosen (fewer lines); re-format now flat-source →
`forcedMethodCallChain` *empty* → argument-break path. Two different code paths for two source shapes of the same AST.
Under the redesign there is one path: `bestFitting([argumentBreak@1, collapseFanOut@0])`, both arms always built, ranked
by (fit, priority, lines) at the rendered column. Source shape is not consulted, so both passes converge on the same
arm. The `seenProviders` (was oscillating) and `collapsedProviders` (already argument-break) rows now decide identically
by opener fit, and `qualifiedRootProviders` (opener overflows) decides collapse — reproducing today's golden **by
mechanism** rather than by the imperative gate.

**Comment safety.** The third historical blocker (`VariableInitializerLayout.java:884`) — both arms render the call, so
eager construction double-claims comments — is handled exactly as the landed rankers handle it (`ARCHITECTURE.md:442-445`):
the `bestFitting` emission is **gated on the initializer being comment-free**; comment-bearing initializers stay on the
imperative `speculatively` ladder whose first-builder-wins rollback owns the claim. This is not a new invariant; it is
the same gate `rankedSingleSegmentChain` (`:1133`) already enforces. The `chainFanOut` builder's "renders each call
exactly once" contract keeps a single-arm build claim-safe; the two-arm `bestFitting` is only emitted when there are no
comments to claim.

---

## What it unblocks + slice order

### Unblocks map

| Item | What it is | How the redesign closes it |
| --- | --- | --- |
| **#191** (LDM-3) — initializer/return fan-out-vs-argument-break convergence | The tracked deferral; `singleCallConvergesOnArgumentBreak` (`VariableInitializerLayout.java:1548`) + the imperative arm at `:890-906` stay hand-rolled | Mechanism 1 supplies the always-present collapse arm; Mechanism 2's priority expresses opener-attachment; the imperative arm collapses to `bestFitting([argumentBreak@1, chainFanOut@0])`. Golden `field-init-typelike-root-idempotence` reproduced by mechanism. |
| **#221 Case B** — "don't break the latest call if there's only a single arg; break by dots instead" | A chain's final `.selector(arg)` opens its single argument instead of fanning onto its own dotted line | `chainFanOut` renders each segment through the ordinary segment group, which keeps `.selector(arg)` flat on its dotted line when it fits and opens the arg only on genuine overflow — the same refinement `refuseOpeningSingleSimpleReturnChainTail` (`:1694`)/`objectRootSingleSegmentChain` (`:2705`) does for `return`, now generalized to every chain by making the fan-out builder source-neutral. |
| **#221 Case A** — lambda-body chain measured into the stay-flat gate | `.assertNext(result -> assertThat(x).extracting(f).containsOnly(y))` should dot-split the inner chain rather than open the outer call's args | Once the inner chain has a source-neutral `chainFanOut` alternative, the stay-flat gate can rank the packed-lambda-body chain (`packedExpressionLambdaBodyChain`, `:445`) against the dot-split fan-out via `bestFitting` instead of a fixed-column probe. Depends on Mechanism 1 + a stay-flat-gate `bestFitting` emission (later slice). |
| **#236 analogue (initializer)** — initializer dot-split / compact tail | #236 gave the **return** chain a compact dotted tail; the initializer still opens the arg | The same `chainFanOut` + segment-group behavior applies to the initializer arm once it routes through `bestFitting`; the initializer threads its own `leftEdgePrefix` (`NAME = `) so the compact-tail refinement composes. Falls out of #191's slice. |
| **#220 / #190** — retire `LayoutWidth` | The fixed-budget/source-column floors survive because the imperative arms still probe them (`openerLineWidth` `:1580`, `singleCallConvergesOnArgumentBreak`, the chain root gates) | Routing the initializer/chain arms through `bestFitting` (ranked at the rendered column) removes the *last consumers* of those probes. Once no arm reads `LayoutWidth.LineBudget`/`variableInitializer`/`nodeLine`, the transitional `LayoutContext.widthBudget` selector (`LayoutContext.java:23`) and the `LayoutWidth` methods delete — the C10 end state the LDM doc's slice 1 and #220 both name. |

### Slice order (smallest safe first)

Each slice is independently shippable behind the AST-equivalence + idempotence + comment-presence net.

1. **Priority-vector plumbing (byte-identical).** Add `BestFitting.priorities` (default all-zero), the additive
   `bestFitting(alternatives, priorities)` factory, the after-fit-before-lines comparator key in
   `LineCount.betterThan`/`chooseBestFitting`, and the `--explain` priority display. All existing `bestFitting` calls pass
   equal priority ⇒ corpus byte-identical. **Closes nothing; unblocks 2–4.** (Mechanism 2 foundation. Mirrors LDM-3a/b's
   additive-node discipline.)

2. **`chainFanOut` builder (byte-identical).** Add the source-neutral builder; replace the two inline `fanOut`
   constructions in `rankedSingleSegmentChain` (`:1154`) and `rankedObjectRootSingleSegmentChain` (`:1221`) with it.
   Because it builds the identical `Doc`, corpus is byte-identical. **Closes nothing; proves Mechanism 1 in isolation.**

3. **Initializer arm → ranked `bestFitting` (reviewed rebaseline, expected byte-identical).** Replace the imperative
   fan-out-vs-argument-break arm (`VariableInitializerLayout.java:890-906`) with
   `bestFitting([argumentBreak@1, chainFanOut(collapse)@0])`, gated comment-free + single-line-flat exactly like the
   master `conditionalGroup` at `ARCHITECTURE.md:542-553`. Retire `singleCallConvergesOnArgumentBreak` (`:1548`) and its
   helpers once no caller remains. **Closes #191**; expected byte-identical on `field-init-typelike-root-idempotence`
   (reproduced by mechanism) — any golden move is reviewed, not assumed.

4. **Generalize the dot-split / compact-tail to non-return chains (reviewed).** Drop the `leftEdgePrefix`-only scoping on
   `refuseOpeningSingleSimpleReturnChainTail` (`:1699`) / `objectRootSingleSegmentChain` (`:2705`) now that `chainFanOut`
   makes the fan-out available for statement/if/initializer chains too, so a single-simple-arg tail compacts everywhere.
   **Closes #221 Case B and the #236 initializer analogue.** Output-changing → reviewed rebaseline; each moved golden
   justified individually (B1 stage-2 discipline).

5. **Lambda-body chain into the stay-flat gate (reviewed).** Emit `bestFitting([packedLambdaBodyChain, dotSplitFanOut])`
   at the packed-lambda-body seam (`:445`). **Closes #221 Case A.** Largest behavior surface; do last.

6. **Retire `LayoutWidth` (cleanup).** With slices 3–5 landed, remove the now-dead `LayoutWidth.LineBudget` probes,
   `openerLineWidth`, and the transitional `LayoutContext.widthBudget`. **Closes #220 / the #190 C10 tail.** Byte-
   identical delete.

Slices 1–2 are pure foundation (byte-identical, parallelizable). Slice 3 is the payoff (#191). 4–6 ride on it.

---

## Risks / alternatives / scope boundary

**Risks / what could regress:**

- **Priority key placement is subtle.** If the priority key were placed *before* the overflow gate, a high-priority arm
  that overflows would win and produce over-width output — the exact defect #223's gate fixed. The key MUST be after fit,
  before lines, and compared only among fitting candidates. A test that a high-priority *overflowing* arm still loses to
  a fitting low-priority arm is mandatory (the `qualifiedRootProviders` collapse *is* that test).
- **Golden movement in slice 3.** The design argues `field-init-typelike-root-idempotence` is reproduced by mechanism,
  but this must be *verified*, not assumed — the master initializer `conditionalGroup` (#215) also touches this file, and
  the interaction of the flat-vs-broken outer `conditionalGroup` with the nested fan-out/argument-break `bestFitting`
  needs a fixture (mirroring the return case `ARCHITECTURE.md:452-454`, where the return `conditionalGroup` picks
  flat-vs-broken and the nested `bestFitting` ranks the two broken shapes). If the interaction is not byte-identical, the
  move is a *reviewed* rebaseline, not a silent one.
- **Comment double-claim.** Any slice that emits a two-arm `bestFitting` on a comment-bearing initializer/chain would
  trip the strict-claims guardrail. The comment-free gate is load-bearing and must be asserted per slice, not assumed
  from the landed rankers.
- **`bestFitting` bound interaction.** A nested `bestFitting` (initializer arm containing a chain that itself emits a
  ranked node) past `MAX_BEST_FITTING_DEPTH=4` (`DocWidths.java:36`) collapses to its first alternative unranked. For
  deeply nested chains the initializer must order its *own* first alternative as the safe default; a fixture at depth ≥4
  guards this.

**Explicitly out of scope:**

- The binary and source-multiline-object-creation return pre-empts (`ARCHITECTURE.md:455-457`) — they need the
  binary/object-creation printers to expose their own ranked candidates (LDM-4), not this mechanism.
- The general context→enum layout refactor of `VariableInitializerLayout` (rule B5/B6, LDM-4). This proposal migrates
  *one arm* through `bestFitting`; it does not turn the whole `Optional<Doc>` ladder into a layout enum.
- Resolved-state conditions for the remaining `selectorBrokeAfter`/`sourceMultilineChain` reads (rule B9, LDM-5). Those
  are the dprint-style branch-on-realized-column work, deliberately kept separate from opener-attachment.
- Comment-bearing chains/initializers stay on the imperative `speculatively` ladder — by design, indefinitely, until the
  comment-ownership pre-pass (B2 stage) lets both arms be built without double-claiming.

**Needs a product decision, not just a mechanism:**

- **Do we keep opener-attachment, or adopt the collapse?** The entire priority mechanism exists to *preserve* the
  current opener-attached golden (`seenProviders` stays argument-break). If the maintainer instead decides the collapse
  shape (`NAME =` ⏎ `ROOT.method(whole);`, fewer lines) is the preferred house style, then Mechanism 2 is unnecessary:
  a plain fewest-lines `bestFitting([collapse, argumentBreak])` suffices and slice 1 is dropped. This is the "deliberate
  golden move to the collapse" alternative the code note names (`VariableInitializerLayout.java:888`). The mechanism is
  built for the *preserve* choice; the *adopt* choice is cheaper but changes visible output across the corpus. **This is
  the single biggest open question and should be answered before slice 3.**

---

## Prior art

Same references as [layout-decision-model.md](layout-decision-model.md)'s prior-art table; the two mechanisms map to:

| Project | Relevance to this proposal |
| --- | --- |
| palantir-java-format | `BreakBehaviour`/`Obs` "prefer the broken form when a designated tail fits" is the conceptual sibling of the priority key — a *preference that outranks pure line count*. frmtr expresses it as a per-alternative priority on the node it already ranks (`bestFitting`) rather than a per-group break-behaviour, because frmtr's decision is between two whole candidates, not one group's internal break. |
| Biome | `AssignmentLikeLayout` enum arms (chain / break-after-operator / never-break / …) are the LDM-4 end state this proposal's slice 3 is a *step toward*: it migrates one arm of the initializer to the ranked engine without yet naming the full enum. |
| dprint | Resolved-state conditions (branch on a labeled point's realized column) are the rejected-here-but-right-elsewhere option (d): correct for opener-attachment in outcome, but heavier than a comparator key, and the right tool for the LDM-5 source-shape-elsewhere reads instead. |
| Prettier | The `conditionalGroup` "first flat layout that fits" is exactly the capability that is *insufficient* here (it cannot rank two broken shapes, and cannot prefer a more-broken one) — the reason `bestFitting` + priority exists. |

## Relationship to existing proposals

| This doc | Existing | Relationship |
| --- | --- | --- |
| Mechanism 2 (priority key) | [layout-decision-model.md](layout-decision-model.md) B8/D16 | Extends the landed `bestFitting` engine with the one key (`fits → priority → lines`) that the initializer arm needs and the fewest-lines-only metric lacks. Not a re-proposal of the engine. |
| Mechanism 1 (`chainFanOut`) | LDM-3 (#191), #221 | The source-neutral builder the #191 code note (`VariableInitializerLayout.java:867-874`) named as a prerequisite; generalizes the #236/#190 return-chain compact-tail (`refuseOpeningSingleSimpleReturnChainTail`) to all chains. |
| Slice 6 (`LayoutWidth` retirement) | #220, #190, LDM-2 (C10) | Provides the missing last-consumer removal the C10 tail (#220) is blocked on: the initializer/chain arms are the surviving `LayoutWidth.LineBudget` readers. |
| Out-of-scope (enum, B9) | LDM-4, LDM-5 | This proposal is the LDM-3-completing slice; the enum classifier (LDM-4) and resolved-state conditions (LDM-5) remain separate. |
