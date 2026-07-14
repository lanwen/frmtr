> **Status: Implemented (consumed).** This per-read execution map was consumed by the D3 atomic flip (#279); all six `RETIREMENT_TARGET` source reads it catalogs are retired and the governance ratchet is terminal. Archived 2026-07-14; retained as a provenance record of the flip.

# Hub Canonicalization: the D3 Atomic-Flip Map

Status: ✅ Implemented (consumed by #279) — execution guide for **D3** (the atomic flip) in
[hub-canonicalization-atomic-rewrite.md](hub-canonicalization-atomic-rewrite.md). This is the concrete,
line-cited map of every remaining `RETIREMENT_TARGET` source read, its consumers, and the structural /
renderer-measured replacement each needs — the input the D3 executor works from so the flip does not
re-discover the four documented reverts.

It was produced by three Wave-2 flip-map investigations (arg / chain / lambda tracks) against
`github/main` and cross-checked against the byte-identical scaffolding landed in Wave 1–2 (below). Every
line number is relative to the file named in its section; re-grep before editing, as the flip itself
moves lines.

## Why this is one flip, not six read-retirements

The six `RETIREMENT_TARGET` methods (`SourceShapeException`: `WAS_MULTILINE` = `wasMultiline` +
`methodCallArgumentsSpanMultipleLines` + `expressionLambdaStartsOnSelectorLine` +
`objectCreationArgumentsSpanMultipleLines`; `STARTS_ON_SAME_LINE`; `CHAIN_SELECTOR_BROKE` =
`selectorBrokeAfter`) do not partition into independently-retirable subtrees. The consumer maps below show
why:

- The **argument** reads (`methodCallArgumentsSpanMultipleLines`, `objectCreationArgumentsSpanMultipleLines`)
  are consumed at **16 sites inside `MethodCallChainPrinter`** (11 + 5) plus 2 in `VariableInitializerLayout` —
  i.e. the argument decision *is* a chain-root/segment decision.
- The **lambda** read (`expressionLambdaStartsOnSelectorLine`) splits: 4 of its 6 live sites hug a lambda body
  inside a **chain segment**, whose true column is a chain-segment column, not a `leftEdgePrefix`.
- The **chain backbone** (`selectorBrokeAfter` → `sourceMultilineChain`) feeds both the stay-flat gate and the
  canonical-fan route, and reads `methodCallArgumentsSpanMultipleLines` to compute
  `chainHasSourceMultilineArguments`.

So retiring any one read while another still gates the *enclosing* level re-oscillates at the next remaining
read (Failed-attempt #4). The flip removes all hub gates at once and iterates in-tree against the corpus
idempotence / over-width invariants (never goldens), exactly as D3 specifies.

## The oscillation invariant (#191), stated once

A width-driven (renderer-measured) decision **under** a still-source-gated enclosing level is not a fixpoint:
pass 1 emits the width-driven shape, which rewrites the enclosing node's rendered line breaks; pass 2 the
still-live enclosing source read sees the *new* shape and flips, moving the inner decision's column, which
flips the inner arm — `format(format(x)) ≠ format(x)`. This is Failed-attempt #3's bind and it holds
identically for arguments (nested concat/ternary arg), chain fan (2-selector/lambda-rooted), and lambda hug
(body inside a source-gated segment). A fixed-budget / `nodeLine` probe is the other horn: idempotent but
over-width, because it counts block/type nesting, not expression-continuation depth. The only escape is
**pure-AST at the true column**, achieved for the whole hub simultaneously.

---

## Read 1 — `selectorBrokeAfter` (`CHAIN_SELECTOR_BROKE`), the chain backbone

`SourceShapePolicy.java:162`. True iff a selector's name token began on a later source line than the previous
segment ended. **3 direct consumers**, all in `MethodCallChainSourcePlanner.java`:

| Site | Feeds | Decides |
| --- | --- | --- |
| `:428` (loop) → `sourceMultilineChain(root,calls)` (`:422`) | `analysis.sourceMultilineChain()` | any selector author-broke ⇒ whole chain "source multiline" |
| `:438` `methodCallStartsAfterScopeLine` | printer `:3555/3557` `promotedRootArgumentsShouldBreak` | selector-on-own-line ⇒ force the promoted-root arg list open |
| `:448` `sourceMultilinePromotedMethodRoot` | planner `:279` `plan()` | promote root to `INLINE_PROMOTED_METHOD_CALL` rendering |

`sourceMultilineChain` (the amplified field) has **~15 consumers**: stay-flat gate `MethodCallChainPrinter.java:991`
and single-call guard `:1026`; single-segment selectors `:1209/1273/1305`; ranked-shape gates `:1586/1649`;
`canAttachFirstSegmentToSimpleRoot :2295`; public `methodCallChainIsSourceMultiline :3229`
(→ `MethodCallPrinter :1312/1337/1344`); `flatHeadHuggedCommentLambdaChain :4121`; `objectRootSingleSegmentChain`
params `:3679/3711/3766`; planner `plan() :238`, `initializerShape() :313`.

**What `chainBreaksByRule` (planner `:352`) already covers** (structural, root-kind + link-count): ObjectCreation /
MethodCall root ≥ 2 calls; factory (`promotesFirstCall`) root `calls-1 ≥ 2`; **plain receiver ≥ 3**. When it fires,
the early canonical route (`MethodCallChainPrinter :1046-1055`) sends the chain to the source-neutral
`chainFanOut`; `fanShapeRules` (`:1726`) picks the shape.

**The two classes it does NOT cover — the D2b/D2c targets:**
- **D2b — 2-selector plain/field-receiver chains** (`resp.field.get(0).build()`, `orderEvent.validateOrder().deliveryPlan()`):
  `2 < 3`, never canonical; today fanned only by the source read via `canAttachFirstSegmentToSimpleRoot`
  (`:2285`, gated `!sourceMultilineChain` + `startsOnSameLine`). **Fix:** drop the plain-receiver threshold to ≥ 2 for
  this class, and relax the `fanAttachesTrivialReceiverFirstSelector` `>= 3` gate (`:1855`) to match.
- **D2c — lambda/cast-rooted chains** (`((OffsetFetchRequestData) x.get(0).build().data()).groups()`): `rootIsEnclosedFanningChain`
  (`:2324`) only *withholds* the source-gated attach; it never *sources* a fan for a 2-selector enclosed/cast root.
  **Fix:** a structural fan keyed on the enclosed/cast root shape.
- **D2d — segment-lambda fan:** break a lambda body *inside a broken chain segment* by width. The AST-pure segment
  renderer exists (`sourceNeutralExpressionLambdaSegment :3878`, ranks with `Doc.bestFitting`); the residual is making
  the body *break* width-driven (the `StreamsCoordinatorRecordHelpers` residual).

**Fixpoint requirement:** D2b/c must be **structural** (a pure AST function like `chainBreaksByRule`) or
renderer-measured `bestFitting` — never a fixed-budget/`nodeLine` probe (re-oscillates; attempts #1B/#3).

---

## Read 2 — `methodCallArgumentsSpanMultipleLines` (`WAS_MULTILINE`)

Three responsibility groups (arg track) + the chain-hub entanglement.

### Group 1 — per-argument break (D1a, arg-level)
| Site | Role |
| --- | --- |
| `BreakableArgumentExpressionPrinter.java:98/129` (`wasMultiline`) | the flat/broken arm choice on the `argument` / `sourceMultilineArgument` paths |
| `BreakableArgumentExpressionPrinter.java:178` | keep a method-call argument broken when *its* args span source lines |
| `MethodCallPrinter.java:1079` (`!…span…`) | disqualifier of the `sourceMultilineArguments` list-preservation branch |

**Replacement:** renderer-measured `Doc.conditionalGroup([flat, broken])` at the true column threaded via
`layout.leftEdgePrefix()` + `layout.trailingContent()` (both now *available* — D1a plumbing landed, see below).

### Group 2 — single-arg **hug** gates (D1b) — also reads `startsOnSameLine`
| Gate | Def / consumers | Reads |
| --- | --- | --- |
| `hasSingleAttachableMethodCallArgument` | `MethodCallPrinter.java:1290`; used `:1076/1216` | `methodCallArgumentsSpanMultipleLines(inner)` + `startsOnSameLine(name,inner)` |
| `hasSingleAttachableObjectCreationArgument` | `MethodCallPrinter.java:1281`; used `:1075/1120` | `objectCreationArgumentsSpanMultipleLines(inner)` + `startsOnSameLine(name,inner)` |

**Replacement:** rank hug-vs-explode with `Doc.bestFitting` at the true column (#221 Case B sketch at
`MethodCallPrinter:1131-`), dropping the fixed-budget inner-call probe and the `…span… + startsOnSameLine` pair.
This is the dominant residual oscillation family (`assertTrue(chain)`, `Arrays.stream(chain)`, `Optional.of(chain)`):
needs the enclosing chain stay-flat gate un-gated first (`MethodCallChainPrinter:644` `firstLineWidth` still col-0).

### Group 3 — object-creation argument list (D1c, Read 4 below)
See Read 4.

### Entanglement — the 11 sites INSIDE `MethodCallChainPrinter` (chain track owns)
`methodCallArgumentsSpanMultipleLines`: `:1306, 1446, 1532, 1587, 2445, 2453, 2790, 3232, 3294, 3341, 4604`. These
gate compact-root / broken-final-segment routing, fan/attach, and `hasSingleExpressionLambdaArgument (:3341)`;
`chainHasSourceMultilineArguments (:2438)` feeds `sourceMultilineArguments` into **both** the stay-flat gate (`:998`)
**and** the canonical route (`:1050`). Plus `VariableInitializerLayout.java:1486/1520` (initializer chain root). These
flip only with Read 1.

---

## Read 3 — `expressionLambdaStartsOnSelectorLine` (`WAS_MULTILINE`)

`SourceShapePolicy.java:206`. True iff a trailing expr-lambda arg begins on the call's selector line; always paired
with `expressionLambdaSpansMultipleLines` = the hug shape `foo(x ->`⏎`body`⏎`)`. **6 live sites, split by track:**

| # | Site | Decides | Track |
| --- | --- | --- | --- |
| 1 | `MethodCallPrinter.sourceMultilineArguments:1078` | keep the lambda-hug path vs explode into a plain broken arg list | lambda |
| 2 | `MethodCallPrinter.sourceMultilineExpressionLambda:1314` | entry gate, standalone hugged-expr-lambda call | lambda |
| 3 | `MethodCallChainPrinter.groupedPromotedExpressionLambda:2637` | entry gate, promoted (scope-carrying) segment hug | **chain / D2d** |
| 4 | `MethodCallChainPrinter.groupedPromotedRootWithSingleSegment:2672` | selector breaks onto its own dotted line | **chain / D2d** |
| 5 | `MethodCallChainPrinter.compactRootWithBrokenFinalSegment:2826` | hug expr-lambda on the final segment | lambda |
| 6 | `VariableInitializerLayout:2234` | *veto* the initializer-chain shape (hug owns it) | lambda |

**Replacement (sites 1/2/5/6):** renderer-measured admission + `Doc.conditionalGroup([flatSelector, huggedBody])`
at the true column D1g now plumbs into `ExpressionLambdaArgumentLayout#plan` (`nodeIndentWidth + leftEdgePrefix.length()
+ firstLine.length()`), deleting the `startsOnSelectorLine && spans` entry gate. **Sites 3/4** retire with the chain
fan/attach reads (segment column, not `leftEdgePrefix`) under D2d.

Lambda-path `wasMultiline` admission flag `ExpressionLambdaArgumentLayout.plan:488-490` (`sourceMultilineBody`): its
structural replacement is "admit when the flat form overflows at the true column," measured, not source-read (the
oscillation the `logicalBinaryLambdaBodyOpenerHug` Javadoc documents). Other lambda `wasMultiline` uses:
`methodCallBodyWithOpener:142`, `packedBodyCallWithoutClosingLine:794`, `packedBodyCallWithBlockLambda:817`,
`methodCallArgumentsStayFlat:703`, `sourceMultilineBinaryMethodCallBody:1298`, `binaryMethodCallLeftOperand:1308`,
`LambdaBodyHeaderLayout:69`, and the whole `SourceMultilineLambdaCallLayout` (`:163-164/173-174/200`, chain-segment
attach — chain track).

---

## Read 4 — `objectCreationArgumentsSpanMultipleLines` (`WAS_MULTILINE`)

| Site | Role |
| --- | --- |
| `ObjectCreationLayoutPolicy.java:35` (`shouldPreserveSourceMultilineArguments`), `:48`, `:108` | preserve/collapse the constructor arg list |
| `ObjectCreationLayoutPolicy.java:60` (`wasMultiline`, return-constructor) | return-value constructor break |
| `MethodCallChainPrinter.java:643/1650/2802/3234/3671` + `MethodCallChainSourcePlanner.java:303` | object-creation **chain-root** rendering (chain track) |

**Replacement:** `ObjectCreationPrinter.widthDrivenObjectCreation` / `constructorArgumentsAreWidthDriven`
(`ObjectCreationLayoutPolicy:94`) already renders the ≤ 3-arg case source-neutrally and is stable; D1c extends that
group to the 4+-arg / preserved cases. The preserve-branch cannot flip in isolation — `initializerShape:303`
(`rootObjectCreationArgumentsSpanMultipleLines`) feeds the chain fan/attach, so it re-oscillates against the chain
level unless flipped together.

## Read 5 — `startsOnSameLine` (`STARTS_ON_SAME_LINE`)

Consumed only in the Group-2 hug gates (Read 2) and `canAttachFirstSegmentToSimpleRoot` (Read 1). Retires with
those — it has no independent consumer.

## Read 6 — `wasMultiline` (`WAS_MULTILINE`), the base read

The residual `wasMultiline` uses not covered above are the arg-level arm choice (Read 2 Group 1) and the lambda
admission/body reads (Read 3). No standalone subtree; it is the last read standing and drops when the hub is pure-AST.

---

## Track ownership split (who retires what at D3)

| Reads | Track | Replacement summary |
| --- | --- | --- |
| `selectorBrokeAfter`, `sourceMultilineChain`, chain-resident `…span…` (16 sites), `startsOnSameLine` (attach) | **chain** | D2b/c structural fan + `chainBreaksByRule` extension; canonical route drives all chain shapes |
| arg-level `wasMultiline`/`…span…` in `BreakableArgumentExpressionPrinter` + `MethodCallPrinter` + `ObjectCreationLayoutPolicy` | **arg** | renderer-measured `conditionalGroup`/`bestFitting` at the D1a true column |
| `expressionLambdaStartsOnSelectorLine` sites 1/2/5/6 + `sourceMultilineBody` | **lambda** | renderer-measured admission at the D1g true column |
| `expressionLambdaStartsOnSelectorLine` sites 3/4 + `SourceMultilineLambdaCallLayout` + segment hug renderers | **chain / D2d** | segment-lambda fan (`bestFitting` on the existing AST-pure segment renderer) |

These are ownership labels for *reasoning about* the flip, not commit boundaries — the reads flip in one D3 step
because of the entanglement above.

## Load-bearing shortcuts to preserve through the flip

Deleting these regresses to worse shapes (per the reprint-retirement playbook):
`rootIsEnclosedFanningChain` (`MethodCallChainPrinter:2324`, `CommitRequestManagerTest` convergence); the
`fanAttachesTrivialReceiverFirstSelector` `>= 3` + `firstSelectorAttachesSafely` + `rootAvoidsShortRootPadding` guards
(`:1855-1858`, kafka `ConsumerGroupMember`/`Sender` oscillations); the D2a inter-segment-comment gate (comment
safety); the ≤ 3-arg width-driven object-creation root (already stable). Keep them; do not delete blindly.

## Byte-identical scaffolding already in place (the inert substrate D3 builds on)

The flip does not start from scratch — it activates substrate already built and tested byte-identically:

| Landed | What it makes available |
| --- | --- |
| #268 (D0) | corpus harness (4 invariants, base-vs-worktree) + `FRMTR_SOURCE_READ_TRIPWIRE` per-read hit counters — the D3 idempotence signal + "which read is still firing" |
| #269 (D2a) | `chainHasInterSegmentLineComment` fan gate — comment-safety residue A, survives the `selectorBrokeAfter` retirement |
| #270 (D1e) | `LayoutContext` threaded into the last statement/field single-segment chain flat-gates |
| #271 (D1a) | `LayoutContext` (`leftEdgePrefix` + `trailingContent`) threaded into the breakable-argument seam |
| D1g (this batch) | `LayoutContext` threaded into the expr-lambda hug admission plan (`ExpressionLambdaArgumentLayout#plan`) |

At D3 these threaded contexts are *consulted* (the fixed-budget floors replaced by a reading at the threaded true
column), the structural fan rules (D2b/c/d) are switched on, and all hub source gates are removed in one step —
iterated in-tree against the corpus invariants until idempotence Δ = 0 and over-width ⊆ base.

## First D3 execution attempt — empirical findings (2026-07-08)

A first corpus-gated execution attempt (parked, non-converged, not merged) validated the atomic thesis on real
files and sharpened the worklist. It retired **Read 1** (`selectorBrokeAfter`, tripwire → 0) and most of **Read 4**
(`objectCreationArgumentsSpanMultipleLines`, 6216 → 170 hits) plus a partial D2d fix, leaving Reads 2/3/5/6 gated.

Result on kafka (clean-built CLI): `--verify` 0, comment parity, over-width ⊆ base — but **idempotence +7 net-new
non-idempotent files** (300-file sample). So a chain+object-creation retirement **in isolation does not converge**,
exactly as the atomic thesis predicts.

- **The +7 oscillators are one family:** an **object-creation-rooted chain nested inside a broken argument or
  chain-segment lambda body** — `.add(new X().setA(..).setB(..))`, `Collectors.toMap(.., k -> new X(..))`,
  `.flatMap(x -> x.stream().filter(..).map(..))`.
- **Empirical mechanism (tripwire on an oscillator, e.g. `NetworkClient`):** `SELECTOR_BROKE_AFTER` = 0 (Read 1
  retired) but `WAS_MULTILINE` / `METHOD_CALL_ARGUMENTS_SPAN_MULTIPLE_LINES` still fire in the segment-lambda body.
  Retiring Read 1 removed the source-multiline signal that had been *stabilizing* these nested chains; the still-live
  Read 2/6 in `sourceNeutralExpressionLambdaSegment` / `methodCallBodyWithOpener` then observe pass-1's own reflow and
  flip flat⇄break-after-arrow. This is #191 made concrete: Read 1 cannot be a fixpoint while Read 2/6 gate the
  enclosed level.
- **The keystone blocker:** threading the true continuation column (#190 `leftEdgePrefix`) into the **segment-lambda
  opener-fit probes** (`expressionFirstLineWidth` / the object-creation `fanRootDoc` rendered at `root()`) is the
  direct fix for all 7 oscillators — without it the object-creation-rooted-chain-in-argument body has no stable
  width-driven fan. Note this is *not* the clean threaded-but-not-consulted plumbing of D1e/D1a/D1g: those sites have
  no in-scope `LayoutContext`, so the real segment column must first be established there (design work, likely not
  byte-identical on its own).

**Refined ordered worklist to reach idempotence Δ = 0 (all in the one atomic step):**
1. Establish + thread the segment-lambda true column (#190 keystone) into the opener-fit probes above.
2. Retire **Read 2** (`methodCallArgumentsSpanMultipleLines`) atomically across all ~22 sites — including the central
   router `chainHasSourceMultilineArguments` → `methodCallChainIsSourceMultiline` — replacing with renderer-measured
   `conditionalGroup`/`bestFitting` at the threaded column.
3. Retire **Read 3 + Read 6** hub sites in the same step (lambda hug admission + the segment-lambda opener-fit probes).
4. Then the residual **Read 4/5** arg-hug gate + `compactRootWithBrokenFinalSegment` site.
5. Only then drop the `SourceShapeException` entries + flip the governance ratchet.

Read 1 + Read 4 retirement is **correct and load-bearing** but *inert-then-oscillating alone*; it is a valid starting
diff for the driver (parked on the local `wip/d3-atomic-hub-flip` reference), not a landable increment.

## D2b/c validated (prototype, 2026-07-08)

The D2b/c structural fan was designed and prototyped (parked on the local `wip/d2bc-structural-fan` reference,
not landable standalone — Read 1 retired while Reads 2/4 stay live). Outcome: **the target chain families
converge idempotently by width; Read 1's structural replacement works.**

**Rule (validated):** a new **width-driven arm** in `MethodCallChainPrinter.methodCallChain`, immediately after the
early canonical-fan route, gated by a new pure-AST predicate `chainIsWidthDrivenTwoSelectorFan`. `chainBreaksByRule`
is left unchanged (it stays the always-fan rule).
- **D2b** (trivial receiver — `NameExpr`/`FieldAccessExpr`/`this`/`super` — with exactly 2 selectors, incl.
  `x.stream().collect(...)`): `Doc.bestFitting([Doc.text(compact), chainFanOut])` — flat when it fits at the true
  column, source-neutral fan on overflow. Idempotent because both arms are pure-AST and the renderer measures at the
  real column.
- **D2c** (enclosed/cast root wrapping a fanning inner chain): **always-fan** via `chainFanOut` — **not** `bestFitting`.
  The `bestFitting` flat arm was rejected: `fanRootDoc` renders the enclosed root at `LayoutContext.root()` (column 0),
  so a real-column flat arm oscillates against the fan arm's column-0 render; an enclosed fanning root is never short
  enough to stay flat anyway. (This is the architect's predicted fallback, confirmed empirically.)
- **Carve-out (comment safety):** the arm carries the early-canonical `!hasComments && !hasBlockLambdaArgument &&
  !sourceMultilineArguments && noneMatch(segmentHasComment)` guard, so comment-bearing chains fall through to the
  comment-preserving imperative ladder (which never reads `selectorBrokeAfter`). Additionally, the D2a residue is wired
  in: `methodCallChainIsSourceMultiline` now returns true for a `hasInterSegmentLineComment` chain, so the 2-node
  `encode(x)`⏎`// note`⏎`.replaceAll(...)` case keeps its comment.

**Evidence.** 6 self-contained target fixtures pass (idempotent + AST-equiv + comment-safe + not over-width):
`field-root-source-multiline-chain`, `cast-root-fanning-chain`, `trailing-collect-arg-idempotence` (3-selector
control, stays fanned), plus new `two-selector-width-driven-fan`, `stream-collect-two-selector-flat`,
`two-selector-inter-segment-comment`. Corpus (kafka 1200-subset): non-idempotent Δ0, comment Δ0, verify 0, over-width
+1 (3 new — all *non-target*: block-lambda / object-creation-root / nested-lambda collapses). Tripwire:
`SELECTOR_BROKE_AFTER`=0; every residual fires `WAS_MULTILINE`/`METHOD_CALL_ARGUMENTS_SPAN`/`OBJECT_CREATION_ARGS_SPAN`
(Reads 2/4/5/6) — the enclosing-column entanglement, not a D2b/c defect.

**Remaining flip components** (the non-target residual, in the one atomic cut): **D1c** (object-creation-root / 4+-arg
constructor width-driven group → retire Read 4), **Read 2** (`methodCallArgumentsSpanMultipleLines`, incl. the central
`chainHasSourceMultilineArguments`→`methodCallChainIsSourceMultiline` router), and **Read 3/5/6** (lambda hug + nested
+ final-trailing-comment-tail). D2b/c + these + the threaded columns compose into D3.

## The uncatalogued INLINE source-read tier (post-D3 follow-up, 2026-07-09)

The six reads above were the *catalogued* `SourceShapePolicy` retirement targets that governance
(`SourceShapeExceptionGovernanceTest`) enumerates. A second, **inline** tier of raw
`range.begin.line < range.end.line` "was this node multiline in source?" reads lived directly in the
per-printer helpers and never passed through `SourceShapePolicy`, so the closed-set ratchet never saw
them. They are the same `RETIREMENT_TARGET` species — "preserve the author's line breaks" — and are
retired here toward full source-independence. Playbook per read: PROBE the read to `false`, measure the
`FrmtrTest` + `scripts/corpus-check.sh` blast radius, then delete (redundant, the surrounding path
already width-checks — cf. #265) or add a width-driven arm; if it can't retire without regressing
idempotence/over-width, REVERT and defer (do not force). Gate: kafka `--subset 800` (and camel where the
subset under-exercises the path) vs `2d5e7f23` — idempotence ⊆ base, over-width ⊆ base, verify 0, comment
parity.

**RETIRED (5 inline reads):**
- `ArrayExpressionPrinter.sourceSpansMultipleLines` (2 uses: `compactArrayInitializer`,
  `compactArrayInitializerValue`) — **redundant delete**. Both `arrayCreation` and `arrayInitializer` already
  width-check the compact array text before using it; the source-multiline gate only forced the broken shape on
  an array that would otherwise fit. A source-multiline array that fits now collapses; one that overflows still
  breaks.
- `AnnotationExpressionPrinter.annotationArrayValueLine` binary-value break — **width-driven arm**: a `BinaryExpr`
  annotation-array value breaks purely when its compact text overflows, not additionally when it was source-multiline.
- `CallableSignaturePrinter.parameterAnnotationSourceBreaks` → `parameterAnnotationPrefixOverflows` — **width-driven
  arm**: a parameter's annotation prefix renders structured whenever the flat parameter overflows (source-multiline
  requirement dropped). Monotonic on over-width — the structured arm only relieves an overflowing flat prefix.
- `RecordDeclarationPrinter.recordComponentHasSourceMultilineAnnotation` — **redundant delete**: the component-break
  suppression now keys only on the width-driven `recordComponentHasWidthDrivenMultilineAnnotation` sibling.
- `VariableInitializerLayout.sourceSpansMultipleLines` (overflowing array-initializer branch) — **redundant delete /
  shape unification**: every overflowing array initializer now converges on the canonical `= {`-on-the-assignment-line
  shape a source-multiline array already produced, instead of routing single-line-source arrays through a divergent
  break-after-`=` ladder.

**DEFERRED (genuinely load-bearing — proved by PROBE→regression, then reverted):**
- `AnnotationExpressionPrinter.sourceMultilineAnnotation` (`annotationPreservingSourceBreaks`, ~:419). Removing the
  force-break flattens a source-multiline parameter annotation rendered at `LayoutContext.root()`, where the true
  parameter column is invisible; `source-multiline-shapes` gained a 137-col over-width line (`@Lookup(...) Type name`).
  Blocked on the annotation render receiving its enclosing column (left-edge-prefix), the same enclosing-column
  entanglement the D3 residuals hit.
- `TypePrinter.sourceMultilineAnnotation` (:341, gates `sourceAnnotatedGenericArgumentBody` + `typeAnnotation`).
  PROBE→`false` gave `source-multiline-annotation-placement` +3 over-width (122/123/122 cols). It is the *only*
  break mechanism for wide type-use annotations — there is no width-driven fallback — and renders at `root()`.
- `ClassExpressionPrinter.sourceMultiline` (:118) + `startsOnLaterLine` (:111). PROBE→`false` gave
  `class-literal-qualified-name` +2 over-width (139/181 cols). The `Type.class` multiline preservation is the only
  mechanism to break a wide qualified class literal; no width-driven arm exists.
- `VariableInitializerLayout.sourceFirstLineKeepsChainAfterRoot` (:1541), `initializerStartsOnContinuationLine`
  (:1557), `methodCallScopeEndsOnNameLine` (:2244). The object-creation-initializer break-after-`=` / chain-attach
  seam (#221); tied to the enclosing-column deferral above and the D3 chain track.
- `SourceMultilineLambdaCallLayout.lambdaBodyStartsAfterHeader` (:175). **RETIRED — #190 F2 segment-column slice.**
  Previously found +13 over-width if naively removed; the blocker it was waiting on — the segment-lambda source-neutral
  render (`ChainSelectorLambdaLayout.sourceNeutralExpressionLambdaSegment`) — has since landed, so the enclosing chain
  now fans one selector per line at its true column instead of the source-gated attach-first-segment opener hug.
  PROBE→`false` is now strict-subset (0 new over-width / non-idempotent on kafka-800 + camel-800), and the
  `lambda-expression-argument-opener` `assertThatThrownBy(() -> …)` / `probe.withVirtualTime(() -> …)` cases converge to
  a one-pass fixpoint (de-parked from both `KNOWN_NON_IDEMPOTENT` sets).

**EXCLUDED (not aesthetic line-break reads):** `CallableSignaturePrinter.rangeBeginLine` (:594, stable source-order
SORTING of parameter prefix parts), `MethodCallPrinter.sharedFirstLineWidth` (:1238,
`statementRange.begin.line == callRange.begin.line` reconstructs the true first-line column — width-measurement
*correctness*, the opposite of a retirement target), plus the comment/blank-line/order reads named in the task
(`CommentIndex.*`, `DeclarationPrefixPrinter`/`ControlConditionPrinter` `Integer.compare`, `BlockPrinter.effectiveBeginLine`,
`SourceText`/`CommentedMethodSignaturePrinter`).

**RESIDUAL (aesthetic, not attempted — outside the named "was-multiline" set):**
`RecordDeclarationPrinter.recordComponentAnnotationsStartOnDifferentLines` (:382, "author put the component's
annotations on separate source lines") is a distinct multi-annotation-stacking read. A PROBE→`false` changed no
fixture (the difference is uncovered), so its new shape cannot be validated; left live pending a fixture that
exercises it. It belongs to a future governance-lint that flags any inline `begin.line`/`end.line` aesthetic read,
not just `SourceShapePolicy` methods.

After this follow-up the inline "was-multiline" (`begin.line < end.line`) reads still live are exactly three —
`AnnotationExpressionPrinter:419`, `ClassExpressionPrinter:118`, `TypePrinter:341` — all deferred above and all
blocked on the same enclosing-column (left-edge-prefix) gap.

## Threading the enclosing column into the deferred inline reads (follow-up, 2026-07-09)

This batch attacks the deferred inline tier by threading the *true enclosing column* into the render sites — the
`leftEdgePrefix`/`trailingContent` mechanism the merged keystone/D1a/D1e/D1g use — so a break that was source-gated
becomes width-driven at the real column. Gate: `scripts/corpus-check.sh kafka --subset 800 --base f95f6d27`
(idempotence ⊆ base, over-width ⊆ base, verify 0, comment parity) + full `./gradlew test`.

**RETIRED (2 reads — the enclosing column was reachable):**
- `AnnotationExpressionPrinter.sourceMultilineAnnotation` (`annotationPreservingSourceBreaks`). The parameter
  (`CallableSignaturePrinter`) and record-component (`RecordDeclarationPrinter`) callers already had the enclosing
  column in reach: both build the broken list's continuation indent + earlier prefix parts into
  `LayoutContext.leftEdgePrefix()` and the same-line `" Type name"` tail into `LayoutContext.trailingContent()`, and
  `annotationPreservingSourceBreaks(annotation, layout)` now breaks the annotation purely when the flat form plus that
  same-line context overflows. The `@Lookup(...) StreamProcessingDefinition definition` case still breaks (its trailing
  type/name pushes the flat annotation past width); an annotation that fits reprints flat. Rebaselined
  `annotated-generic-type-width` (two fitting component annotations collapse flat). Corpus Δ0.
  *(This is the earlier DEFERRED "+137 over-width without threading" case — resolved by actually threading the column.)*
- `RecordDeclarationPrinter.recordComponentAnnotationsStartOnDifferentLines` (the RESIDUAL multi-annotation-stacking
  read). Clean delete: a component's annotations reprint inline regardless of source shape, and the prefix stacks only
  on the existing width-driven `recordComponentFlat` overflow gate. PROBE→`false` changed no *existing* fixture (still
  uncovered) and was corpus-Δ0, so the retirement adds a new fixture `record-component-annotations-reprint-inline` to
  lock the width-driven shape (source-split-but-fitting annotations collapse inline).

**DEFERRED (3 reads / 5 sites — the enclosing column is genuinely unreachable at the render site):**
- `TypePrinter.sourceMultilineAnnotation` (gates `sourceAnnotatedGenericArgumentBody` + `typeAnnotation`).
  PROBE→`false` re-confirmed: `source-multiline-annotation-placement` gains +2 over-width (`@EncodedKeys({...})` 123,
  `@MergeFormula(value = …)` 122). The wide *type-use* annotation renders deep inside `genericArgumentBody`, which
  receives no `LayoutContext` and cannot know the type-argument continuation column; it is the only break mechanism
  (no width-driven fallback). Threading the column here requires pervasive `LayoutContext` plumbing through the whole
  type renderer (`typeBody`→`classOrInterfaceTypeBody`→`genericArgumentBody`→`typeAnnotation`) — the deeper foundation.
- `ClassExpressionPrinter.sourceMultiline` + `startsOnLaterLine`. PROBE→`false` re-confirmed:
  `class-literal-qualified-name` gains +2 over-width (139, 181). `classExpression(ClassExpr)` receives no
  `LayoutContext`, and the source-multiline preservation is the *only* mechanism to break a wide qualified `Type.class`
  — there is no width-driven fill/wrap over the dotted segments (the break points are keyed on where the source broke).
  Blocked on both threading a column in and building a width-driven segment-wrap algorithm.
- `VariableInitializerLayout.initializerStartsOnContinuationLine` + `methodCallScopeEndsOnNameLine` (+ the sibling
  `sourceFirstLineKeepsChainAfterRoot`, a `rawSource.lines()` read outside the guard's line-compare scope). These gate
  the object-creation-initializer break-after-`=` / chain-attach seam (#221) via `InitializerChainShape` — the D3 chain
  track. The attach decision needs the true continuation column (#190 `leftEdgePrefix`); retiring them in isolation
  re-oscillates against the still-source-gated enclosing chain level (the #191 invariant). Not forced.
- `SourceMultilineLambdaCallLayout.lambdaBodyStartsAfterHeader` + `ExpressionLambdaClosingLayout.callClosingStaysOnLambdaBodyLine`.
  The chain-segment lambda hug/close seam; blocked on the segment-lambda source-neutral render + #190 leftEdgePrefix
  (the D2d track). Not forced.

Net: the inline aesthetic-read allowlist drops from **9 → 7**. The two retired reads were exactly the ones whose
enclosing declaration (parameter / record component) already knew its column; the remaining seven all render at a
column the AST cannot reconstruct (type-argument continuation, class-literal continuation, initializer chain-attach,
segment-lambda body) and are blocked on the same enclosing-column / `leftEdgePrefix` foundation as the D3 chain track.
