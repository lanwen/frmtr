# Formatter-Owned Syntax View Proposal

Status: Proposed and held for separate architecture review.

## Summary

The removed `SyntaxNodeView` should not be reintroduced in its original form. That version was a recursive snapshot of
JavaParser node kind, range, attached comment text, and children; `JavaFormatter` built it from the printable
`CompilationUnit` and then discarded it. Because printers continued to consume JavaParser AST nodes directly, the view
never became an architectural boundary.

A formatter-owned syntax view is still a useful idea, but only if it becomes the owner of source and trivia facts that
printers repeatedly need today: normalized source spans, comment/trivia associations, parsedness, child/sibling
ordering, raw-region accounting inputs, and debug or performance labels. The first useful shape is therefore not a
replacement AST. It is a read-only per-formatting-run index over the current JavaParser tree, carried by
`JavaFormatContext`, with typed printers still accepting JavaParser nodes while asking the formatter-owned view for
metadata.

Adopt it separately from immediate performance work. Performance work can add focused measurements around current
helpers first. A syntax view is worth adopting when the formatter is ready to consolidate source/trivia lookup,
comment-placement policy, recovery boundaries, or parser-migration seams. It is not worth adopting as a speculative
wrapper around every JavaParser node.

## Current State

Formatter code currently lives under `frmtr-core/src/main/java/dev/lanwen/frmtr/java`, with public API and options in
`frmtr-core/src/main/java/dev/lanwen/frmtr` and document rendering in
`frmtr-core/src/main/java/dev/lanwen/frmtr/doc`.

`JavaFormatter` owns the entry path:

1. Configure JavaParser with language level, stored tokens, and comment attribution.
2. Parse into a JavaParser `CompilationUnit`.
3. Reject or recover parse problems according to `FormatterOptions.ParseErrorBehavior`.
4. Run `JavaTransformPipeline` and `ImportSortTransform` for source-equivalent AST normalization.
5. Construct `JavaPrinter` with `FormatterOptions`, `SourceText`, and the recovery flag.
6. Render the `Doc` from `DocRenderer`.

`JavaPrinter` remains the composition root. It builds `JavaFormatContext`, then wires `ExpressionPrinters`,
`DeclarationPrinters`, and `StatementPrinters`. Those printers and their dispatchers accept JavaParser node types such
as `CompilationUnit`, `BodyDeclaration<?>`, `Statement`, `Expression`, `MethodCallExpr`, and `ObjectCreationExpr`
directly. The coverage map in `docs/formatter-coverage.md` is already organized around those JavaParser AST kinds.

Formatter-owned boundaries already exist, but they are metadata and policy helpers around the JavaParser tree rather
than a single syntax view:

- `JavaFormatContext` carries shared per-run state: options, comment tracking, comment placement policy, raw source,
  source text, source shape, object-creation layout policy, recovery planners, compact source text, layout width, and
  parse-recovery mode.
- `JavaCommentMap` snapshots JavaParser own, orphan, and contained comment associations at
  `JavaPrinter.print(CompilationUnit)`.
- `JavaCommentPlacementPolicy`, `CommentTracker`, `JavaCommentTrivia`, `JavaCommentKind`, `CommentIndex`, and
  `CommentPlacement` classify comments, place them, and account for print-once behavior.
- `RawSource`, `RawPreservedSource`, `CompactSourceText`, `SourceText`, `SourceRegion`, `SourceShape`,
  `RecoveredListPlanner`, `RecoveredSourceRegions`, and `RecoveredRawGapPrinter` own raw text, source slices, source
  shape predicates, and parse-error recovery islands.
- `FormatterGuardrails` depends on current JavaParser node and comment identity: transforms must preserve the same
  `CompilationUnit`, existing child nodes, existing comments, and import declaration identities.

The old `SyntaxNodeView` originated in the initial Java formatter scaffold as:

- `kind`: JavaParser metamodel type name.
- `span`: line and column range through `SourceSpan`.
- `comments`: attached comment text only.
- `children`: recursive `SyntaxNodeView` children.

The initial architecture document said parsed JavaParser trees would be adapted into `SyntaxNodeView` to keep
formatter-owned syntax metadata separate from JavaParser APIs. The implementation did not follow through: `JavaPrinter`
continued walking JavaParser declarations and statements, and the `SyntaxNodeView.from(printableUnit)` call had no side
effects.

## Proposed Architecture

Introduce a formatter-owned syntax view only as an active per-run metadata/index boundary. Do not start by replacing
typed JavaParser printer inputs.

The proposed first version:

- Lives package-private in `frmtr-core/src/main/java/dev/lanwen/frmtr/java`, or in a future
  `dev.lanwen.frmtr.java.syntax` package if the file count makes the boundary clearer.
- Is built once after transforms and before printing, from the printable `CompilationUnit` and `SourceText`.
- Is stored on `JavaFormatContext`, alongside or eventually replacing the current source/trivia helpers.
- Preserves JavaParser node and comment identity, because existing transforms and comment accounting rely on identity.
- Exposes formatter-owned facts through stable vocabulary while still letting printers receive typed JavaParser nodes.

A concrete first API could be shaped around a `JavaSyntaxView` plus small values:

```java
final class JavaSyntaxView {
    static JavaSyntaxView from(CompilationUnit unit, SourceText sourceText);

    SyntaxNodeInfo info(Node node);

    Optional<SourceRegion> sourceRegion(Node node);

    List<SyntaxNodeInfo> children(Node node);

    List<JavaCommentTrivia> ownComments(Node node);

    List<JavaCommentTrivia> orphanComments(Node node);

    List<JavaCommentTrivia> containedComments(Node node);
}

record SyntaxNodeInfo(
        Node node,
        String kind,
        Optional<SourceRegion> sourceRegion,
        Node.Parsedness parsedness) {}
```

That API is intentionally narrower than a full formatter AST. It gives printers and policies a stable place to ask
"what is this syntax node in formatter terms?" without losing the JavaParser subtype information that current printers
use for dispatch.

The view can then grow only where it absorbs existing complexity:

- Move `JavaCommentMap` storage behind the view, while leaving `JavaCommentPlacementPolicy` responsible for placement
  decisions and `CommentTracker` responsible for claims.
- Move generic source-range conversion from repeated `node.getRange().map(sourceText::region)` call sites into the view.
- Provide sibling and child sequence accessors for list-oriented helpers such as `SourceOrderedCommentInterleaver` and
  `RecoveredListPlanner`.
- Provide formatter-owned labels for `Doc.label(...)` and instrumentation, replacing ad hoc label spelling like
  `java.expression:ClassName`.
- Provide parsedness and recovery facts so `StatementRuleEnvelope`, `ExpressionRuleEnvelope`, and
  `BodyDeclarationRuleEnvelope` stop walking subtrees independently for the same safety checks.

Later, after the metadata boundary is useful, selected typed view layers can be added where they remove real coupling:

- A `SyntaxListView<N extends Node>` for formatter-owned sibling lists and recovery planning.
- A `SyntaxTriviaView` for comment and token-adjacent trivia that JavaParser does not attach cleanly.
- A small `SyntaxKind` enum only for formatter-owned categories that are not one-to-one JavaParser class names, such as
  "top-level declaration", "compact unnamed-class member", "recoverable raw gap", or "source-only switch rule".

The architecture should explicitly avoid building a parallel full AST at first. A full AST mirror would force every
printer to switch dispatch style before the formatter has proven which metadata belongs behind the boundary.
No typed printer-input migration should start until at least one narrow metadata-index slice has removed real
duplication and passed output, idempotence, and reparse checks.

## Migration Plan

1. Define the target boundary in docs first.
   Update this proposal into an accepted ADR or update `ARCHITECTURE.md` only when implementation starts. The decision
   should say whether the first implementation is a metadata index or a full printer-input migration.

2. Add a minimal package-private syntax view.
   Build `JavaSyntaxView` after `JavaTransformPipeline.transform(...)` and before `JavaPrinter.print(...)`. Store it in
   `JavaFormatContext`. The first implementation should preserve JavaParser node and comment identity and should not
   change output. It must also have an immediate consumer in the same implementation slice; a built-but-unused view would
   repeat the failure mode of the removed `SyntaxNodeView`.

3. Move source-span lookup into the view.
   Replace repeated range-to-`SourceRegion` logic only where the view removes duplicated validation, for example in
   recovery planning or source-shape checks. Keep `SourceText` as the offset/slicing owner; the view should cache or
   expose regions, not duplicate line-indexing.

4. Move comment association storage behind the view.
   Let `JavaCommentMap.from(unit)` become either an implementation detail of `JavaSyntaxView` or a collaborator created
   by it. Keep `JavaCommentPlacementPolicy` and `CommentTracker` separate unless the move demonstrably simplifies
   placement versus claim responsibilities.

5. Convert list-oriented helpers next.
   `RecoveredListPlanner`, `SourceOrderedCommentInterleaver`, `CompilationUnitPrinter`, `MemberBlockPrinter`,
   `BlockPrinter`, `ModuleBlockPrinter`, `SwitchPrinter`, `EnumDeclarationPrinter`, and
   `AnnotationDeclarationPrinter` are good candidates because they already reason about source-ordered sibling lists,
   comments, and raw gaps.

6. Add instrumentation and debug labels through the view.
   Centralize syntax labels used by `Doc.label(...)` and performance timers. This should happen before broad performance
   work if the performance effort needs stable per-node or per-rule names, but not before basic performance profiling.

7. Consider typed printer-input views only after the index is established.
   Replace direct JavaParser inputs for one narrow area, such as import declarations or recovered list entries, and
   measure whether the result simplifies printer code. Do not migrate expressions, statements, and declarations all at
   once.

8. Update documentation with every architectural move.
   Implementation that changes package layout, formatter pipeline, printer boundaries, comment ownership, recovery
   strategy, or public debug behavior should update `ARCHITECTURE.md`, `docs/java-formatter-internals.md`, and
   `docs/formatter-coverage.md` in the same change.

## Pros

- API stability inside formatter internals: printers can depend on formatter-owned source/trivia vocabulary instead of
  scattering JavaParser range, comment, and parsedness calls.
- Better source-span ownership: offset-based `SourceRegion` can become the normal formatter span instead of a recovery
  detail used only by `SourceText` and recovery helpers.
- Cleaner comment/trivia model: the current `JavaCommentMap` snapshot is already close to a syntax-view component, and
  moving it behind a broader view would make "source facts" easier to locate.
- Easier parser migration later: keeping typed JavaParser nodes initially avoids churn, while moving metadata lookup
  behind formatter-owned APIs creates a gradual seam if another parser or JavaParser upgrade changes ranges/comments.
- Better testing seams: `JavaSyntaxView` can get focused tests for source-region mapping, comment association snapshots,
  parsedness flags, child ordering, and instrumentation labels without rendering whole files.
- Printer simplification in high-friction areas: list recovery, source-ordered comments, raw gap accounting, and source
  shape checks can share one metadata source instead of each helper re-walking JavaParser nodes.
- Performance instrumentation can become more consistent: timers and counters can attach to stable formatter syntax
  labels rather than JavaParser class names or scattered `Doc.label(...)` strings.

## Cons

- A view that does not own active decisions becomes dead architecture again. The old `SyntaxNodeView` failed because it
  was built and ignored.
- A full AST mirror would be expensive and risky. Current printers use JavaParser's rich subtype API for dispatch, node
  lists, names, modifiers, expressions, statements, declarations, and comments.
- Identity-sensitive behavior makes replacement hard. `FormatterGuardrails`, transforms, comment claims, and raw
  accounting currently assume JavaParser node and comment identities survive the pipeline.
- There is a real memory and build-time cost to indexing every node, source region, comment association, child list, and
  label for every format operation.
- The current helper boundaries are already reasonably focused. Folding them into a syntax view could make one large
  "god index" unless responsibilities remain explicit.
- It can distract from immediate performance work if used as a prerequisite for basic profiling, targeted allocation
  reduction, or hot-path fixes.

## Risks

- Comment duplication or loss if the view changes the distinction between comment association, placement policy, and
  print-once claiming.
- Recovery regressions if raw gaps are widened, narrowed, or source-sliced differently from current
  `RecoveredListPlanner` and `RecoveredSourceRegions` behavior.
- Transform regressions if the view encourages cloned or replaced nodes before the transform identity contract is
  redesigned.
- Stale caches if transforms or printers mutate JavaParser nodes after the view is built. The view should be built only
  after source-equivalent transforms and should treat printer-time node mutation as a bug unless explicitly designed.
- Over-generalization. A generic `kind/children/comments` tree will not encode formatter concerns such as pragma state,
  source-only switch labels, compact unnamed-class expansion, or recovered raw gaps without additional concepts.
- Test burden can grow quickly if every printer starts accepting both JavaParser nodes and syntax-view wrappers during a
  long migration.

## Verification Strategy

Proposal-only verification is limited to review and semantic diff inspection. No formatter behavior should change until
implementation starts.

For an implementation slice, use layered verification:

- Unit-test the syntax view directly with AssertJ: node identity preservation, source-region mapping, child ordering,
  own/orphan/contained comment snapshots, parsedness classification, missing-range handling, and label naming.
- Keep existing golden formatter fixtures for output behavior. New or moved ownership must prove formatted output,
  idempotence, and JavaParser reparse validity where practical.
- Run focused tests for changed helper boundaries, especially `JavaCommentPlacementPolicyTest`,
  `JavaCommentTriviaTest`, `RecoveredListPlannerTest`, `RecoveredSourceRegionsTest`, `CompilationUnitPrinterTest`,
  `BlockPrinterTest`, `MemberBlockPrinterTest`, and any printer-specific tests touched by the migration.
- Run guardrails-enabled tests for migration slices that touch transforms or comment accounting:
  `-Ddev.lanwen.frmtr.debug.guardrails=true`.
- Use `Frmtr.debugDoc(...)` or `DocDebugRenderer` snapshots only when labels or instrumentation behavior changes.
- Use `sem diff --format json` and `sem diff --format json --from origin/main --to HEAD` to inspect semantic changes
  before review.

## Adoption Recommendation

Do not adopt the original `SyntaxNodeView` shape. It was too shallow, did not own decisions, and would likely repeat the
same unused-adapter failure.

Adopt a formatter-owned syntax view only as a separate architecture slice when at least one of these conditions is true:

- Comment/trivia ownership needs another consolidation step beyond `JavaCommentMap`, `JavaCommentPlacementPolicy`, and
  `CommentTracker`.
- Recovery work needs more shared source-span and sibling-list metadata than `RecoveredListPlanner` and
  `RecoveredSourceRegions` should own alone.
- Performance instrumentation needs stable formatter-owned syntax labels and per-node metadata across printer helpers.
- Parser migration or a JavaParser upgrade creates enough range/comment API churn that a formatter-owned metadata seam
  would reduce risk.

Do not adopt it before immediate performance work that can be handled with the current structure. Start performance work
by measuring current hot paths in `JavaFormatter`, `JavaPrinter`, comment placement, raw source extraction, compact
source text, and recovery helpers. If profiling shows repeated tree scans, repeated range conversion, repeated comment
association lookup, or inconsistent instrumentation as a dominant cost, use those findings to justify a focused
`JavaSyntaxView` implementation.

The recommended path is therefore: defer broad adoption, keep the proposal as the target shape, and implement only the
small metadata-index version when a concrete formatter maintenance or performance slice can consume it immediately.
