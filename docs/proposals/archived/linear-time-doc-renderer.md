> **Status: Implemented.** Landed on `main`: the S5 `fits`/`flatWidth` unification (`6e4f600a`), the bounded + memoized `DocWidths` measurement, and the M2a overhead reduction (`c5ca3644`). Archived 2026-07-14; retained as a provenance record.

# Linear-time renderer via width memoization and bounded `fits`

Status: Implemented — its S5 sub-step (the `fits`/`flatWidth` unification, section (a)) has **landed
on `main` in `6e4f600a`**; M2 adds the memoization (b) and bounded lookahead (c).

(Roadmap M2; absorbs S5 — "Collapse `fits` and `flatWidth` into one function.")

> **Implementation state:** `DocWidths` now owns a per-render measurement context
> with bounded `fits` and memoized flat-width measurement. Bounded fits stops once overflow or a hard
> line proves the group cannot fit; memoized widths are scoped to one render/explain pass so stale
> state cannot leak across renders.

> **Implemented performance note:** a local 50-pass no-diff macro run on a project with ~600 files
> showed the implemented M2 branch slower than `main` in central tendency (`main`: mean 3.342s /
> median 3.140s / p95 5.440s; M2: mean 3.718s / median 3.540s / p95 5.320s). The p95 numbers were
> noise-equivalent, but the mean/median regression is enough to track an explicit follow-up: keep the
> single measurement walker and per-render cache boundary, but reduce the implementation overhead.

## Summary

`DocRenderer` decides whether each `Group` renders flat or broken by measuring the flat width of its
subtree. That measurement is implemented by two near-identical recursive walks — `fits(...)` and
`flatWidth(...)` — neither of which is memoized and neither of which is bounded. As a result:

- Nested `Group`s re-measure overlapping subtrees, so the renderer degrades toward **O(n²)** on
  deeply nested documents (the common case for chained calls, nested generics, and builder-style
  expressions).
- `fits`/`flatWidth` are two structurally identical `switch`es over the same nine `Doc` variants.
  Adding an IR variant (roadmap B2 enriches the `Doc` IR) means editing both, and a divergence
  between them is a silent wrong-output bug. This is exactly the S5 cleanup.

`Doc` nodes are immutable records (`frmtr-core/src/main/java/dev/lanwen/frmtr/doc/Doc.java`), so a
node's flat width is a pure function of the node and can be computed bottom-up **once** and reused.
Combined with a Lindig-style **bounded** `fits` that stops at the first hard line or as soon as the
remaining budget goes negative, this takes the per-`Group` measurement from "walk the whole subtree"
to "walk at most `w` columns of it," where `w` is the line width. The asymptotic target is
**O(n²) → O(n·w)**, and in practice the bounded lookahead is usually far cheaper than `w` because it
stops at the first line break.

This is a change contained entirely to `DocRenderer` (and possibly a tiny addition to `Doc`); it must
be **behavior-preserving** — byte-for-byte identical output — and it should be validated with the M1
benchmark harness, not asserted. See the honest impact note at the end: for a Java formatter, parsing
frequently dominates rendering, so the headline win here is robustness against pathological nesting
and a cleaner IR contract, with raw throughput improvement to be confirmed by M1.

## Current algorithm (annotated)

All line/column numbers below refer to
`frmtr-core/src/main/java/dev/lanwen/frmtr/doc/DocRenderer.java` as it stands today.

### The render walk and where `fits` is called

`render(...)` is a single recursive walk over the `Doc` tree carrying `indent` and a `Mode`
(`FLAT`/`BREAK`). The only place a decision is made is the `Group` case (lines 43-46):

```java
case Doc.Group group -> {
    Mode next = fits(group.doc(), indent, options.lineWidth() - column) ? Mode.FLAT : Mode.BREAK;
    render(group.doc(), indent, next);
}
```

So **every `Group` encountered during rendering triggers one `fits` call** before its body is
rendered. Crucially, `render` then descends into `group.doc()`, where it may encounter more `Group`s,
each of which triggers its own `fits` call. The `fits` of an outer group and the `fits` of an inner
group measure overlapping regions of the same tree.

### `fits` (lines 52-83)

```java
private boolean fits(Doc doc, int indent, int remaining) {
    return fits(doc, indent, remaining, Mode.FLAT);
}

private boolean fits(Doc doc, int indent, int remaining, Mode mode) {
    if (remaining < 0) {
        return false;
    }
    return switch (doc) {
        case Doc.Text text -> text.value().length() <= remaining;
        case Doc.Concat concat -> {
            int rest = remaining;
            boolean ok = true;
            for (Doc child : concat.docs()) {
                int width = flatWidth(child, indent, mode);   // <-- re-walks each child fully
                if (width < 0 || width > rest) {
                    ok = false;
                    break;
                }
                rest -= width;
            }
            yield ok;
        }
        case Doc.Line _ -> remaining >= 1;
        case Doc.SoftLine _ -> true;
        case Doc.HardLine _ -> false;
        case Doc.Indent indented -> fits(indented.doc(), indent + 1, remaining, mode);
        case Doc.Group group -> fits(group.doc(), indent, remaining, Mode.FLAT);
        case Doc.IfBreak conditional -> fits(mode == Mode.BREAK ? conditional.breakDoc() : conditional.flatDoc(), indent, remaining, mode);
        case Doc.Label label -> fits(label.doc(), indent, remaining, mode);
    };
}
```

`fits` already has *some* short-circuiting: the `remaining < 0` guard (line 57), the per-child break in
the `Concat` loop when `width > rest` or `width < 0` (lines 67-70), and `HardLine -> false` (line 77).
But the short-circuit is undermined by the line it relies on: **for a `Concat`, it does not walk the
children incrementally — it calls `flatWidth(child, ...)` on each child, and `flatWidth` measures that
child's *entire* subtree to a full integer width before `fits` even compares it to the budget.** So a
single `Concat` child that is a huge subtree is measured in full even if the very first text inside it
already overflows the line.

### `flatWidth` (lines 85-107)

```java
private int flatWidth(Doc doc, int indent, Mode mode) {
    return switch (doc) {
        case Doc.Text text -> text.value().length();
        case Doc.Concat concat -> {
            int width = 0;
            for (Doc child : concat.docs()) {
                int childWidth = flatWidth(child, indent, mode);
                if (childWidth < 0) {
                    yield -1;                                  // hard line poisons the width
                }
                width += childWidth;
            }
            yield width;
        }
        case Doc.Line _ -> 1;
        case Doc.SoftLine _ -> 0;
        case Doc.HardLine _ -> -1;
        case Doc.Indent indented -> flatWidth(indented.doc(), indent + 1, mode);
        case Doc.Group group -> flatWidth(group.doc(), indent, Mode.FLAT);
        case Doc.IfBreak conditional -> flatWidth(mode == Mode.BREAK ? conditional.breakDoc() : conditional.flatDoc(), indent, mode);
        case Doc.Label label -> flatWidth(label.doc(), indent, mode);
    };
}
```

This is the same `switch` as `fits`, returning a width instead of a boolean. `HardLine` returns `-1`
and that `-1` propagates up through `Concat` (lines 92-94) to mean "unbounded / does not fit flat."
Note that `indent` is threaded through but never actually used to compute a width — flat width does
not depend on indentation (a flat render emits no newlines, so indentation is never applied). The only
parameter that genuinely affects the result is `mode`, and only via the `IfBreak` case.

### Worst-case analysis

Consider a left- or right-nested chain of groups, the natural shape of `a.b().c().d()...` or nested
generics / nested `delimited(...)` envelopes (see `Doc.delimited`, which nests a `group` inside a
`group`):

```
Group(Concat[ ... , Group(Concat[ ... , Group(Concat[ ... ]) ]) ])
```

Let `n` be the number of nodes and let the nesting depth be `d` (in these chains `d` is proportional
to `n`). During `render`:

1. The outermost `Group` calls `fits` on its body. `fits` reaches the body's `Concat` and calls
   `flatWidth` on each child. One of those children is the next nested `Group`; `flatWidth` walks it
   **completely** (the `Group` case at line 103 recurses into the whole subtree).
2. `render` then descends and hits that nested `Group`, which calls `fits` again, which again calls
   `flatWidth` over *its* body — re-walking the same nodes the outer measurement already visited.
3. Repeat at every nesting level.

Each of the `d` groups triggers a measurement whose cost is proportional to the size of its subtree,
and the subtrees are nested, so the total work is `Σ (size of subtree at level k)` ≈ `n + (n-1) + ... `
which is **Θ(n²)** for a linear chain of depth `d ≈ n`. The redundancy is pure: `flatWidth` recomputes
the same node widths over and over because nothing is cached, and `fits` does not stop early because
it asks `flatWidth` for a complete width before testing the budget.

The constant factor is also higher than necessary: every `Group` measurement does the full
`flatWidth` arithmetic even when the line is already overflowing two characters in.

## Proposed design

Three changes, layered. (a) and (b) are the S5 + memoization core; (c) is the bounded-lookahead
optimization that makes the asymptotics hold.

### (a) Unify `fits` and `flatWidth` into one measurement function [implemented]

`fits` and `flatWidth` answer the same question with different return types. Fold them into one
internal cost-walker whose currency is "columns remaining" and whose only outcomes are *fits within
budget*, *overflows*, or *hit a hard line*. The cleanest formulation that subsumes both is a single
function that consumes a remaining budget and reports how much budget is left (or a sentinel for
"definitely does not fit flat"):

```java
// implemented — replaces the previous separate fits(...) and flatWidth(...) walkers
// Returns the budget remaining after laying `doc` out flat starting from `remaining`.
// A negative result means "overflowed or contains a hard line": does NOT fit flat.
private int measureFlat(Doc doc, int remaining, Mode mode) {
    if (remaining < 0) {
        return remaining;          // already overflowed — propagate, do not descend
    }
    return switch (doc) {
        case Doc.Text text   -> remaining - text.value().length();
        case Doc.Line _      -> remaining - 1;
        case Doc.SoftLine _  -> remaining;            // 0 width when flat
        case Doc.HardLine _  -> NO_FIT;               // sentinel < any reachable remaining
        case Doc.Indent in   -> measureFlat(in.doc(), remaining, mode);
        case Doc.Group g     -> measureFlat(g.doc(), remaining, Mode.FLAT);
        case Doc.IfBreak c   -> measureFlat(mode == Mode.BREAK ? c.breakDoc() : c.flatDoc(), remaining, mode);
        case Doc.Label l     -> measureFlat(l.doc(), remaining, mode);
        case Doc.Concat con  -> {
            int rest = remaining;
            for (Doc child : con.docs()) {
                rest = measureFlat(child, rest, mode);
                if (rest < 0) {                        // bounded: stop at first overflow / hard line
                    break;
                }
            }
            yield rest;
        }
    };
}
```

Then the two former entry points become one-liners over the same walker:

```java
// implemented
private boolean fits(Doc doc, int remaining) {
    return measureFlat(doc, remaining, Mode.FLAT) >= 0;
}
```

The `Group` decision site (lines 43-46) becomes
`Mode next = fits(group.doc(), options.lineWidth() - column) ? Mode.FLAT : Mode.BREAK;`.

Notes:

- `indent` is dropped from the measurement entirely. The current code threads `indent` through both
  functions (lines 56, 85, and the `+1` at lines 78 and 102) but **never reads it** to produce a
  width — flat layout emits no newlines, so indentation never applies. Removing it shrinks the
  signature and removes a parameter that could otherwise pollute a cache key. (This is a behavior-
  preserving simplification, not a behavior change; verify with a grep that no other caller passes a
  meaningful indent.)
- A single sentinel `NO_FIT` (e.g. `Integer.MIN_VALUE / 2`, far below any reachable `remaining`)
  replaces the dual `-1`/`false` "hard line" encoding. The `remaining < 0` early guard then naturally
  swallows both "overflowed" and "hit a hard line."
- This is the S5 deliverable: one `switch` over `Doc`, so a new IR variant (B2) is added in exactly
  one place.

### (b) Memoize flat width on the immutable `Doc` [implemented]

Because `Doc` records are immutable and flat width is a pure function of the node, the *unbounded*
flat width of any node can be cached and reused across every `fits` that crosses it. Three options:

1. **`IdentityHashMap<Doc, Integer>` cache held by the renderer (recommended).** Keyed by node
   identity (cheap, no `equals`/`hashCode` over deep trees), populated lazily on first measurement,
   discarded when the `DocRenderer` instance is done. Pros: zero changes to `Doc`; cache lifetime is
   bounded to a single render; identity keys avoid the cost and risk of structural equality on large
   trees. Cons: a hash lookup per visited node; memory proportional to the number of *distinct* nodes
   measured.
   - Subtlety to respect: a memoized full width and the bounded short-circuit (c) interact. The cache
     must store the *complete* flat width of a node (or the `NO_FIT` sentinel), computed without a
     budget cap, so that a partial/aborted bounded walk never poisons the cache with a truncated
     value. The clean split is: bounded `fits` walks top-down and may short-circuit; whenever it needs
     a child's complete contribution it consults/fills the unbounded-width cache for that child. Hard-
     line-bearing subtrees cache as the `NO_FIT` sentinel.

2. **A computed field on each record.** Rejected: Java records cannot carry a lazily-initialized
   mutable field — the canonical constructor fixes all components, and a record's "fields" are its
   components. Forcing eager bottom-up width computation into the constructor would mean `Doc.concat`,
   `Doc.group`, etc. compute widths for nodes that may never be measured, and it couples the IR to a
   renderer concern. It also can't represent the mode-dependence of `IfBreak` (see below) in a single
   field. Rejected.

3. **A non-record wrapper (e.g. a `Measured(Doc inner, int flatWidth)` decorator) inserted at build
   time.** Rejected for now: it changes the IR shape that the rest of the formatter and `debugDoc`
   observe, contradicts S5's "contained to `DocRenderer`" scope, and breaks the clean `sealed
   interface` pattern-match exhaustiveness.

**Recommendation: option 1, an `IdentityHashMap` width cache local to the `DocRenderer` instance.** It
keeps the optimization entirely inside the renderer, requires no change to the immutable `Doc` IR,
matches the records-can't-hold-lazy-fields constraint, and is trivially discarded per render so there
is no cross-render staleness.

**The `IfBreak` / mode caveat.** Flat width is *not* purely a function of the node: the `IfBreak` case
in both current functions branches on `mode` (line 80 / line 104). The width of a subtree containing
an `IfBreak` differs between `FLAT` and `BREAK` mode. In practice, `fits` always enters in `FLAT`
mode and only the `IfBreak` case can preserve a `BREAK` mode downward — but to be safe and correct,
the cache must either (i) key on `(node, mode)`, or (ii) only cache subtrees provably free of
`IfBreak`, or (iii) restrict caching to the `FLAT`-mode width (the mode actually used by `fits`).
Option (iii) is simplest and matches today's behavior, since `fits` is only ever invoked to test flat
fitting; document this assumption explicitly and add a test that an `IfBreak` inside a measured group
still produces identical output.

### (c) Bounded `fits` lookahead [implemented]

The `measureFlat` walker in (a) already encodes the bound: it threads the *remaining* budget through
the walk and returns early the instant `remaining < 0` (the guard at the top, plus the `break` in the
`Concat` loop). This is the Lindig "does it fit in the remaining `w` columns" formulation rather than
"what is the total width." Combined with `HardLine -> NO_FIT`, a `fits` query touches at most `w + 1`
columns of leaf text before it can answer, regardless of how large the subtree beyond that is.

The memoized unbounded width (b) and the bounded walk (c) cooperate: the bound makes the *common* case
(group overflows early) cheap without consulting the cache at all; the cache makes the case where a
group genuinely does fit cheap by not re-measuring shared subtrees across nested groups. Together they
give the **O(n·w)** target — each of the `n` nodes is measured at most once to its full width (cached),
and each `fits` query is bounded by `w`.

## Behavior-preservation argument

The change must produce byte-for-byte identical output. The argument:

- **Same decision function.** The only place rendering branches on measurement is the `Group` case
  (lines 43-46), and it branches on the boolean `fits(...) >= remaining-budget`. The new
  `fits(doc, remaining)` returns `true` exactly when `measureFlat(doc, remaining, FLAT) >= 0`, i.e.
  exactly when the flat layout consumes `<= remaining` columns and contains no hard line. That is the
  same predicate the current `fits` computes — the current `fits` returns `false` iff some prefix of
  the flat layout exceeds `remaining` (the `width > rest` break) or a hard line is present
  (`HardLine -> false`, `width < 0`). The bound only changes *when* the walk stops, not *what answer*
  it gives: a budget that goes negative can never recover (widths are non-negative; `SoftLine`/`Group`
  add zero or more), so stopping early is safe.
- **Hard line semantics preserved.** Today `HardLine` yields width `-1` in `flatWidth` and `false` in
  `fits`, and that `-1` poisons any enclosing `Concat` width (lines 92-94, 67). The new `NO_FIT`
  sentinel reproduces this: a hard line anywhere in the measured-flat region forces `fits` to
  `false`, so a group containing a hard line always renders in `BREAK` mode, exactly as today.
- **`Label` transparency preserved.** `Doc.Label` is transparent in `render`, `fits`, and `flatWidth`
  today (lines 48, 81, 105 each just recurse into `label.doc()`). The unified `measureFlat` keeps the
  `Label -> measureFlat(l.doc(), ...)` recursion, so labels remain measurement-transparent and
  provenance-only.
- **`Concat` flattening unaffected.** `Doc.concat` already flattens nested `Concat` and drops `EMPTY`
  (`Doc.flattenConcat`, lines 92-103), so the measured tree shape is the same one the renderer walks;
  memoization keys on those exact nodes.
- **Memoization is a pure cache.** Cache values equal the value the recursive walk would have returned
  (subject to the `IfBreak`/mode handling above), so reading from the cache is indistinguishable from
  recomputing. Identity keying is sound because `Doc` records are immutable — a node's structure
  cannot change after it is keyed.

A golden-output regression run (format the existing fixture corpus and diff) plus the B3
AST-equivalence check are the concrete guards.

## Micro-benchmark plan (ties to M1)

_Historical — the forward-looking JMH/macro benchmark plan is now owned by the still-open M1 item
([performance-followups-from-jfr.md](../performance-followups-from-jfr.md)); the M2a measurement note
records that paired macro timing did not show a reliable whole-CLI speedup._

## Risks

- **Cache memory.** The `IdentityHashMap` holds an entry per distinct measured node; on a very large
  file this is O(measured nodes) extra memory for the duration of one render. Bounded by the document
  size and freed when the renderer instance is discarded. If this proves significant, restrict caching
  to nodes above a size threshold, or to `Group` bodies only (the only subtrees re-measured across
  nesting).
- **`IfBreak` mode-dependence.** As described in (b), flat width is not strictly node-pure because of
  `IfBreak`. The mitigation (cache `FLAT`-mode width only, or key on `(node, mode)`) must be chosen
  deliberately and covered by a test, or the cache can return a wrong width and silently change output.
- **`Label` transparency.** Must remain transparent in the unified walker; a `Label` that accidentally
  contributed nonzero width or blocked recursion would change break decisions. Covered by keeping the
  recurse-through behavior and by golden tests.
- **Sentinel arithmetic.** `NO_FIT` must be chosen so that `NO_FIT` minus any reachable text length
  cannot wrap around to a non-negative value; use a value like `Integer.MIN_VALUE / 2` and never add
  positive width to an already-negative `remaining` (the top guard prevents descent once negative).
- **Bound vs. cache interaction.** A bounded walk must never write a truncated width into the unbounded
  cache (see (b)). Keep the two concerns separated: bounded top-down `fits`, unbounded cached width.
- **Correctness over speed.** If any of the above cannot be made provably behavior-preserving, ship
  S5 (the unification, (a)) alone — it is valuable on its own as a maintainer win — and defer
  memoization.

## Success metrics

- Worst-case nested-group render time grows roughly **linearly** with depth instead of quadratically
  (visible as a flattened JMH curve across depths).
- **Byte-for-byte identical** output on the fixture corpus and B3 AST-equivalence check — zero diffs.
- No regression (within noise) on shallow/wide documents from the cache overhead.
- The renderer is a single measurement `switch` over `Doc` (S5): adding a `Doc` variant requires
  editing one function, not two.
- A published render-only before/after number from the M1 harness.

## Honest impact note: parsing usually dominates

For a Java formatter, lexing + parsing the source into an AST frequently costs more than rendering the
resulting `Doc`. The M1 full-pipeline benchmark exists precisely to reveal this split. This proposal
should therefore be framed as: **(1)** eliminating a genuine **O(n²) pathology** that can blow up on
adversarial or machine-generated nesting (robustness, not just average speed), and **(2)** a
maintainer-facing IR cleanup (S5). The average-case wall-clock win on typical hand-written files may
be modest if parsing dominates — that is an acceptable and expected outcome, and M1 should report it
honestly rather than the proposal over-claiming a headline speedup. The asymptotic guarantee and the
single-`switch` contract are the durable wins; the throughput delta is whatever M1 measures.

## Implemented follow-up: reduce measurement overhead

M2a is implemented in `dc9bd4c`. `DocWidths.Measurement` preserves the important maintainability
property: `fits` and `flatWidth` route through one canonical per-variant `Doc` measurement walker.
The implementation makes that shared walker cheaper on normal documents by replacing per-node
`Budget` / `MeasureResult` record traffic with primitive sentinel returns and fast-pathing cached
complete widths before bounded descent.

Follow-up measurement is now captured in
[`performance-followups-from-jfr.md`](../performance-followups-from-jfr.md): paired macro timing did not
show a reliable whole-CLI speedup from M2a, while denser allocation sampling confirmed that the
targeted `DocWidths$Budget` / `DocWidths$MeasureResult` allocation signal disappeared. Future
renderer work should be evidence-driven: only add overflow-bound caching if repeated bounded overflow
work still shows up after the broader raw-source, comment-query, parser-lifecycle, and startup
investigations are separated. More advanced overflow-bound caching complicates cache semantics and is
not needed to preserve the one-switch contract.

## Scope and non-goals

- The implemented boundary is `DocWidths` for width measurement and bounded `fits`, with
  `DocRenderer` consuming that measurement result to choose flat or broken rendering.
- The `Doc` IR and public API are unchanged.
- Absorbs S5: `fits` and `flatWidth` are unified through the `DocWidths` measurement path, so S5
  needs no separate doc.
- Remaining non-goals: changing formatter output/options, changing the `Doc` public API, or
  implementing the M1 benchmark harness here.
