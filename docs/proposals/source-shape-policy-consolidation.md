# Centralize Source-Shape Coupling Into One Explicit Policy

Status: Implemented (consolidation landed; strict-claims enablement deferred to B2, where the ownership consolidation is now underway — Stage 1 migrated the trailing-comment family to an explicit pre-claim ownership pre-pass + `ownsHere` filter, output-neutral; remaining traversal-order families + probe-claim decoupling are the path to enabling strict-claims)

## Summary

Formatting decisions in the Java formatter currently read the original source token layout from many independent call
sites. Printers consult `SourceShape`, `RawSource`, `CompactSourceText`, raw "does this contain a newline" probes, and
ad hoc `getRange().begin.line`/`end.line` comparisons to decide whether to preserve a user's multiline call, keep a
constructor compact, separate members with a blank line, or fall back to source-derived text. The decisions are
legitimate user-friendly features, but the decision *logic* is scattered, duplicated, and inconsistent. Output depends
on input formatting through dozens of separate doors.

This proposal does not remove source awareness. Preserving deliberate blank lines and deliberate multiline forms is
desirable behavior the formatter should keep. The proposal is to move every "should I respect the source shape here?"
decision behind a single documented policy object, `SourceShapePolicy`, carried on `JavaFormatContext`, and to forbid
printers from reaching into raw source, token ranges, or AST position arithmetic directly for layout decisions. After
the migration, a printer asks the policy a named, intent-revealing question; it never reconstructs source-shape logic
inline.

This is a structural consolidation, not an output change. It is closely related to, and a concrete near-term down
payment on, the deferred `formatter-owned-syntax-view.md` proposal: the source-shape policy is the first useful
metadata-owning slice that proposal asks for, scoped narrowly to layout-from-source decisions.

## Current coupling inventory

All paths below are under `frmtr-core/src/main/java/dev/lanwen/frmtr/java`. Counts come from greps over that directory.

### The four source-peeking helpers

- `SourceShape.java` — the one helper already named for this job. Owns range-based "spans multiple lines",
  "starts on same line", "arguments/parameters were already multiline", "throws on its own line", and try-resources
  shape. It is the right seed for the policy, but it covers only part of the decisions printers actually make.
- `RawSource.java` — raw token-range extraction, `rawWithoutOwnComment`, and `normalizeWhitespace`. Intended for
  raw-recovery and compact text, but printers also call `rawSource.rawWithoutOwnComment(node).contains("\n")` as an
  ad hoc multiline probe, duplicating what `SourceShape.spansMultipleLines` already does (`SourceShape.java:32-36`
  falls back to exactly this).
- `CompactSourceText.java` — compact source-equivalent text for width gates and fallback expression text. Reads token
  ranges and raw literals; also itself peeks at comment containment (`getAllContainedComments().isEmpty()` at
  `CompactSourceText.java:95,102,222,237`).
- `SourceText.java` — offset/line indexing and raw slicing. Lower-level and largely appropriate to keep as the slicing
  owner, but `SourceShape` and recovery helpers reach through it for `linePrefix`, `sliceBetween`, `sliceAfterWithin`.

### Source-shape consult sites by area (grounded counts)

- `sourceShape.*` decision calls in printers: **26 call sites** across:
  - `MethodCallChainPrinter.java` (10): `:377,:483,:506,:511,:615,:623,:825,:827,:874,:1143` —
    multiline-call preservation driving chain breaking and root promotion.
  - `MethodCallPrinter.java` (3): `:403,:543,:619` — multiline-call preservation and argument-break gating.
  - `ConditionalExpressionPrinter.java` (3): `:132,:196,:450` — multiline ternary preservation.
  - `ObjectCreationLayoutPolicy.java` (3): `:34,:47,:57` — constructor-argument multiline preservation.
  - `MethodDeclarationPrinter.java` (3): `:119,:132,:185` — parameter break, throws-on-own-line, return-type span.
  - `StatementPrinter.java` (3): `:517,:527,:547` — try-resources shape and resource multiline preservation.
  - `BinaryExpressionPrinter.java` (1): `:169` — enclosed operand multiline preservation.

- `rawSource.*` direct calls outside the helper files: **41 call sites**. Two distinct purposes are tangled here:
  - **Raw multiline probes (source-multiline preservation):** ~17 sites of the form
    `rawSource.rawWithoutOwnComment(x).contains("\n")`, concentrated in
    `ExpressionLambdaArgumentLayout.java` (`:86,:241,:246,:248,:330,:348,:462,:475`),
    `VariableInitializerLayout.java` (`:692,:695,:697,:785`),
    `MethodCallChainPrinter.java` (`:187,:198,:229,:927,:1116`),
    `ControlConditionPrinter.java:198`, and `ConditionalExpressionPrinter.java:451`.
    These are the same question `SourceShape.spansMultipleLines` answers, but asked directly against raw text and
    therefore behaving subtly differently (no range fast path, no shared semantics).
  - **Raw recovery / source-derived fallback text:** `rawSource.raw(...)` / `rawWithoutOwnComment(...)` in
    `ClassOrInterfaceDeclarationPrinter.java:101`, `MethodDeclarationPrinter.java:99,:165`,
    `ModuleDeclarationPrinter.java:75`, `StatementPrinter.java:239,:308,:437,:904,:1082`,
    `SwitchPrinter.java:549,:552,:558,:680`, `TextBlockPrinter.java:138,:159`,
    `VariableInitializerLayout.java:464,:479`, `EnumDeclarationPrinter.java:483,:501`,
    `PackageDeclarationPrinter.java:58`, `LambdaExpressionPrinter.java:257`, plus `RawPreservedSource.java`.

- `compactSource.*` consult sites: **43 call sites** across `MethodCallChainPrinter`, `MethodCallPrinter`,
  `ConditionalExpressionPrinter`, `SwitchPrinter`, `CommentedExpressionListPrinter`, `MethodCallChainSourcePlanner`,
  `ExpressionDispatcher`, `BodyDeclarationDispatcher`. The dominant pattern is a **width probe**:
  `currentIndentedWidth(compactSource.compact(x)) <= options.lineWidth()` (e.g. `MethodCallPrinter.java`,
  `MethodCallChainPrinter.java`, `ConditionalExpressionPrinter.java:` near the line-width comparisons,
  `MethodCallChainSourcePlanner.java:347`). Compact text is "source-equivalent" by construction, so these are also
  source-shape decisions even though they read text rather than ranges.

- **Blank-line preservation** via direct AST position arithmetic: **5 distinct gap-detection sites**, each
  re-implementing the same `current.begin.line > previous.end.line + 1` test:
  `BlockPrinter.java:118-123`, `MemberBlockPrinter.java:491-516`, `ModuleBlockPrinter.java:112-116`,
  `EnumDeclarationPrinter.java:343-349`, `RecordDeclarationPrinter.java:217`. None of these route through
  `SourceShape`; they read `getRange()` directly.

- **Source-multiline structural planning:** `MethodCallChainSourcePlanner.java` carries a `sourceMultilineChain`
  field and computes `selectorStartsAfterPreviousSegmentLine` from ranges directly (`:242-267`), independent of
  `SourceShape`.

- **Comment-containment peeking** (`getAllContainedComments`): **61 call sites**. These overlap the
  `comment-containment-index.md` proposal and are mostly *not* in scope here, except where containment is used purely
  as a gate on whether a compact/source-shaped layout is safe (e.g. `CompactSourceText.java:95,102,222,237`,
  `MethodCallChainSourcePlanner.java:110,170`). Those gates are source-shape decisions and should consult the same
  policy.

Distinct source-peeking call sites counted for this proposal: **115** (26 `sourceShape` + 41 `rawSource` +
43 `compactSource` + 5 blank-line gap probes). Comment-containment gates and the source-multiline planner fields are
additional overlapping surface tracked but not added to that number to avoid double-counting with the comment proposal.

## Why it matters

- **Idempotence is hard to reason about.** Output depends on input layout through ~115 independent reads. A second
  format pass produces source whose shape differs from the first input's shape, so any disagreement between
  range-based probes (`SourceShape.spansMultipleLines`) and raw-text probes
  (`rawSource.rawWithoutOwnComment(x).contains("\n")`) can produce non-idempotent output. With one policy, "what counts
  as multiline" has exactly one definition and one fixed point to verify.

- **Predictability and consistency.** Today the same conceptual question — "was this already multiline?" — is answered
  three different ways: a range comparison in `SourceShape`, a raw-newline scan in `ExpressionLambdaArgumentLayout` /
  `VariableInitializerLayout` / `MethodCallChainPrinter`, and a per-segment range scan in
  `MethodCallChainSourcePlanner`. Each can diverge on comments, ranges that are absent, or normalized whitespace.

- **Maintainability.** New layout features keep adding one-off source peeks. The blank-line rule is copy-pasted across
  five printers with the same `+ 1` arithmetic. A width gate is hand-spelled at every call. There is no single place to
  change policy, add a feature flag, or audit what the formatter is allowed to learn from input.

- **Testability.** Source-shape behavior is currently only observable through full golden fixtures. A single policy
  object can be unit-tested directly for each decision, including the awkward no-range and comment-bearing cases.

## Proposed design

Introduce one package-private, read-only, per-run policy object:

```java
final class SourceShapePolicy {
    SourceShapePolicy(SourceText sourceText, RawSource rawSource, CompactSourceText compactSource,
                      JavaCommentPlacementPolicy commentPolicy, FormatterOptions options);

    // --- Multiline / "preserve author's broken form" decisions ---
    boolean wasMultiline(Node node);                              // single canonical definition
    boolean startsOnSameLine(Node left, Node right);
    boolean argumentsWereMultiline(MethodCallExpr call);
    boolean argumentsWereMultiline(ObjectCreationExpr creation);
    boolean parametersWereMultiline(CallableDeclaration<?> decl);
    boolean throwsStartsOnOwnLine(CallableDeclaration<?> decl);
    boolean selectorBrokeAfter(Node previous, Node selectorOwner);  // chain-segment source split
    TryResourcesShape tryResources(TryStmt statement);

    // --- Blank-line preservation (replaces 5 hand-rolled gap checks) ---
    boolean hadBlankLineBetween(Node previous, Node next);

    // --- Width / source-equivalent fit decisions ---
    boolean fitsOnOneLine(Node node, ToIntFunction<String> indentedWidth);
    String compactText(Node node);                                 // re-exposes CompactSourceText behind the policy

    // --- Raw recovery (kept, but funneled) ---
    String rawText(Node node);
    String rawTextWithoutOwnComment(Node node);
}
```

What the policy owns:

- The **one definition of "was multiline"**. Internally it keeps the existing range-first, raw-fallback logic from
  `SourceShape.spansMultipleLines` (`SourceShape.java:32-36`) and makes every raw `.contains("\n")` probe call it.
- The **one definition of "had a blank line between"** (the `previous.end.line + 1` rule shared by
  `BlockPrinter`, `MemberBlockPrinter`, `ModuleBlockPrinter`, `EnumDeclarationPrinter`, `RecordDeclarationPrinter`).
- The **width-fit gate** so `currentIndentedWidth(compactSource.compact(x)) <= options.lineWidth()` becomes
  `policy.fitsOnOneLine(x, indentedWidth)` and there is one place that decides what "source-equivalent compact text"
  means for a fit decision.
- The **source-shaped gates currently expressed as comment-containment checks** in compact reconstruction, delegating
  the containment query itself to `JavaCommentPlacementPolicy` (per `comment-containment-index.md`) rather than calling
  `getAllContainedComments()`.

How printers consult it:

- Printers receive `SourceShapePolicy` from `JavaFormatContext` exactly where they get `sourceShape` today
  (`JavaFormatContext.java:26,43`). `SourceShape`, `ObjectCreationLayoutPolicy`'s source reads, and the raw multiline
  probes all become policy calls.
- Printers stop importing `RawSource` for layout decisions. `RawSource`/`CompactSourceText`/`SourceText` remain as the
  policy's private collaborators (and `RawPreservedSource` remains the raw-output+accounting owner), but a printer that
  only wants a layout answer no longer holds them.
- A lint-style guard (review checklist plus a targeted grep in the implementation PR) forbids new
  `getRange().*.line`, `getTokenRange()`, and `rawSource.*.contains("\n")` layout decisions outside the policy and the
  recovery/raw-output helpers.

The policy deliberately does **not** absorb `SourceText`'s offset/slicing math, `RawPreservedSource`'s comment
accounting, or the recovery planners' boundary rules. Those are not "should I respect source shape?" questions; they
are mechanics the policy may call but should not re-own.

## What stays source-aware on purpose

These are intended features and must be preserved bit-for-bit; the policy is where they live, not where they die:

- **Deliberate blank lines** between members, enum constants, module directives, record components, and statements.
- **Deliberate multiline calls/constructors/ternaries/lambdas** — when a user broke an argument list or chain across
  lines, the formatter keeps it broken (`MethodCallChainPrinter`, `MethodCallPrinter`, `ObjectCreationLayoutPolicy`,
  `ConditionalExpressionPrinter`, `ExpressionLambdaArgumentLayout`, `VariableInitializerLayout`).
- **`throws` on its own line** and **already-multiline parameter lists** (`MethodDeclarationPrinter`).
- **Try-with-resources source shape and trailing semicolon** (`StatementPrinter`, `SourceShape.tryResources`).
- **Source-equivalent width gates** that decide whether a node can stay on one line.
- **Raw recovery / source-derived fallback** for pragmas, unparsed regions, text blocks, switch labels, and javadoc.

The goal is one documented home for these, not their removal.

## Migration plan

Staged, low-risk, output-preserving. Each stage is independently shippable and golden-fixture-verified.

1. **Rename and widen the seed.** Promote `SourceShape` into `SourceShapePolicy` (or add the new type and have
   `SourceShape` delegate). Keep methods identical first; pure rename, no behavior change. Wire it on
   `JavaFormatContext` alongside the existing `sourceShape` field.

2. **Unify the multiline definition.** Add `wasMultiline(Node)` as the canonical method (the existing
   `spansMultipleLines` body). Migrate the ~17 raw `rawSource.rawWithoutOwnComment(x).contains("\n")` probes in
   `ExpressionLambdaArgumentLayout`, `VariableInitializerLayout`, `MethodCallChainPrinter`, `ControlConditionPrinter`,
   and `ConditionalExpressionPrinter` to call it. This is the highest-value idempotence step. Verify no fixture moves;
   any fixture that *does* move reveals a pre-existing divergence to be decided explicitly.

3. **Centralize blank-line preservation.** Add `hadBlankLineBetween(previous, next)` and route
   `BlockPrinter:118`, `MemberBlockPrinter:491-516`, `ModuleBlockPrinter:112`, `EnumDeclarationPrinter:343`, and
   `RecordDeclarationPrinter:217` through it. Pure de-duplication of identical arithmetic.

4. **Centralize the width-fit gate.** Add `fitsOnOneLine(node, indentedWidth)` and migrate the
   `compactSource.compact(x)` + `lineWidth()` comparisons in `MethodCallPrinter`, `MethodCallChainPrinter`,
   `ConditionalExpressionPrinter`, and `MethodCallChainSourcePlanner:347`. Compact text generation stays in
   `CompactSourceText`; only the decision wrapper moves.

5. **Fold the chain source planner's shape probes.** Replace `MethodCallChainSourcePlanner`'s direct
   `selectorStartsAfterPreviousSegmentLine` range arithmetic (`:242-267`) and `sourceMultilineChain` with policy calls
   (`selectorBrokeAfter`, `wasMultiline`).

6. **Move source-shaped containment gates behind the policy + comment policy.** The
   `getAllContainedComments().isEmpty()` gates in `CompactSourceText` and the planner become policy questions that
   internally delegate containment to `JavaCommentPlacementPolicy`. Coordinate ordering with
   `comment-containment-index.md` so the two migrations do not fight over the same call sites.

7. **Quarantine raw recovery.** Leave `rawSource.raw(...)` recovery/fallback calls as-is but funnel them through
   `SourceShapePolicy.rawText` / `rawTextWithoutOwnComment` (or keep them on `RawPreservedSource` where accounting is
   needed) so printers no longer hold a bare `RawSource` for layout reasons. Recovery text generation itself does not
   change.

8. **Close the door.** Remove `RawSource`/`SourceShape` fields from printers that now only use the policy, and add the
   grep/review guard against new direct source peeks. Update `ARCHITECTURE.md`, `docs/java-formatter-internals.md`, and
   `docs/formatter-coverage.md` in the implementation PR.

## Risks & non-goals

Risks:

- **Hidden divergence becomes visible.** Unifying range-based and raw-text multiline definitions (stage 2) may move a
  few fixtures because the two definitions are not currently identical. Each move must be an explicit, reviewed decision
  rather than a silent rebaseline.
- **A "god policy."** The policy could grow into a catch-all. Mitigation: it answers only layout-from-source questions;
  it does not own slicing (`SourceText`), comment accounting (`CommentTracker`/`RawPreservedSource`), or recovery
  boundaries (`RecoveredListPlanner`/`RecoveredSourceRegions`).
- **Overlap churn with the comment-containment work.** Stages 6 touches `getAllContainedComments` gates that
  `comment-containment-index.md` also migrates. Sequence them; do not migrate the same call site twice.
- **Stale-state hazard.** The policy must be built after source-equivalent transforms and treat printer-time node
  mutation as a bug, same constraint as the syntax-view proposal.

Non-goals:

- Removing or weakening blank-line or multiline preservation.
- Changing formatter output, line-width policy, comment placement, or raw-recovery text.
- Rebaselining or editing test fixtures or external projects.
- Replacing `SourceText` offset math, `RawPreservedSource` accounting, or recovery-boundary logic.
- Implementing the full `formatter-owned-syntax-view` (this is the narrow first slice, not the whole view).

## Success metrics

- Exactly one method defines "was multiline" and one defines "had a blank line between"; greps for
  `rawSource.*contains("\n")` and `getRange().*\.line` layout comparisons outside `SourceShapePolicy`, `SourceText`,
  and the recovery/raw-output helpers return zero matches.
- No printer outside the policy and the recovery/raw helpers imports `RawSource` for a layout decision.
- The ~115 source-peeking layout call sites collapse to policy calls; the distinct decision *kinds* (multiline,
  blank-line, width-fit, throws, try-resources, chain-split, raw-fallback) are each documented once.
- Existing golden fixtures pass without rebaseline; any intentional fixture change from stage 2 is individually
  justified.
- Direct `SourceShapePolicy` unit tests cover multiline (range and no-range), blank-line, width-fit, throws-on-own-line,
  and try-resources decisions, including comment-bearing nodes.
- Idempotence: a documented set of "deliberately multiline" and "deliberate blank line" fixtures format to a fixed point
  on the second pass.

## Relationship to other proposals and roadmap items

- **`formatter-owned-syntax-view.md` (Proposed, held for architecture review):** That proposal argues a formatter-owned
  metadata boundary is worth adopting only as a *narrow slice with an immediate consumer*, specifically calling out
  "source-shape checks" and "consolidate source/trivia lookup" as candidate first slices (see its Migration Plan steps
  3 and 5). **B1 is that first slice.** It does not supersede the syntax-view proposal and does not require the full
  view; it implements one concrete, immediately-consumed metadata owner (`SourceShapePolicy`) that the larger
  `JavaSyntaxView` can later absorb or delegate to. If the syntax view is eventually built, `SourceShapePolicy` becomes
  one of its facets rather than a competing structure. B1 therefore **depends on the syntax view only conceptually**
  and can ship independently.

- **`comment-containment-index.md` (partially implemented):** Overlaps on the source-shaped containment gates
  (`getAllContainedComments().isEmpty()`). B1 should *consume* that proposal's `JavaCommentPlacementPolicy` query
  methods rather than re-add containment scans. Stage 6 is the explicit coordination point.

- **Roadmap B2 / B3:** B1 is the prerequisite consolidation. With every source-shape decision behind one policy, B2 and
  B3 (which build on a single, auditable source-coupling surface — e.g. tightening idempotence guarantees and reducing
  remaining input-dependent behavior) operate on one object with a documented contract instead of ~115 scattered reads.
  B1 does not implement B2/B3; it makes them tractable and low-risk.

- **Comment-ownership consolidation (B2, underway):** B1's residual — enabling the `strict-claims` guardrail — reassigned
  to B2, where the comment-ownership consolidation has now begun. **Stage 1 landed:** an explicit pre-claim ownership
  subsystem (`OwnerSlot` role enum, identity-keyed `OwnerKey(anchor, slot)`, a `CommentTracker.ownership` map populated
  by a read-only `assignOwnership(unit)` pre-pass in `JavaPrinter.print`, and an `ownsHere` filter) migrates **only** the
  trailing-comment family. It is output-neutral — the trailing family is the unique one a pure source-order rule
  reproduces byte-for-byte (goldens byte-identical; zero cross-node `ownsHere` rejections corpus-wide). The remaining
  families (leading/adjacent/own/interleaved/orphan) need a **traversal-order** ownership rule — a source-order rule
  diverges ~12% on contested leading/own comments (the parent-interleaver-beats-child cases) — and the eager
  `Optional<Doc>` candidate-ladder probes must render claim-free, so `strict-claims` stays off until both land.
