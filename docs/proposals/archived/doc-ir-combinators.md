> **Status: Implemented.** Landed on `main`: the four IR primitives (`LineSuffix`, `BreakParent`, `Fill`, `ConditionalGroup`) plus group identity in `Doc`, with renderer/width/debug/explain support. Archived 2026-07-14; retained as a provenance record. See [Outcomes](#outcomes) for what shipped and what was declined (the chain-collapse goal).

# Enrich the Doc IR with the combinators printers are faking today

Status: Implemented — see [Outcomes](#outcomes)

## Outcomes

All four proposed primitives landed on `main`, plus group identity:

- **`LineSuffix`**, **`BreakParent`**, **`Fill`**, **`ConditionalGroup`**, and the optional `Group`/`IfBreak` `groupId`
  are implemented in `Doc` with renderer (`DocRenderer`), width (`DocWidths`), debug (`DocDebugRenderer`), and explain
  (`DocExplainRenderer`) support. `Fill` validates its alternating shape (rejecting a non-empty even-length list) and
  `ConditionalGroup` rejects an empty alternative list; both have focused `DocRendererTest` coverage.
- **`LineSuffix` was adopted broadly**, retiring the trailing-comment placement coupling as planned: the enum constant
  trailing comment is now a `Doc.lineSuffix(...)` (see `EnumConstantComments` / `EnumDeclarationPrinter`), and
  `RecordDeclarationPrinter`, `CommentedExpressionListPrinter`, `ControlConditionPrinter`, `StatementPrinter`, and
  `MethodCallChainPrinter` route their trailing comments through it as well. **`BreakParent`** has one consumer
  (`RecordDeclarationPrinter`).
- **`Fill`'s first (and so far only) Java-printer consumer is `ThrowsClausePrinter`**, which greedily packs an overflowing
  `throws ...` exception list across continuation lines instead of emitting it as one unbreakable `Doc.text` blob.
- **The chain-collapse goal was NOT achieved and is not achievable.** Replacing `MethodCallChainPrinter`'s `Optional<Doc>`
  dispatch (and the `LayoutWidth` probes that feed it) with `ConditionalGroup` is neither byte-identical nor expressible:
  - **Not byte-identical.** The chain probes measure *flattened strings* at fixed-indent baselines and at source columns
    (`LayoutWidth`, `range.begin.column`), whereas `ConditionalGroup` measures *flat fit at the actual output column*
    via `DocWidths`. The two measurements disagree, so swapping in `ConditionalGroup` would move break points and change
    golden output.
  - **Not expressible.** The chain ranks *multiple broken layouts* and selects among them on structural/source
    predicates; `ConditionalGroup` is Prettier-shaped — N flat candidates plus exactly one final broken fallback,
    chosen purely by flat fit, with no predicate gating. It cannot rank two broken layouts against each other.
  - Therefore `MethodCallChainPrinter`'s `Optional<Doc>` dispatch and `LayoutWidth` **stay**.
- **The throws clause was evaluated as a `ConditionalGroup` consumer and also rejected, for the same reason.** Replacing
  `ThrowsClausePrinter`'s inline-vs-broken width probe with `Doc.conditionalGroup([inline-flat, broken-fill])` is not
  behavior-preserving: the hand-rolled probe measures the signature width **including the trailing body opener**
  (`" {"` / `";"`) the caller emits *after* the throws clause, but a `ConditionalGroup` alternative is sized by its own
  flat width at the output column and cannot see that trailing same-line content. The swap would let a signature that
  fits without the brace but overflows with it stay inline, emitting a line 1–2 columns over the limit — it happens to
  leave the current fixture corpus byte-identical, but the behavior differs. This is the same
  node-local-fit-vs-trailing-context limitation as chain-collapse.
- **`ConditionalGroup` therefore stays an additive, validated foundation primitive with no byte-identical Java-printer
  consumer.** Its "at least one alternative" invariant is now enforced at the type level (a compact constructor on the
  `ConditionalGroup` record), not just in the `Doc.conditionalGroup(...)` factory, so a direct in-package
  `new ConditionalGroup(...)` cannot bypass it.
- **Enum-constant `Fill` packing was declined.** Reflowing enum constants to pack as many per line as fit is an
  opinionated layout that conflicts with frmtr's source-shape-preservation bias (it would discard the author's
  one-constant-per-line intent), so it was not pursued. `LineSuffix` still removes the enum comma/comment coupling
  independently of any packing decision.

## Summary

The formatter's document IR (`frmtr-core/src/main/java/dev/lanwen/frmtr/doc/Doc.java`) has nine primitives:
`Text`, `Concat`, `Line`, `SoftLine`, `HardLine`, `Indent`, `Group`, `IfBreak`, `Label`. Mature pretty-printers in the
Wadler/Lindig lineage (notably Prettier's doc language) provide several more combinators that `frmtr` printers
currently hand-roll: deferring trailing content to end of line (`lineSuffix`), packing as many items per line as fit
(`fill`), forcing an enclosing group to break (`breakParent`), and trying ranked layouts (`conditionalGroup`).

Because the IR cannot express these, printers reach outside the Doc model and measure flattened *strings* against the
line width via `LayoutWidth` (`currentIndentedWidth`, `blockStatement`, `continuationStatement`) before they have a
final `Doc`, then branch on `Optional<Doc>` fallbacks. Trailing comments are woven inline with `Doc.concat(...)` plus
ad-hoc comma re-ordering and next-sibling look-ahead. This is the machinery that silently dropped enum separators (see
roadmap `docs/proposals/README.md` lines 81-85) and that `EnumDeclarationPrinter` still carries today.

This proposal adds four primitives — `LineSuffix`, `BreakParent`, `Fill`, `ConditionalGroup` — plus group identity so
`IfBreak` can target a named group. Each is additive: existing docs keep rendering identically. The recommended first
step is `LineSuffix`, which retires the largest and most bug-prone body of special-case code (trailing-comment
placement) with the least renderer risk.

This is roadmap item **B2**. It pairs with **B1** (source-shape policy) and is de-risked by **B3** (AST-equivalence
safety net); it also shares the renderer with **S5/M2** (folding `fits`/`flatWidth`, linear-time render). See
[Relationship to B1, B3, and S5/M2](#relationship-to-b1-b3-and-s5m2).

## Current IR and its gaps

### The IR (existing)

`Doc.java` (lines 105-121) defines the sealed permitted set. The renderer
(`frmtr-core/src/main/java/dev/lanwen/frmtr/doc/DocRenderer.java`) interprets it in two modes, `FLAT` and `BREAK`
(lines 136-139), through three mutually-recursive switches:

- `render(Doc, indent, mode)` (lines 25-50) emits text and newlines.
- `fits(Doc, indent, remaining, mode)` (lines 56-83) decides whether a `Group` can stay flat. `HardLine` yields
  `false` (line 77), which is the *only* current mechanism by which a nested hard break forces an enclosing group to
  break.
- `flatWidth(Doc, indent, mode)` (lines 85-107) returns the flat width, with `HardLine` returning `-1` (line 101) to
  poison any flat measurement that contains it.

A `Group` (lines 43-46) decides its mode locally: `fits(group.doc(), indent, lineWidth - column)`. Groups have **no
identity** — `IfBreak` (line 47) reads the *ambient* `mode` argument, not the mode of a specific group. `Label` (lines
48, 81, 105) is pure provenance and is transparent to width and rendering.

The gaps, mapped to roadmap B2:

1. **No `lineSuffix`.** Trailing comments must be emitted inline at the moment the owning node is printed; there is no
   way to say "park this text, flush it just before the next newline."
2. **No `fill`.** "Pack greedily, break only where needed" has to be precomputed by measuring strings.
3. **No `breakParent`.** "Break the enclosing group because a child broke" only happens as a side effect of a literal
   `HardLine` poisoning `fits`. A printer that wants to break a parent for a semantic reason (a child group broke, a
   comment is present) must inject a `HardLine` or probe widths.
4. **No `conditionalGroup`.** "Try layout A, else B, else C" is emulated by chains of `Optional<Doc>` returns gated on
   `LayoutWidth` probes.

### Width probing instead of an expressive IR

`LayoutWidth` (`frmtr-core/src/main/java/dev/lanwen/frmtr/java/LayoutWidth.java`) measures a *flattened string* at a
*fixed* indentation baseline:

```java
int currentIndented(String text) {
    return options.indentUnit().length() + text.length();   // line 28-29
}
int blockStatement(String text) {
    return (options.indentUnit().length() * 2) + text.length();   // line 35-36
}
```

It does not know the renderer's actual `column`, does not understand nested groups, and operates on text the printer
must produce *before* the `Doc`. At least 23 printers consume `currentIndentedWidth` and ~12 consume the
`blockStatement` probe (per `grep` across `frmtr-core/src/main/java/dev/lanwen/frmtr/java`). Representative emulations:

- **`fill`** — `ArrayExpressionPrinter.nestedArrayRowsShouldBreak()` (≈ lines 288-290) probes each row's compact form
  and breaks *all* rows if *any* overflows; `arrayCreation()` (≈ lines 94-99) tries a compact string and falls back to
  a structured `Doc`. `ThrowsClausePrinter` (≈ lines 75-82) computes two different width probes to decide whether the
  `throws` clause stays on the signature line or moves to a continuation. `MethodCallChainPrinter` packs chain segments
  in a `for` loop that probes width per candidate (≈ lines 144-159).
- **`conditionalGroup`** — `MethodCallPrinter.methodCall(...)` (≈ lines 173-211) is a layered `Optional<Doc>` chain:
  try suffixed-enclosed, else huggable lambda, else …; first non-empty wins. `LambdaExpressionPrinter` (≈ lines
  152-166) tries inline, then parameter-break inline, then method-call opener, then broken, each gated on a width
  probe. `CallableSignaturePrinter.parametersBreak(...)` (≈ lines 149-152) decides parameter breaking from a flat-width
  probe.
- **"huggable"** — `LambdaExpressionPrinter.huggableBlockLambdaArguments(...)` (≈ lines 463-487) accepts an injected
  `ToIntFunction<String> firstLineWidth` and returns `Optional.empty()` when the first line overflows;
  `VariableInitializerLayout` (≈ lines 742-749) injects a closure carrying the field-declaration prefix so the probe
  matches the real left edge. This injected-probe pattern exists precisely because the IR cannot decide layout from the
  real column.
- **`breakParent`** — there is no primitive. The effect is achieved only by emitting a literal `HardLine`, relying on
  `fits` returning `false` (DocRenderer line 77) / `flatWidth` returning `-1` (line 101). No `containsHardLine` /
  `forceBreak` predicate exists; the propagation is implicit and un-targeted.

### The motivating example: enum trailing comments

`EnumDeclarationPrinter` (`frmtr-core/src/main/java/dev/lanwen/frmtr/java/EnumDeclarationPrinter.java`) is the vivid
case the roadmap calls out. A trailing comment on an enum constant is not attached to that constant by JavaParser — it
may be the constant's own comment, a contained body comment, the *next* constant's comment, or an owner orphan comment.
`enumConstantTrailingComment(...)` (lines 577-610) tries all four sources in order:

```java
private Doc enumConstantTrailingComment(EnumDeclaration owner, EnumConstantDeclaration declaration,
        EnumConstantDeclaration next) {
    Doc ownTrailing = declaration.getComment()
            .filter(comment -> CommentIndex.startsAfterNodeOnSameLine(declaration, comment))
            .map(comments::comment).orElse(Doc.EMPTY);
    if (ownTrailing != Doc.EMPTY) { return ownTrailing; }
    Doc containedTrailing = Doc.concat(declaration.getAllContainedComments().stream()
            .filter(comment -> CommentIndex.startsOnEndLine(declaration, comment))
            .map(comments::comment).filter(comment -> comment != Doc.EMPTY).toList());
    if (containedTrailing != Doc.EMPTY) { return containedTrailing; }
    if (next != null) {
        Doc nextTrailing = next.getComment()
                .filter(comment -> CommentIndex.startsAfterNodeOnSameLine(declaration, comment))
                .map(comments::comment).orElse(Doc.EMPTY);
        if (nextTrailing != Doc.EMPTY) { return nextTrailing; }
    }
    if (owner == null) { return Doc.EMPTY; }
    return Doc.concat(comments.orphanCommentStatements(owner,
            comment -> CommentIndex.startsOnEndLine(declaration, comment)));
}
```

Because the comment must be emitted *after* the separator but the separator is decided *elsewhere*, the printer also
carries a parallel boolean predicate `enumConstantHasTrailingComment(...)` (lines 612-634) — a near-duplicate of the
logic above — purely so the *separator* code knows whether this entry "owns" the trailing comma. That boolean threads
through:

- `enumEntrySeparator(...)` (lines 339-352), which emits either `Doc.EMPTY` or `Doc.text(",")` depending on
  `previousOwnsTrailingComma`;
- `enumConstant(...)` (lines 515-526), which appends the comment with a hard-coded `", "` glue:
  `trailing == Doc.EMPTY ? Doc.EMPTY : Doc.concat(Doc.text(", "), trailing)`;
- the `EnumEntryList.rawOwnsTrailingComma` flag (lines 261, 289-290, 691-695) and `enumBlock`'s separator placement
  (lines 169-196).

So the comment placement and the comma placement are two coupled, look-ahead-driven decisions, computed twice, in three
places. That coupling is exactly what dropped separators. `LineSuffix` removes the coupling: the constant emits its body
and a `LineSuffix(commentDoc)`; the separator is emitted unconditionally as `Doc.text(",")`; the renderer guarantees the
comma prints first and the comment flushes at the line break. The four-source look-up still has to *find* the comment
(that is comment-ownership, owned by `JavaCommentPlacementPolicy` — see B1), but the *placement* arithmetic disappears.

This same inline-concat-plus-look-ahead shape recurs outside enums and would be retired by the same primitive:

- `StatementPrinter.expressionStatementTrailingComment(...)` (≈ lines 422-430) — `Doc.concat(Doc.text(" "), trailing)`.
- `CommentedExpressionListPrinter` (≈ lines 55-82) — re-orders the comma (`commaAppended`) when an argument has a
  trailing comment.
- `RecordDeclarationPrinter.recordComponentTrailingBlockComment(...)` (≈ lines 260-283) — looks ahead to the next
  component to make sure the comment does not bleed past it.
- `MethodCallChainPrinter` (≈ lines 1282-1286) — joins trailing line comments with `Doc.text(" ")`.
- `ControlConditionPrinter` (≈ line 185) — embeds the comment *into a string literal*.

`CommentTracker` already claims comments by identity through an `IdentityHashMap` (`printed`, ≈ line 28) via
`FormatterGuardrails.claimComment(...)` (≈ line 134), so deferring *where* a claimed comment prints does not affect
*whether* it is accounted — important for the interaction with `LineSuffix` below.

## Proposed new primitives

All four are new `Doc` records added to the `permits` clause (`Doc.java` line 8) with matching static factories. Each
is marked **proposed-new**. Existing primitives are unchanged.

### 1. `LineSuffix` (proposed-new) — recommended first

**Semantics.** `lineSuffix(content)` renders nothing at its position. The renderer buffers `content` and flushes it
immediately before the next `Line`/`SoftLine`/`HardLine` newline (or at end of document). Multiple suffixes on a line
flush in document order. For width purposes a `LineSuffix` contributes **zero** to `flatWidth`/`fits` (its content does
not push the current line over the limit — trailing comments never cause the *code* before them to wrap; this matches
Prettier).

**Record shape (proposed-new):**

```java
record LineSuffix(Doc content) implements Doc {}
static Doc lineSuffix(Doc content) { return new LineSuffix(content); }
```

A companion `LineSuffixBoundary` (proposed-new, optional, phase 2) forces a flush without a newline — needed only if a
suffix must precede a non-newline token; enum/field trailing comments do not need it initially.

**Renderer changes.** Add a per-render buffer `List<Doc> lineSuffixes` to `DocRenderer`.
- `render`: `case LineSuffix ls -> lineSuffixes.add(...)` (capturing current `indent`/`mode`); render nothing now.
- `newline(indent)` (currently lines 119-124): **before** trimming/emitting the break, if `lineSuffixes` is non-empty,
  render each buffered suffix at its captured indent/mode, clear the buffer, *then* perform the newline. `render(Doc)`
  (line 14-23) flushes any remaining suffixes once at the end.
- `fits`: `case LineSuffix _ -> true` (contributes nothing; never the reason a group breaks).
- `flatWidth`: `case LineSuffix _ -> 0`.

There is one subtlety to lock down in tests: a buffered suffix that itself contains a `HardLine` must not retroactively
make an already-rendered group break. Restrict v1 to single-line suffix content (line comments, block comments without
internal newlines), which is all the trailing-comment call sites produce, and assert that invariant in
`FormatterGuardrails`.

**Debug renderer.** `DocDebugRenderer` (`frmtr-core/src/main/java/dev/lanwen/frmtr/doc/DocDebugRenderer.java`) gains a
case mirroring `Indent` (lines 40-43):

```
LineSuffix
  Text("// trailing")
```

### 2. `BreakParent` (proposed-new)

**Semantics.** A zero-width marker that forces the nearest enclosing `Group` into `BREAK` mode. It is the explicit,
*targeted* form of today's accidental "emit a `HardLine` to poison `fits`" trick — but without printing a newline
itself. Prettier propagates `breakParent` up through all ancestor groups at build time; in this single-pass renderer it
is detected by `fits`.

**Record shape (proposed-new):**

```java
record BreakParent() implements Doc {}
Doc BREAK_PARENT = new BreakParent();
```

**Renderer changes.**
- `fits`: `case BreakParent _ -> false` (exactly mirrors `HardLine`, line 77) so any group containing it cannot stay
  flat.
- `flatWidth`: `case BreakParent _ -> -1` (mirrors `HardLine`, line 101).
- `render`: `case BreakParent _ -> {}` (emits nothing; the break it forces happens because the enclosing group already
  chose `BREAK`).

This makes propagation a property of the IR rather than of string probes: e.g. `ArrayExpressionPrinter`'s "break all
rows if any row overflows" becomes a group that contains a `BreakParent` emitted when a child row breaks, instead of
`nestedArrayRowsShouldBreak()` re-measuring every row.

**Debug renderer.** `case BreakParent _ -> appendLine(out, depth, "BreakParent");` (mirrors `HardLine`, line 39).

### 3. `Fill` (proposed-new)

**Semantics.** `fill(parts)` takes an alternating list `[content, separator, content, separator, …]`. Each separator is
placed flat if the *next* content fits on the current line, otherwise it breaks. Unlike `Group`, the fit decision is
made *per separator*, so a fill packs as many items per line as fit and breaks only where needed. This directly models
array elements, argument lists, `throws` lists, and enum constants.

**Record shape (proposed-new):**

```java
record Fill(List<Doc> parts) implements Doc {}   // alternating content/separator
static Doc fill(List<Doc> parts) { return new Fill(List.copyOf(parts)); }
```

**Renderer changes.** `render` gains a `case Fill` that walks pairs: render `content[i]` flat; for `separator[i]`,
measure whether `separator(flat) + content[i+1](flat)` fits in `lineWidth - column` using the existing `fits`/
`flatWidth` helpers — if yes render the separator in `FLAT`, else in `BREAK`. `fits`/`flatWidth` treat `Fill` as the
flat concatenation of all parts (separators count as their flat width), which is a safe over-estimate for an enclosing
group's own decision. This is the one primitive that adds genuinely new renderer control flow (a bounded look-ahead of
one element), so it should land *after* `LineSuffix` and after S5 folds `fits`/`flatWidth` (see below).

**Debug renderer.** `Fill` with each part rendered as a child, like `Concat` (lines 33-36).

### 4. `ConditionalGroup` + group identity (proposed-new)

**Semantics.** `conditionalGroup([A, B, C])` renders the first alternative whose flat rendering fits the remaining
width; if none fit, it renders the last. This replaces the `Optional<Doc>` fallback chains. To support Prettier-style
`ifBreak` that depends on a *named* group rather than the ambient mode, groups gain an optional id.

**Record shapes (proposed-new / modified):**

```java
record ConditionalGroup(List<Doc> alternatives) implements Doc {}        // proposed-new
record Group(Doc doc, String groupId) implements Doc {}                  // modified: add nullable groupId
record IfBreak(Doc breakDoc, Doc flatDoc, String groupId) implements Doc {} // modified: add nullable groupId
```

The existing `group(Doc)` / `ifBreak(Doc, Doc)` factories keep their signatures and pass `null` for `groupId`, so all
current call sites compile unchanged.

**Renderer changes.**
- `render`: `case ConditionalGroup cg ->` pick the first alternative with `fits(alt, indent, lineWidth - column)`,
  default to the last, render it in `FLAT` if it fit else `BREAK`. `fits`/`flatWidth` use the *first* alternative
  (Prettier's convention: the most-flat layout) as the representative width for an enclosing group.
- For group ids, maintain a `Map<String, Mode>` recording each identified group's chosen mode; `IfBreak` with a
  non-null `groupId` reads that map instead of the ambient `mode` (DocRenderer line 47). Requires the identified group
  to render before the dependent `IfBreak`, which the printers already arrange (opener group precedes the closer).

**Debug renderer.** `ConditionalGroup` prints each alternative under an `alt N:` header (mirroring `IfBreak`'s
`break:`/`flat:`, lines 48-54); `Group`/`IfBreak` append `(#groupId)` when set.

## Migration strategy (executed)

_Historical._ The staged rollout ran as planned: S5 landed first (`6e4f600a`); each primitive shipped behind its
factory with renderer/debug/width/explain coverage; `LineSuffix` was adopted across the trailing-comment families
(enum, record, control-condition, statement, expression-list, chain). See [Outcomes](#outcomes) for the end state,
including the migrations that were declined (`ConditionalGroup` chain-collapse, enum `Fill` packing) and the fact
that `LayoutWidth` was ultimately retired by the later hub true-column work, not by this item.

## Worked example: `lineSuffix` on an enum trailing comment

Input:

```java
enum Color {
    RED,
    GREEN, // the good one
    BLUE
}
```

### Before (today)

`enumConstant(...)` emits the comment glued to the constant body with `", "`, and `enumEntrySeparator(...)` must *not*
emit its own comma because `previousOwnsTrailingComma` is true. Schematically the `GREEN` entry's doc is:

```
Concat
  Text("GREEN")
  Concat            // appended by enumConstant lines 519-525
    Text(", ")
    Text("// the good one")
```

and the separator between `GREEN` and `BLUE` is forced to `Doc.EMPTY` (lines 348-352) because the boolean
`enumConstantHasTrailingComment(...)` returned true. Two coupled decisions, computed in two places; getting the boolean
wrong drops a separator.

### After (`lineSuffix`)

`enumConstant(...)` emits its body and a deferred suffix; the separator is always a literal comma:

```java
return Doc.concat(
        comments.leading(declaration),
        enumConstantAnnotations(declaration),
        Doc.text(declaration.getNameAsString()),
        enumConstantArguments(declaration),
        trailing == Doc.EMPTY ? Doc.EMPTY : Doc.lineSuffix(Doc.concat(Doc.text(" "), trailing)));
```

with `enumEntrySeparator(...)` reduced to `Doc.concat(Doc.text(","), Doc.HARD_LINE)` unconditionally. Debug doc:

```
Concat
  Text("GREEN")
  LineSuffix
    Concat
      Text(" ")
      Text("// the good one")
Text(",")
HardLine
Text("BLUE")
```

Render walk: `Text("GREEN")` → buffer the suffix → `Text(",")` → at `HardLine`, `newline()` flushes the buffer first,
yielding the line `    GREEN, // the good one`, then breaks. The comma can no longer go missing because it is no longer
conditional, and the comment placement is no longer coupled to it. `enumConstantHasTrailingComment(...)` (lines
612-634) and the `rawOwnsTrailingComma` plumbing (lines 261, 289-290, 691-695) are deleted.

## Risks

- **Renderer complexity / statefulness.** `LineSuffix` and group-id tracking add mutable per-render state
  (`lineSuffixes`, `Map<String, Mode>`) to a renderer that is currently a clean stateless recursion over `column`/
  `out`. Mitigation: reset state in `render(Doc)` (lines 14-23) exactly as `out`/`column` are reset; cover with focused
  `DocRendererTest`s including nested groups and multiple suffixes per line. This also interacts with M2's
  memoization — fill's per-element look-ahead and suffix buffering must stay compatible with a linear-time rewrite, so
  M2 should be designed with these primitives in mind (or land first).
- **`LineSuffix` + `HardLine`-in-suffix.** A multi-line suffix flushed at a newline could re-trigger breaking. v1
  restricts suffix content to single-line; enforce and test. Defer multi-line trailing block comments.
- **`BreakParent` propagation reach.** Because this renderer decides group mode top-down via `fits`, a `BreakParent`
  only affects groups whose `fits` call *encounters* it. A `BreakParent` buried inside an already-flat-decided sibling
  will not retroactively break an ancestor. This is the same limitation `HardLine` has today; document it and prefer
  emitting `BreakParent` at the point the breaking child is built. A true build-time upward propagation (Prettier-style
  `propagateBreaks`) is a larger change and is explicitly out of scope for v1.
- **`ConditionalGroup` cost.** Trying alternatives calls `fits` per alternative; nested conditional groups can compound
  the existing O(n²) `fits` behavior the roadmap notes in M2 (README lines 110-119). Land `ConditionalGroup` after, or
  jointly with, M2's bounded `fits`.
- **Interaction with `Label`.** `Label` is transparent today (DocRenderer lines 48, 81, 105). New primitives must stay
  transparent to it: `flatWidth`/`fits` must recurse through `Label` into `Fill`/`ConditionalGroup` children, and `S3`
  (`--explain`) relies on labels surviving — so wrapping a `ConditionalGroup` in a `Label` must still report which
  alternative rendered. Add a debug-renderer test that a labelled conditional group shows both the label and the chosen
  alternative.
- **Comment accounting.** Deferring a comment via `LineSuffix` must not change identity-based claiming in
  `CommentTracker` (`printed` IdentityHashMap, ≈ line 28) / `FormatterGuardrails`. The comment is still claimed when its
  `Doc` is *built*; only its *render position* moves. Run the guardrail-enabled comment pass (per
  `comment-containment-index.md`) to confirm no duplicate/missed claims.

## Success metrics

- **Special-case code removed.** Concrete first target in `EnumDeclarationPrinter`: delete
  `enumConstantHasTrailingComment(...)` (≈ 23 lines, 612-634), the `previousOwnsTrailingComma`/`rawOwnsTrailingComma`
  coupling, and the comma-glue branch in `enumConstant(...)` — on the order of 40-60 lines of coupled logic, replaced by
  one `lineSuffix` call and an unconditional comma.
- **`LayoutWidth` probe count drops.** Baseline: ~23 files referencing `currentIndentedWidth`, ~12 referencing the
  `blockStatement` probe. Track the count after each `ConditionalGroup`/`Fill` migration; target retiring `LayoutWidth`
  entirely once the last consumer migrates.
- **`Optional<Doc>` fallback chains collapse.** Count `Optional<Doc>` "layout A else B" returns in `MethodCallPrinter`,
  `LambdaExpressionPrinter`, `ConditionalExpressionPrinter` before/after; each `conditionalGroup` should replace a
  chain.
- **Fixtures simplified, not rebaselined.** Existing golden fixtures must pass *unchanged* through every step (the
  primitives are output-equivalent by construction). The win is fewer input-shape-dependent fixtures needed to pin
  fragile comment/comma behavior over time, and the enum-separator class of bug becoming structurally impossible.
- **Correctness gate.** With B3 layer 1 in place, every migration step is green on re-parse AST-equivalence and
  `format(format(x)) == format(x)`.

## Relationship to B1, B3, and S5/M2

- **B1 (centralize source-shape coupling).** B2 and B1 are complementary halves of the same enum bug. `LineSuffix`
  removes the *placement* arithmetic; B1's `JavaCommentPlacementPolicy` / `CommentTracker` boundary (see
  `comment-containment-index.md`) owns the *ownership* question (which of the four sources a trailing comment comes
  from — `EnumDeclarationPrinter` lines 577-610). `LineSuffix` consumes a `Doc` that the policy produces; the cleaner
  the policy boundary, the smaller the suffix call sites. The roadmap explicitly sequences B2 "letting it pull B1 along
  where the two intersect" (README lines 197-200).
- **B3 (correctness safety net).** B3 is the enabling guardrail: the dropped-separator bug (README lines 81-85) is
  exactly what B3 layer 1 (re-parse + AST-equivalence) and layer 2 (idempotence) would have caught. Each migration step
  here should be gated on B3 so the IR enrichment can be done "aggressively" (README line 92) without fear.
- **S5 / M2 (renderer).** S5 (fold `fits`/`flatWidth`) is a *prerequisite*: it halves the renderer edit per new
  primitive and removes the two-switch divergence risk this proposal multiplies (README lines 182-189). M2 (linear-time
  renderer with bounded `fits` + width memoization) shares the renderer with `Fill`'s per-element look-ahead and
  `ConditionalGroup`'s per-alternative `fits`; the two efforts must be co-designed so the new combinators do not
  reintroduce the O(n²) behavior M2 removes. Recommended order: **S5 → LineSuffix → (M2 / BreakParent) → Fill →
  ConditionalGroup**.
