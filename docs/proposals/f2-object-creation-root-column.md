# F2 / #190 — object-creation-rooted chain bodies hug their opener at the true rendered column

## Problem

A fanned chain selector whose sole argument is an expression lambda with an
object-creation(-rooted-chain) body could render **over-width**, because the layout that would
hug the body's opener was *withheld* and the chain fell back to a shape that drops the whole
lambda flat onto one continuation line.

Concrete corpus case (`kafka FetchResponse.java`):

```java
// before — dropped flat, 130 cols (over 120)
topicResponse.partitions()
        .forEach(
            partition -> responseData.put(new TopicPartition(name, partition.partitionIndex()), partition)
        );

// after — opener hug, 0 over-width
topicResponse.partitions()
        .forEach(partition -> responseData.put(
            new TopicPartition(name, partition.partitionIndex()),
            partition
        ));
```

## Root cause (as it stands after the D3 flip already merged)

The hug-admission gate `ExpressionLambdaArgumentLayout.plan` now measures the flat body at
`Math.max(fixedProbe, columnWidth)` — the D3 flip that consults the threaded segment column is
already on `main`. But for a selector nested inside a *fanned argument list* the threaded
`columnWidth` (`layoutWidth::continuationStatement`, and even the `fannedSelectorColumnWidth`
`nodeIndentWidth + indentUnit*2` approximation) still **under-counts the real column** — the
selector renders at the live `Doc.indent` depth (column 32 in the repro), which no static probe
can recover because `LayoutContext` carries no argument-nesting depth. So `plan` reads the flat as
fitting at the shallow column and withholds; the only remaining hug path is the *direct opener
rescue* in `ChainSelectorLambdaLayout`.

That rescue was then blocked a second time: `argumentOnlyFansItself` treated **any**
`ObjectCreationExpr` argument (`new TopicPartition(name, partition.partitionIndex())`) as a
self-fanning argument that must reach the chain printer's own fan — so
`bodyOpenerHugArgumentsRenderFlatSafely` returned false, the direct opener yielded, and the selector
dropped its lambda flat through `brokenMethodCallSegment`.

## Why a static "true column" is not reachable (the premise correction)

The original plan was to *thread the true column* into the gate. First-hand tracing showed this is
**architecturally impossible for the general case**: `LayoutContext` carries a text
`leftEdgePrefix` and a `trailingContent`/`leadingBreak` flag but **no argument-nesting depth**, and
`LayoutWidth.nodeIndentWidth` counts only enclosing type/block indentation. For a chain nested
inside a fanned argument list the rendered column is a **render-time** fact. So "measure at the true
column" cannot be a static swap — it has to be realized at render time.

## Realization: build the width-safe hug arm; let the live ranker choose

The enclosing selector is already a `conditionalGroup([flatBody, hugBody])` sitting inside a
`bestFitting` chain fan, and `DocWidths.betterThan` is **fit-first** — a fitting alternative always
beats an overflowing one regardless of line count (`DocWidths.java:166`). So the flat arm is already
measured live at the true column; the only defect was that `hugBody` was built as the *drop-flat*
`brokenMethodCallSegment` instead of a width-safe opener hug. The fix therefore does not chase a
static column — it ensures a **width-safe, forced-break hug arm is offered**, and lets the fit-first
ranker pick it exactly when the flat arm overflows at the live column.

Shipped change (surgical): relax `argumentOnlyFansItself` so a **bare `ObjectCreationExpr`**
argument is no longer treated as self-fanning. Rendered on its own broken continuation line inside
the opener it either fits flat or breaks its own argument list — both width-safe. Only an
object-creation-**rooted-chain** (`new X().setA(...).setB(...)`) or a **multi-selector** method-call
chain still wants the chain printer's own one-selector-per-line fan and stays excluded through the
`MethodCallExpr` branch. That single relaxation re-enables the direct opener rescue for exactly the
`new X(...)`-argument family.

## Idempotence — the fixpoint properties (both `KNOWN_NON_IDEMPOTENT` allowlists stay empty)

1. **Pass-invariant.** The hug arm and its selection are a pure function of the AST + live render
   column, never a stale source column. The flat text and its rendered column are pass-invariant, so
   hug-vs-flat is a fixpoint.
2. **Forced break.** The opener hug carries a hard line, so it is a valid `conditionalGroup`
   fallback, never a flat one-liner redundant with the flat arm.
3. **Width-safe.** The opener hug breaks the body call's argument list one per line; each line is
   ≤ the flat overflow, so it never introduces a *new* over-width.
4. **Keep withholding self-fanning shapes.** Object-creation-rooted-chain / multi-selector-chain
   arguments still yield to the generic path so they reach the chain printer's own fan.

## Validation (measured)

- Full `./gradlew test` green; a new fixture
  `lambda-body-call-object-creation-argument-opener` pins the reshape (base drops flat at 128; fix
  hugs, 0 over-width; idempotent + AST-equivalent).
- 5-corpus harness (base `f75268ea` vs fix), kafka + camel:
  - reshaped files: 1 (kafka) + 3 (camel) — all the same opener-hug tightening.
  - over-width lines: **−1 (kafka), −3 (camel)**; **0 files with more over-width**.
  - comment markers: **delta 0** (no comment drops).
  - AST-equivalence (`--verify`): **0 failures**.
  - idempotence: **0 NEW non-idempotent** files (pre-existing oscillators unchanged).

## Deferred follow-ups (adjacent families, rare, need new plumbing)

- **Bare object-creation body** (`​.map(p -> new TopicPartition(a, b))`, `Nc` repro): withheld by
  `plan`'s shallow-column probe with no direct-opener rescue for a *bare* `ObjectCreationExpr` body.
  Needs an `objectCreationBodyWithOpener` helper mirroring `methodCallBodyWithOpener`.
- **Opener-overflows-needs-fan** (`​.map(t -> new OffsetFetchRequestTopic().setName(...)....)`): the
  chain head itself over-widths on the arrow line, so the correct shape is the object-creation-root
  *fan* (root on the arrow line, each selector fanned below), not the opener hug.
