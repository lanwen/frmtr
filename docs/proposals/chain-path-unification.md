# Chain-Path Unification: routing every fluent-chain layout through the ranked engine

Status: 🔵 Proposed — read-only audit + sequenced plan. Depends on the landed convergence-redesign foundation
([convergence-redesign.md](convergence-redesign.md) slices 1–3) and the layout-decision model
([layout-decision-model.md](layout-decision-model.md) LDM-1/-2f/-3). This doc does **not** re-propose those mechanisms;
it inventories every code path that decides or renders a method-call **chain**'s layout, classifies each by whether it
routes through the unified ranked engine (`Doc.bestFitting` + `MethodCallChainPrinter.chainFanOut`) or has its own
imperative logic, and sequences the migration so a general fluent-chain policy can be expressed idempotently.

Scope note: a *chain* here is a `MethodCallExpr` whose scope is itself a call / object-creation / field-access-of-call —
the `root.a().b()` fluent shape. All line numbers are against HEAD `9ec908f7` on branch
`feat/ldm-lambda-body-chain-staygate`.

## TL;DR

- The **ranked engine exists and works**: `Doc.bestFitting(alternatives, int[] priorities)` (`Doc.java:264`), ranked
  `fit → priority → fewer-lines → less-overflow → earliest` in `DocWidths.betterThan` (`DocWidths.java:163`) /
  `chooseBestFitting` (`DocWidths.java:131`), bounded `MAX_BEST_FITTING_DEPTH=4` / `MAX_BEST_FITTING_ALTERNATIVES=8`
  (`DocWidths.java:23`/`:30`); the source-neutral fan-out builder `MethodCallChainPrinter.chainFanOut`
  (`MethodCallChainPrinter.java:1242`) is live.
- **Exactly four consumers route through it today**, all inside `MethodCallChainPrinter` or `VariableInitializerLayout`:
  `rankedSingleSegmentChain` (`:1122`), `rankedObjectRootSingleSegmentChain` (`:1184`), and the initializer's
  `rankedSimpleRootSingleCallConvergence` (`VariableInitializerLayout.java:1629`). All three cover the **single-selector,
  final-segment-breaks** shape only. Every other chain shape — the multi-segment fan-out, the statement/if/argument/binary
  chains, the lambda-body fan, the object-root fan — is chosen by an **imperative ladder** in
  `MethodCallChainPrinter.methodCallChain` (`:611`–`:995`) or by a caller's own gate.
- The imperative ladder is heavily **source-shape gated** (`sourceMultilineChain`, `methodCallArgumentsSpanMultipleLines`,
  `wasMultiline`, `startsOnSameLine`) and measures fit at **fixed `LayoutWidth` budgets** or **`range.begin.column`
  source columns** for the callers that still pass `LayoutContext.root()` (statement, if, assignment RHS, argument,
  binary, ternary). `leftEdgePrefix` — the rendered-column correction — is activated for **only two** callers so far:
  the `return` chain and the variable-initializer chain (`ARCHITECTURE.md:538`, `:572`).
- The unification is therefore **not** "add the engine" (done) but "**migrate the remaining shapes and callers onto it,
  behind the leftEdgePrefix/rendered-column work, byte-identical-first**." The single biggest gate is a **product
  decision** the convergence-redesign already flagged: *fan fluent chains even when they fit* (End-state A, link-count
  convergence, large rebaseline) vs *fan only when over-width* (End-state B, current, width-driven). Most slices are
  policy-neutral; two are policy-dependent.

---

## Part 1 — The chain-path map

### The one ranking engine (target)

| Piece | What it does | file:line |
| --- | --- | --- |
| `Doc.bestFitting(List)` / `(List, int[])` | ranked-broken-layout node; priority vector default all-zero (byte-identical) | `Doc.java:238`, `:264`; record `Doc.java:326` |
| `chooseBestFitting` | picks winner at the **live output column**, bounded/deterministic | `DocWidths.java:131` |
| `betterThan` | key order `fit → priority → fewer-lines → less-overflow → earliest` (priority only among fitters, after the fit gate) | `DocWidths.java:163` |
| `measureLineCount` | side-effect-free mirror of the render walk (congruence-pinned) | `DocWidths.java:204` |
| `chainFanOut(root, calls, tail, layout)` | **source-neutral** one-`.selector`-per-line fan-out from the AST alone; single selector fans onto its own dotted line; each segment renders through the ordinary segment group (per-segment arg break stays with the renderer) | `MethodCallChainPrinter.java:1242` |
| `chainContinuation(Doc)` = `indent(indent(concat(HARD_LINE, doc)))` | the +8 dotted continuation | `MethodCallChainPrinter.java:2008` |
| `objectRootContinuation(Doc)` = `indent(concat(HARD_LINE, doc))` | the +4 continuation for object roots | `MethodCallChainPrinter.java:2782` |

### Chain-rendering paths by syntactic position

Legend for **engine**: **X** = already on the ranked engine (`bestFitting`); **Y** = imperative but AST/rendered-column
clean (mechanical to route); **Z** = imperative + source-shape / source-column dependent (needs leftEdgePrefix /
rendered-column work first). **Shapes**: flat, packed (greedy first-line then dotted), compact-root-with-broken-final-
segment (CRBFS), one-per-line fan, dot-split (single selector onto its own dotted line), open-final-arg,
whole-call-collapse (`NAME =`⏎`call`).

| # | Position | Owner (entry) | Shapes it can emit | Engine? | Source-shape / column risk | Gate conditions |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | **statement-expr** `foo.a().b();` | `StatementPrinter.expressionStatement` → `MethodCallPrinter` forced-semicolon renderer → `MethodCallChainPrinter.methodCallChain(…FORCED…)` | source-multiline preserve; one-per-line fan (Branch P) | **Z** | reads `wasMultiline(statement)` (`MethodCallChainPrinter.java:525`); `methodCallChainIsSourceMultiline`; passes **`LineBudget.BLOCK`** hard-coded, **no `LayoutContext`** (→ `root()`, source-column floor) | `StatementPrinter` chain-vs-call gate reads `hasComments`/`isSourceMultiline`/`rootIsObjectCreation`/`!rootIsFieldAccess` |
| 2 | **assignment RHS** `x = a.b().c();` | `AssignmentExpressionPrinter.assignmentWithMethodCallValue` (`:471`) → `MethodCallPrinter.assignmentWithBrokenMethodCallArguments` (`:1454`) | broken-arg-list; forced chain (source-multiline / prefix-overflow) | **Z** | `methodCallChainIsSourceMultiline` (`:1469`); threads prefix via a **`firstLineWidth` closure at `LineBudget.BLOCK`** (`:1468`), **not** `leftEdgePrefix` (passes no `LayoutContext`) | source-multiline OR `prefixedFirstLineWidth(compact) > lineWidth` |
| 3 | **variable / field initializer** `T x = a.b();` | `VariableInitializerLayout.variableInitializerBrokenOrFlat` → arms | flat (master `conditionalGroup` #215); **argument-break@1 vs whole-call-collapse@0 (RANKED)**; dot-split tail (#221 B); packed fan; compact object-creation; forced chain | **X** (one arm) + **Z/Y** (rest) | ranked arm is AST+rendered-column clean; the object-creation / packed / forced arms read `methodCallChainRootIsObjectCreation`, `sourceMultiline*`; **`leftEdgePrefix("NAME = ")` ACTIVATED** for the forced arm (`:2251`) | ranked arm gated comment-free + single-selector + simple-attachable-root (`:1635`); rest source-shape gated |
| 4 | **return** `return a.b();` | `ReturnExpressionPrinter.returnStatement` (`:198`) → `conditionalGroup([flat, broken])` (`:258`); broken arm → `returnWithForcedMethodCallChain` (`:529`) | flat; source-multiline preserve; source-multiline-lambda; **CRBFS→RANKED** and object-root→**RANKED**; forced chain; broken-call fallback | **X** (broken-shape ranking) + **Z** (source-multiline arms) | `methodCallChainIsSourceMultiline` (`:545`,`:551`); **`leftEdgePrefix("return ")` ACTIVATED** (`:540`) → drops source-column floor in `compactRootLineWidth` (`:1951`) | comment-free return uses the renderer-measured `conditionalGroup`; comment/source-multiline preempts (`:237`) |
| 5 | **method-call argument** `outer(a.b().c())` | `MethodCallPrinter.methodCallArgumentDoc` → `methodCallChain(…AUTO…, LineBudget.CONTINUATION)` (`:1293`) | stay-flat OR one-per-line fan (Branch P) | **Z** | AUTO stay-flat gate reads `sourceMultilineChain` (`:633`), `methodCallArgumentsSpanMultipleLines` (`:899`); threads **`CONTINUATION` budget** so the flat probe measures at the deeper column, but **`LayoutContext.root()`** (no `leftEdgePrefix`) | stay-flat unless `firstLineWidth(compact) > lineWidth` at CONTINUATION (`:648`) |
| 6 | **binary operand / binary-wrapped arg** `a.b() + y` | `BinaryExpressionPrinter.binaryExpressionLineOperand` (`:239`) → `forcedMethodCallChainRenderer` else `brokenMethodCallRenderer` (`:242`,`:252`) | forced chain; broken-call | **Z** (delegates to imperative chain) | break gate `methodCallOperandShouldBreak` (`:303`) is **deliberately width+AST-only** (docs `:289`–`:299` cite the #119 non-idempotence lesson from keying on `methodCallArgumentsSpanMultipleLines`); reads `wasMultiline` (`:215`) elsewhere; **no `LayoutContext`** to the chain | operand flat width > width at its (nested-)continuation line |
| 7 | **ternary branch** `c ? a.b() : z` | `ConditionalExpressionPrinter.conditionalBranch` (`:785`) | source-multiline preserve (raw text `:790`); else generic `expressionRenderer` → MethodCallPrinter → chain at **`CURRENT`** | **Z** | `wasMultiline(branch)` (`:789`) → raw source; otherwise inherits the generic chain path with **`LayoutContext.root()`**, **`CURRENT`** budget (not CONTINUATION) | multiline source preserved raw; else width-driven via chain |
| 8 | **expression-lambda body** `x -> a.b().c()` (as an outer arg) | `ExpressionLambdaArgumentLayout.huggableMethodCallArguments` gate `overflowingHuggedBareRootChainBody` (`:357`,`:574`) → `ExpressionPrinters.huggedLambdaBodyChain` (`:437`) → `forcedMethodCallChain(…CURRENT, withLeftEdgePrefix(firstLine+" "))` (`:438`) | flat-open-tail (packed); **over-width dot-fan (#221 Case A)** — compact root on header line, each `.selector` dotted, close dedented | **Y** (imperative but rendered-column clean) | gate is **AST-only** (`chainRootIsBareCall`, `chainCallsCanStayFlat` reads `wasMultiline` on args only); measures via `nodeIndentWidth + firstLine + 1` — **no `range.begin.column`**; **`leftEdgePrefix` threaded** for the width gate but the shape is `forcedMethodCallChain`, **not `bestFitting`** | bare-call root, ≥2 segments, all calls flat, chain overflows the hugged column, fan fits |
| 9 | **if / while / control condition** `if (a.b().c())` | `ControlConditionPrinter` / `ControlConditionMethodCallLayout` → `forcedMethodCallChain.apply(methodCall)` (`ControlConditionMethodCallLayout.java:83`,`:92`,`:130`) | source-multiline preserve; forced chain | **Z** | entirely `sourceShapePolicy.wasMultiline` / `sourceMultilineLogicalCondition` gated; **no `LayoutContext`** (→ `root()`, empty prefix) | only fires when the condition/operand `wasMultiline` |
| 10 | **nested-in-another-chain** (chain as a chain-segment arg) | recurses through `methodCallChainSegment` → generic dispatch → `methodCallChain` | inherits the segment's context; ranked when the inner chain hits `rankedSingleSegmentChain` | **X**/**Z** | inner chain sees `chainFanOut` root rendered at `LayoutContext.root()` (`:1248`) — **no prefix threaded into nested chains** | same gates as the top-level chain |
| — | **array-initializer element** `{ a.b().c(), … }` | `ArrayExpressionPrinter` → `methodCallWithTail` (ordinary dispatch) | **no chain-specific path**; renders as a nested expression | — (n/a) | inherits the array's continuation indent, not a chain-dedicated printer | — |
| — | **cast operand** `(Foo) a.b().c()` | `CastExpressionPrinter` → `expression.format(operand, root())` | **no chain-specific path** | — (n/a) | cast only gates whether the *type* breaks (intersection casts), never the operand chain | — |
| — | **throws / annotation** | — | chains do not render in these contexts | — (n/a) | — | — |

**Reading the map.** The only **X** cells are single-selector-with-breakable-final-arg (positions 3, 4) and the
nested case that reaches those (10). Every fan-out you actually see in the corpus for a *multi-segment* chain — the
one-per-line shape at positions 1, 5, 8, 9 — is emitted by the **imperative Branch P**
(`MethodCallChainPrinter.java:988`–`994`, `Doc.concat(rootDoc, chainContinuation(root, segments))`) or by
`forcedMethodCallChain`, **not** by `chainFanOut`/`bestFitting`, even though `chainFanOut` builds the byte-identical
shape. That is the fragmentation: **same output shape, five different owners, four of them source-shape/source-column
gated.**

### The imperative ladder inside `MethodCallChainPrinter.methodCallChain` (`:611`–`:995`)

This is the single most important file region. It is an `Optional<Doc>` cascade with ~20 early-returns. Key structure:

- **Stay-flat gate** (`:623`–`:652`): returns empty (leave flat) unless forced / has comments / source-multiline /
  over-width at `firstLineWidth` — this is where AUTO-mode argument chains (pos 5) and ternary chains (pos 7) decide.
- **`speculatively` comment-rollback probes** (`:671`, `:695`, `:779`, `:901`, `:908`, `:938`, `:951`, `:959`) —
  first-builder-wins comment claim; the reason comment-bearing chains **cannot** use the eager two-arm `bestFitting`.
- **Single-segment method-root branch** (`:872`–`:982`): tries `compactRootWithBrokenFinalSegment` speculatively, then
  `expressionLambdaRootWithSingleSegment`, then **`rankedSingleSegmentChain` (X, `:920`)**, then imperative fallbacks
  (`brokenRootWithAttachedFinalSegment`, `sourceMultilineArguments`, grouped-promoted).
- **Single-segment object-root branch** (`:839`–`:871`): **`rankedObjectRootSingleSegmentChain` (X, `:847`)** then
  imperative `objectRootSingleSegmentChain` (`:860`).
- **Multi-segment fall-through / Branch P** (`:987`–`994`): `recordChainWidthBreak` then the imperative one-per-line
  `chainContinuation(root, segments)`. **This is what `chainFanOut` was extracted to reproduce but does not yet feed.**

`compactRootWithBrokenFinalSegment` (`:1774`) — the shared CRBFS builder both rankers use as their flat arm — is itself
gated on `methodCallArgumentsSpanMultipleLines` / `objectCreationArgumentsSpanMultipleLines` / `startsOnSameLine`
(`:1789`,`:1801`,`:1809`) and reads `leftEdgePrefix` via `compactRootLineWidth` (`:1943`). So the **flat arm of the
ranked node is still source-shape gated**; the ranker only owns the compact-vs-fan verdict between two already-source-
filtered shapes.

### The still-load-bearing floors (what blocks a clean rendered-column migration)

`leftEdgePrefix` is read in exactly two width gates and two shape refinements:

- `compactRootLineWidth` (`:1943`–`:1959`): **prefix set** → `nodeIndentWidth(root) + prefix.length() + firstLine.length()`
  (exact rendered column, floor dropped); **prefix empty** → `max(range.begin.column+1, nodeIndentWidth) + firstLine`
  (the **source-column floor** — still load-bearing for pos 1/2/5/6/7/9).
- `refuseOpeningSingleSimpleReturnChainTail` (`:1730`) and the object-root fan-out tail (`:2741`): both gated on
  `!leftEdgePrefix().isEmpty()` — **return-chain-only** refinements.
- Three sibling gates thread `layout` but **do not read the prefix yet** (pure plumbing, source-column floor unchanged):
  `rootLineWidth` (`:2466`), `selectorLineWidth` (`:2572`), and `MethodCallPrinter.methodCallRootLineWidth`
  (per `ARCHITECTURE.md:564`). These are the "floors that survive" the doc's slice-6 names.

`LayoutContext.widthBudget` (`LayoutContext.java:23`, marked **transitional**) and the `LayoutWidth.LineBudget` enum
(`LayoutWidth.java:22`) are the fixed-per-depth crutch the rendered-column end state retires.

---

## Part 2 — Classification & dependencies

### Per-path classification

- **(X) Already ranked** — nothing to migrate for the shape they own: `rankedSingleSegmentChain`,
  `rankedObjectRootSingleSegmentChain`, `rankedSimpleRootSingleCallConvergence`. Residual work is *widening* what they
  cover (multi-segment, more root kinds), which is behavioral.
- **(Y) Imperative but AST/rendered-column clean** — mechanical to route once the fan-out is the ranked arm:
  - The **multi-segment Branch P** (`:988`–`994`) — builds the identical shape `chainFanOut` builds; it is reached only
    after the source-shape gates above already fired, so its *own* body is AST-only.
  - The **lambda-body #221 Case A fan** (pos 8) — already rendered-column clean via threaded `leftEdgePrefix` +
    `nodeIndentWidth`; it just isn't expressed as a `bestFitting` (it is a hand-picked `forcedMethodCallChain`).
  - The **binary operand** break *gate* (pos 6, `methodCallOperandShouldBreak`) — already width+AST-only by design; the
    *shape* it delegates to (`forcedMethodCallChain`) is the imperative ladder, so the delegate, not the gate, is the Z.
- **(Z) Imperative + source-shape / source-column dependent** — need the leftEdgePrefix/rendered-column activation for
  their caller **first**: statement (1), assignment RHS (2), argument (5), ternary (7), if/control (9), and the
  source-multiline arms of return (4) / initializer (3). These are the paths that pass `LayoutContext.root()` and lean
  on the `range.begin.column` floor or a fixed `LineBudget`.

### Dependency graph

```
convergence slice-1 (priority vector)         [landed]
convergence slice-2 (chainFanOut)             [landed]
convergence slice-3 (initializer ranked arm)  [landed]  ── X for pos 3 single-call
LDM-2f return leftEdgePrefix                  [landed]  ── X-capable for pos 4
LDM-2f initializer leftEdgePrefix             [landed]

        ┌─ U1  multi-segment Branch P → chainFanOut (byte-identical)   [depends: slice-2]
        ├─ U2  return conditionalGroup broken arm fully via bestFitting[depends: U1, pos-4 prefix]
        ├─ U3  activate leftEdgePrefix for statement/argument callers  [depends: sibling-gate reads]
        │        └─ U4  statement + argument chains → bestFitting       [depends: U1, U3]
        ├─ U5  if/control + ternary + assignment RHS leftEdgePrefix    [depends: U3 pattern]
        │        └─ U6  those callers → bestFitting                     [depends: U1, U5]  ← policy-dependent
        ├─ U7  lambda-body #221A fan expressed as bestFitting          [depends: U1]        ← policy-dependent
        ├─ U8  binary operand delegate → bestFitting                   [depends: U1, U4]
        └─ U9  retire LayoutWidth.LineBudget + widthBudget selector    [depends: U3,U5 done]
```

The critical enabler is **U1** (make `chainFanOut` the actual multi-segment fan-out arm) and **U3** (activate
`leftEdgePrefix` for the callers that still use the source-column floor), because every downstream `bestFitting`
migration needs (a) the fan-out to be a real ranked alternative and (b) the flat arm to be measured at the rendered
column, not a stale source column.

---

## Part 3 — Sequenced unification plan (byte-identical-first)

Each slice is independently shippable behind the AST-equivalence + `IdempotencePropertyTest`
(`frmtr-core/src/test/java/dev/lanwen/frmtr/java/IdempotencePropertyTest.java`) + whole-corpus fixture net. **The #163
lesson is the governing rule: unit-green ≠ corpus-green; every slice is measured against the corpus for
non-idempotence** (the redesign's baseline is the `field-init-typelike-root-idempotence` / `seenProviders` fixpoint and
the whole-corpus idempotence sweep; the historical regression was 432→782 when chain-breaks were decided imperatively
across printers with source-shape signals). A slice that only unit-passes is **not** shippable.

### U1 — Multi-segment Branch P through `chainFanOut` (byte-identical). Policy-neutral.

**Touches:** `MethodCallChainPrinter.methodCallChain` multi-segment fall-through (`:987`–`994`). Replace
`Doc.concat(rootDoc, chainContinuation(root, segments))` with a `chainFanOut(root, calls, finalSegmentSuffix, layout)`
call **when `rootDoc` equals what `chainFanOut` would build** (the EXPRESSION_RENDERER root case). Where `rootDoc` is a
promoted/broken-object-creation root, keep the inline construction (chainFanOut renders the root via
`expressionRenderer.format(root, root())`, which differs from a promoted `rootDoc`).

**Expected golden impact:** byte-identical — `chainFanOut`'s multi-segment branch already delegates to the identical
`chainContinuation(root, methodCallChainSegments(calls, tail))` (`:1258`). **Closes nothing; unblocks U2/U4/U6/U7/U8** by
making the multi-segment fan-out a *named, reusable* arm the rankers can list.

**Gate:** corpus byte-identity diff must be empty; idempotence unchanged. This is the "prove the builder feeds the real
path" slice.

### U2 — Return broken-shape ranking widened to multi-segment (reviewed, expected byte-identical). Policy-neutral.

**Touches:** the return path already routes single-segment through `rankedSingleSegmentChain`/`rankedObjectRootSingleSegmentChain`
via `returnWithForcedMethodCallChain` (`ReturnExpressionPrinter.java:566`,`:562`). Extend the ranked emission to the
**multi-segment** return chain: where the broken arm currently falls to `forcedMethodCallChain` for a comment-free,
width-driven, non-source-multiline chain (`:572`), emit `bestFitting([CRBFS-or-packed, chainFanOut])` instead of the
imperative forced chain.

**Expected golden impact:** the return `conditionalGroup`'s broken arm becomes fully renderer-ranked. Likely
byte-identical for chains where the compact/packed shape already wins on line count; **reviewed** because a multi-segment
return chain that today packs greedily could re-rank to a full fan. Guard with a fixture mirroring
`return-chain-final-argument` at 3+ segments.

**Gate:** corpus diff reviewed per moved golden; idempotence green. `leftEdgePrefix("return ")` is already active so the
flat arm measures correctly.

### U3 — Activate `leftEdgePrefix` for the statement and argument callers (determinism hardening, expected byte-identical). Policy-neutral.

**Touches:** `StatementPrinter.expressionStatement` (create a `LayoutContext` with the empty statement prefix — a
statement chain owns its own first column, so `leftEdgePrefix=""` but the *budget* must become rendered-column via
`nodeIndentWidth`, not `LineBudget.BLOCK`); `MethodCallPrinter.methodCallArgumentDoc` (`:1293`) thread a real
`LayoutContext` carrying the argument's continuation prefix instead of `root()`. This is the same activation the return
(`ARCHITECTURE.md:538`) and initializer (`:572`) slices did, applied to the next two callers. Requires the three
sibling gates (`rootLineWidth` `:2466`, `selectorLineWidth` `:2572`, `MethodCallPrinter.methodCallRootLineWidth`) to
actually **read** the prefix (they are plumbed but no-op today).

**Expected golden impact:** byte-identical where the source-column floor already equalled the rendered column (already-
formatted corpus), a determinism hardening for reindented inputs — exactly the initializer slice's outcome
(`ARCHITECTURE.md:578`–`580`). **Reviewed** because the argument path adds a real prefix where there was a source-column
floor; a reindented nested chain is now measured at its true column.

**Gate:** the key regression to watch is the `source-multiline-method-root-chain-initializer` family
(`ARCHITECTURE.md:1936`) — a bare `nodeIndentWidth` swap without a threaded prefix regressed it, so the prefix must be
threaded, not just the floor dropped. Corpus idempotence green; every moved golden justified.

### U4 — Statement + argument chains → `bestFitting` (reviewed). Policy-neutral (width-driven only).

**Touches:** after U3, the statement forced-chain tail and the AUTO-mode argument fan both have a rendered-column flat
measurement and a `chainFanOut` arm. Replace the imperative Branch-P fall-through for these callers with
`bestFitting([compact/packed, chainFanOut])`, gated comment-free exactly like the landed rankers
(`MethodCallChainPrinter.java:1133`).

**Expected golden impact:** the visible fan-out shape is unchanged (U1 proved byte-identity of the shape); the *choice*
between packed-greedy and full-fan now happens at the renderer. **Reviewed** — a statement chain that today greedy-packs
its first line (`packedMethodCallChain`) may re-rank. This is the first slice where the packed-vs-fan house style is
exercised through the engine, so it is where End-state A/B first bites for statements (see the product decision below).

**Gate:** corpus diff reviewed; idempotence green. **This slice is the canary for the A/B decision** — if packed shapes
re-rank to fans, that is End-state A leaking in and must be an explicit choice, not a silent rebaseline.

### U5 — Activate `leftEdgePrefix` for if/control + ternary + assignment RHS (expected byte-identical). Policy-neutral.

**Touches:** `ControlConditionMethodCallLayout.forcedMethodCallChain` call sites (`:83`,`:92`,`:130`),
`ConditionalExpressionPrinter.conditionalBranch` (`:785`), `AssignmentExpressionPrinter`/
`MethodCallPrinter.assignmentWithBrokenMethodCallArguments` (`:1454`) — thread a real `LayoutContext` (the if-condition
prefix `if (`, the ternary branch's continuation, the assignment `target op `) instead of `root()` / the bare
`firstLineWidth` closure. Same pattern as U3.

**Expected golden impact:** byte-identical on already-formatted corpus (measurement parity), determinism hardening for
reindented inputs. **Reviewed.** These callers are the most source-shape-entangled (the if path is *entirely*
`wasMultiline` gated), so the activation must preserve the "preserve a deliberately-multiline condition" behavior —
which stays imperative (source-shape preservation is deliberate per `layout-decision-model.md` D13); only the
**width-driven** decision moves to the rendered column.

**Gate:** corpus idempotence green; the source-multiline-preservation fixtures for if/ternary must not move.

### U6 — if/control + ternary + assignment RHS width-driven chains → `bestFitting` (reviewed). **Policy-dependent.**

**Touches:** the width-driven (non-source-multiline) chain arms of positions 2, 7, 9. Route them through
`bestFitting([compact/packed, chainFanOut])` behind the U5 prefix.

**Expected golden impact:** **reviewed, potentially large.** These positions currently fan only via
`forcedMethodCallChain` when *source-multiline* or *over-width*; unifying them means the fan-vs-compact verdict is
width-ranked for every width-driven chain in a condition/branch/assignment. **This is where the A/B product decision is
load-bearing** (see below): under End-state B (fan only when over-width) this is byte-identical for fitting chains;
under End-state A (fan when link-count says so even if it fits) it rebaselines every multi-link condition/branch chain.

**Gate:** requires the A/B decision resolved first. Corpus diff reviewed per moved golden; idempotence green.

### U7 — Lambda-body #221 Case A fan expressed as `bestFitting` (reviewed). **Policy-dependent.**

**Touches:** `ExpressionLambdaArgumentLayout.huggableMethodCallArguments` (`:355`–`:376`). Today the over-width
lambda-body chain is a hand-picked `forcedMethodCallChain` (pos 8, classification Y). Express the flat-open-tail vs
dot-fan choice as `bestFitting([packedLambdaBodyChain, chainFanOut])` at the packed-lambda-body seam
(`MethodCallChainPrinter.packedExpressionLambdaBodyChain` `:445`), so the renderer — not the `overflowingHuggedBareRootChainBody`
predicate — picks. The prefix is already threaded (`withLeftEdgePrefix(firstLine+" ")`, `ExpressionPrinters.java:441`),
so this is rendered-column clean.

**Expected golden impact:** should reproduce the current `lambda-body-chain-dotted-fan` fixture by mechanism; **reviewed**
because it generalizes the currently-narrow gate (bare-call-rooted only) — the scope narrowing at
`overflowingHuggedBareRootChainBody` (`:574`) is a *policy* choice (only bare-call roots fan) that the engine would not
reproduce without an explicit gate. If the intent is to keep scoped-root lambda-body chains packed, the gate must remain.

**Gate:** the `lambda-body-chain-dotted-fan` fixture must not move unless intended; idempotence green. This is the
largest lambda-body surface, so it rides late.

**Realized (cutover seam U7, End-state A).** `ExpressionLambdaArgumentLayout.huggableMethodCallArguments` now consults
`MethodCallChainPrinter.lambdaBodyChainFansByCanonicalRule` *before* the fitting `compactBodyWithClosingLine` shape, so a
fan-threshold lambda-body chain fans one selector per line even when it fits, generalizing the gate past the old
bare-call-root-only `overflowingHuggedBareRootChainBody` (retained as a subsumed fallback). Both triggers share the
`huggedLambdaBodyChain` → `forcedMethodCallChain(withLeftEdgePrefix(firstLine + " "))` render. The generalization was NOT
expressed as `bestFitting` — the existing forced-fan render already produces the canonical shape and threads the header
column, so no ranked-node change was needed. **Deferred slice:** object-creation-rooted lambda-body chains
(`.map(x -> new Record().setA(...).setB(...))`) are withheld (`!(root instanceof ObjectCreationExpr)`), because
`chainFanOut` renders that root at `LayoutContext.root()` and `new X()` hugs its first selector on a flat pass but breaks
onto its own line on a source-multiline pass — the nested-root gap this plan names — so fanning them reintroduced ~16
kafka oscillations. They stay on the source-shape-stable packed shapes until the root-at-`root()` gap is closed (the
same leftEdgePrefix-into-the-root work #190 tracks).

**Realized (cutover seam, the lambda-body ARROW position, #190).** U7 above fans the lambda body in the *hug* position
(`huggableMethodCallArguments`), but left the **break-after-`->` vs attach-root-to-`->`** verdict of a fan-carrying
lambda body that is an *exploded/standalone argument* (`() -> admin.createTopics(...).all().get()`, rendered by
`LambdaExpressionPrinter.lambdaExpression`, not the hug path) on a source-shape-gated dual decider:
`LambdaBodyHeaderLayout.sourceMultilineMethodCallBodyWithHeader` attaches when the body's *raw first source line* fits
after the arrow (which, once a prior pass fanned the body, is just the bare root), and the broken-after-arrow fallback
breaks when it overflowed — so a fan-carrying body alternated `() ->`⏎`admin`⏎`.createTopics(…)…` (break) ⇄
`() -> admin`⏎`.createTopics(…)…` (attach) forever (5 kafka bucket-C files). The fix replicates the initializer
break-after-`=` seam: `LambdaExpressionPrinter.lambdaBodyChainArrowBestFitting` ranks two AST-derived arms with
`Doc.bestFitting` at the true column — attached (`params -> ` + fan) vs broken (`params ->`⏎ indent(fan)) — both wrapping
**one** source-neutral `chainFanOut` fan rendered once at `LayoutContext.root()` (`ExpressionPrinters.lambdaBodyCanonicalFanChain`,
gated by the U7 `lambdaBodyChainFansByCanonicalRule`). Sharing the one prefix-agnostic fan is load-bearing (the
initializer lesson): the root renders at `root()` in both arms, so a promoted-factory/method-call opener group cannot
break differently between arms and re-flip. Placed *after* `methodCallBodyWithOpener` so a body whose outermost call
carries arguments (`entry -> entry.a().b().compose(x, y)`) keeps its stable opener shape — only argument-less-tail chains
(`.all().get()`, `.stream()`, `.isPresent()`) fall here. **Measured (kafka, base 179):** cutover 205→202 non-idempotent,
NEW 35→32, the 3 name/receiver-rooted bucket-C arrow chains converge, 0 newly-broken (NEW is a strict subset),
`--verify` 0 non-equivalent, full `./gradlew test` failure set byte-identical to the pre-seam tip. **Deferred slice
(unchanged from U7, both still oscillate but are byte-identical to the pre-seam tip):** a body chain whose lambda is
hosted by a chain-selector call (`stream.flatMap(broker -> broker.config()...)`), and a body chain carrying an
expression-lambda selector (`e -> e.name().filter(n -> …).isPresent()`) — both withheld by
`lambdaBodyChainFansByCanonicalRule`/`chainFansByCanonicalRule` so `lambdaBodyCanonicalFanChain` returns empty and they
fall through to the unchanged source-shape branches. The residual 32 NEW are dominated by the **first-selector
attach/break** position (`data.configResources()` ⇄ `data`⏎`.configResources()`, incl. the expression-lambda-selector
return chains), the next seam.

### U8 — Binary operand delegate → `bestFitting` (reviewed). Policy-neutral (gate stays width+AST-only).

**Touches:** `BinaryExpressionPrinter.binaryExpressionLineOperand` (`:242`,`:252`). The break *gate*
(`methodCallOperandShouldBreak`) stays exactly as-is (it is already the #119-hardened width+AST-only predicate); only the
**delegate shape** changes from `forcedMethodCallChain`→`bestFitting([compact, chainFanOut])` once U1/U4 land, so a
broken binary operand chain ranks its shape at the operand's continuation column.

**Expected golden impact:** reviewed; the operand column is threaded via `continuationStatementWidth`/`nestedContinuationLine`
today, so a real `LayoutContext` must carry that. Byte-identical for operands whose compact shape already wins.

**Gate:** the `#119` non-idempotence fixture (operand args flat-vs-exploded) is the mandatory guard — this is the
canonical cross-context oscillation this whole effort must not reintroduce. Idempotence green.

### U9 — Retire `LayoutWidth.LineBudget` + `LayoutContext.widthBudget` (byte-identical cleanup). Policy-neutral.

**Touches:** with U3/U5 activating `leftEdgePrefix` for every caller and the width-driven arms ranked at the rendered
column, the `LayoutWidth.LineBudget` fixed-depth budgets (`LayoutWidth.java:22`), the `nodeLine`/`variableInitializer`
probes, and the transitional `LayoutContext.widthBudget` selector (`LayoutContext.java:23`) lose their last readers.
Delete them.

**Expected golden impact:** byte-identical delete (the C10 end state `convergence-redesign.md` slice 6 /
`layout-decision-model.md` C10 name). **Closes #220 / the #190 C10 tail.**

**Gate:** compile-clean removal; corpus byte-identical; idempotence green.

### Slice ordering summary

| Slice | Byte-identical? | Policy | Closes / enables | Corpus gate |
| --- | --- | --- | --- | --- |
| U1 | yes | neutral | enables U2/U4/U6/U7/U8 | empty diff |
| U2 | reviewed (exp. identical) | neutral | return multi-segment ranking | per-golden |
| U3 | reviewed (exp. identical) | neutral | enables U4 | `source-multiline-method-root-chain-initializer` |
| U4 | reviewed | neutral (width-driven) | statement + argument ranked; **A/B canary** | per-golden |
| U5 | reviewed (exp. identical) | neutral | enables U6 | if/ternary source-multiline preservation |
| U6 | reviewed | **A/B-dependent** | if/ternary/assignment ranked | needs A/B first |
| U7 | reviewed | **A/B-dependent** | lambda-body #221A ranked | `lambda-body-chain-dotted-fan` |
| U8 | reviewed | neutral (gate unchanged) | binary operand ranked | **#119 oscillation fixture** |
| U9 | yes | neutral | retire LayoutWidth (#220) | empty diff |

Foundation (already landed) → **U1** (feed the builder) → **U3/U5** (rendered-column activation) → **U2/U4/U8**
(policy-neutral migrations) → **U6/U7** (policy-dependent, after the A/B call) → **U9** (cleanup).

---

## Part 4 — The A/B product decision

The convergence-redesign already isolated this (`convergence-redesign.md` "Needs a product decision"). Restated for the
chain unification:

- **End-state A — fan fluent chains when a link-count / convergence rule says so, even when they fit.** This is the
  prettier-java / gjf "one segment per line once the chain is deemed a builder" convention (the dropped #163
  link-count-root-kind rule). Adopting it means a multi-link chain fans **regardless of width**, which rebaselines a
  large fraction of the corpus (every 3+ link chain that today stays flat/packed). The unified engine expresses it as a
  **priority** on the `chainFanOut` arm (fan preferred over compact even at equal-or-more lines), reusing Mechanism 2.
- **End-state B — fan only when the compact/packed shape is over-width.** The current behavior: `bestFitting` ranks
  fewer-lines-first, so a fitting compact shape always beats the fan. No rebaseline; the engine is purely a
  width-arbiter.

**Which slices are which:**

- **Policy-neutral (safe under either A or B): U1, U2, U3, U5, U8, U9** — they change *who owns* the decision and *at
  what column it is measured*, not *whether a fitting chain fans*. Under B they are byte-identical-or-reviewed-small;
  they are the safe-now spine.
- **The A/B call must be made before U4 lands as a *behavioral* change and before U6/U7 at all.** U4 is where the packed-
  vs-fan verdict first flows through the renderer for statements; under B it is byte-identical for fitting chains, under
  A it fans them. U6 (conditions/branches/assignments) and U7 (lambda bodies) are the large-surface policy consumers.

**Recommendation for sequencing the decision:** land U1 (proves the builder), U3/U5 (rendered-column parity, the
determinism hardening that is valuable regardless of A/B), and U2/U8 (narrow, width-driven) first — these retire the
source-column floors and unify ownership **without** committing to A or B. Make the A/B call, then land U4/U6/U7 under
the chosen policy as reviewed rebaselines. U9 closes out `LayoutWidth`.

---

## Part 5 — Risks: the #163 re-introduction surface

The paths most likely to reintroduce #163-style cross-context oscillation, and why routing through the single ranked
engine mitigates each:

1. **Binary operand (pos 6).** The exact site of #119: keying an operand's arg-break on
   `methodCallArgumentsSpanMultipleLines` made the layout depend on the previous pass's incidental line shape
   (`BinaryExpressionPrinter.java:289`–`299`). **Mitigation:** U8 keeps the #119-hardened width+AST-only *gate* and only
   swaps the *delegate* to `bestFitting`, which measures at the rendered column and never reads source line breaks. The
   #119 fixture is the mandatory guard.
2. **Chain-with-breaking-final-arg (pos 3/4).** The `field-init-typelike-root-idempotence` / `seenProviders` oscillation
   (source-multiline → collapse; flat-source → argument-break) was the redesign's motivating case. **Mitigation:** the
   ranked arm builds **both** candidates from the AST on every input (`chainFanOut` is source-neutral, `Doc.java`
   contract), so the two passes rank the same two candidates — the fixpoint-by-construction argument
   (`convergence-redesign.md` "Idempotence guarantee"). Widening it (U2) must preserve this: never gate an arm's
   *existence* on source shape.
3. **Forced paths that pass the source-column floor (pos 1/2/5/9).** These decide breaks at a stale
   `range.begin.column` (`compactRootLineWidth` empty-prefix branch `:1954`). A reindented input measures at the wrong
   column → break on one pass, collapse on the next (the #137 family). **Mitigation:** U3/U5 activate `leftEdgePrefix`
   so the fit is measured at the true rendered column before any of these route through `bestFitting`; **do not** route
   a caller through the ranker before its prefix is activated (that ordering is the whole point of the dependency graph).
4. **Nested chains (pos 10).** `chainFanOut` renders its root at `LayoutContext.root()` (`:1248`), so a nested chain does
   **not** inherit the enclosing segment's prefix. If a future slice threads a prefix into nested chains, it must be the
   *rendered* prefix, not a reconstructed one, or the inner and outer passes will disagree. Left as an explicit
   non-goal here.

The unifying mitigation is structural: **one ranked node, both arms built from the AST, ranked at the live output
column** is idempotent-by-construction (the three-argument proof in `convergence-redesign.md`). The regression risk is
entirely in the **migration order** — routing a Z-path through the engine before its rendered-column prefix is active,
or gating an arm's existence on source shape. The byte-identical-first sequencing (U1 before any behavioral slice; U3/U5
before U4/U6) is what keeps each slice measurable against the corpus baseline.

## Relationship to existing proposals

| This doc | Existing | Relationship |
| --- | --- | --- |
| Part 1 map | `ARCHITECTURE.md` "Method call chains" (`:495`–`:680`) | This is the *inventory* view of the same machinery ARCHITECTURE describes prose-first; every cell cites the same code. |
| U1 (`chainFanOut` feeds Branch P) | `convergence-redesign.md` Mechanism 1 | The builder exists and feeds the two single-segment rankers; U1 extends it to the multi-segment fan the imperative ladder still owns. |
| U3/U5 (leftEdgePrefix activation) | `layout-decision-model.md` LDM-2f / C10; `convergence-redesign.md` slice 6 | The "activate `leftEdgePrefix` for the remaining callers" work the return (#190) and initializer slices started, generalized caller-by-caller. |
| U4/U6/U7 (callers → bestFitting) | `layout-decision-model.md` B8 pilot; `convergence-redesign.md` slices 4–5 | Completes the LDM-3 pilot from "single-segment only" to "every chain position," behind the A/B policy call. |
| U9 (retire LayoutWidth) | #220 / #190 C10 tail; `convergence-redesign.md` slice 6 | The last-consumer removal both name; unblocked once U3/U5 land. |
| A/B decision | `convergence-redesign.md` "Needs a product decision" | The same fan-when-fitting-vs-fan-when-over-width call, restated for the chain positions and mapped to which slices depend on it. |
