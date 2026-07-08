# Hub Canonicalization: the D3 Atomic-Flip Map

Status: 🔵 Execution guide for **D3** (the atomic flip) in
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
