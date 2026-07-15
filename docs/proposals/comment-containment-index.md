# Java Comment Containment Index Proposal

Status: Partially implemented; the bottom-up index and original-tree boolean gates are implemented, while query-specific and accounting callers remain proposed.

> **Remaining actionable work:** migrate full-list filtering and identity-accounting callers onto intent-specific `JavaCommentPlacementPolicy` queries, preserve the direct JavaParser fallback for detached or cloned nodes, and measure the allocation and wall-clock effect.

## Summary

Speed up Java formatter checks by answering recursive comment-containment questions from a formatter-owned, per-run index behind the `JavaCommentMap` and `JavaCommentPlacementPolicy` boundary.

`JavaPrinter.print(CompilationUnit)` calls `JavaFormatContext.startCommentRun(unit)`, which initializes `JavaCommentPlacementPolicy` and builds `JavaCommentMap.from(unit)`.
The map computes JavaParser-compatible containment bottom-up once per run.
Boolean layout safety gates use `SourceShapePolicy.hasContainedComments(Node)`, which reads the run index for original nodes and falls back to JavaParser for detached clones.

Remaining direct callers need filtered lists, raw identities, or clone-local facts and require separate migrations.
Do not change formatter output, test fixtures, build files, external projects, or unrelated dirty files as part of those migrations.

## Current Hot Path

The baseline JFR attributes around 28% of sampled formatter work to `Node.getAllContainedComments()`, with allocation pressure from `LinkedList`, `Object[]`, and `Arrays.copyOf`.

The central multiplier is absent from `JavaCommentMap` because one recursive bottom-up walk records own, orphan, and contained associations for every original node.
Original-tree boolean gates read that snapshot without allocating recursive JavaParser lists.
Remaining direct formatter-core callers are concentrated in full-list filtering, comment identity accounting, compatibility tests, and clone-local compact-source reconstruction.

Formatter containment questions serve three different purposes and should not be migrated with a single blunt replacement:

- Indexed safety gates decide whether compact/source-shaped layout is safe.
- Remaining source-position queries need source-ordered comments, line-comment filtering, same-line ownership, and between-neighbor gaps.
- Remaining accounting paths need raw `Comment` identities for `CommentTracker`, `FormatterGuardrails`, raw-preserved source, and recovered source regions.

## Current Design

Keep the existing formatter comment boundary:

- `JavaCommentMap` remains the per-run snapshot owner.
- `JavaCommentPlacementPolicy` remains the read-only query API for printers and helpers.
- `CommentTracker` remains the stateful identity-based claim/accounting owner.
- `CommentIndex` remains low-level range predicates and ordering only; it should not directly ask JavaParser for recursive containment.

`JavaCommentMap.from(unit)` builds containment bottom-up after source-equivalent transforms and before printing starts.
The implementation preserves JavaParser semantics for every node:

1. A node's contained comments include that node's orphan comments first.
2. Then, for each child in child order, include the child's own attached comment when present.
3. Then include that child's contained comments.
4. Do not include the node's own attached comment in that node's contained list.

That order is the compatibility contract.
Parity tests compare formatter-owned lists against JavaParser `getAllContainedComments()` on representative parsed trees.

Current map internals:

- Use `IdentityHashMap<Node, ...>` for node lookups, matching current formatter behavior.
- Add an `IdentityHashMap<Comment, JavaCommentTrivia>` so every raw JavaParser comment identity maps to one canonical `JavaCommentTrivia` wrapper for the run.
- Store `ownComment(Node)`, `orphanComments(Node)`, and `containedComments(Node)` as immutable views.
- Expose `hasContainedComments(Node)` and `hasContainedLineComments(Node)` so callers do not stream lists only to check emptiness.
- Expose raw identity views where accounting needs raw `Comment` values, for example `containedRawComments(Node)` or policy methods that return `JavaCommentTrivia` while `CommentTracker` unwraps identity through `trivia.comment()`.
- Reuse `List.of()` for empty lists and copy only once for non-empty lists.
  The index still retains containment lists per node, but it avoids JavaParser's repeated recursive recomputation and transient allocation chain.

Available and proposed policy queries:

- `boolean hasContainedComments(Node node)` (available)
- `boolean hasContainedLineComments(Node node)` (available)
- `boolean containsComment(Node node, JavaCommentTrivia comment)` or `containsCommentIdentity(Node node, Comment comment)`
- `List<JavaCommentTrivia> containedComments(Node node, Predicate<JavaCommentTrivia> predicate)`
- `List<JavaCommentTrivia> containedLineComments(Node node)`
- `List<JavaCommentTrivia> sourceOrderedContainedComments(Node node)` when callers currently sort locally
- `List<JavaCommentTrivia> unattachedTrailingLineCommentsAfterNode(Node node)` to mirror the existing block-comment query
- raw-accounting helpers used by `RawPreservedSource`, `RecoveredSourceRegions`, and `FormatterGuardrails`

The policy should be the formatter-owned surface for placement and containment.
Printers should not need to know whether the map is implemented as precomputed lists, compact arrays, intervals, or another index structure.

## Implementation State and Next Steps

1. Parity coverage protects JavaParser containment order, own-comment exclusion, orphan-first order, child-own-before-child-contained order, canonical trivia identity, and range-less orphans.

2. `JavaCommentMap.from(unit)` traverses the parsed tree once, computes child containment before parent containment, and stores immutable identity-keyed views.
   - `JavaCommentPlacementPolicy.startRun(unit)` owns the one-run lifecycle, and queries before start fail fast.
   - Raw `Comment` identity remains stable for `CommentTracker` and guardrails.

3. `hasContainedComments` serves boolean layout gates from the run index for original nodes and scans detached nodes directly.
   - `SourceShapePolicy` owns general compact/source-shape gates.
   - Placement-specific list and identity queries may use `JavaCommentPlacementPolicy` directly.
   - `CompactSourceText` keeps direct JavaParser scans because it handles detached comment-stripped clones that are absent from the run index.

4. Migrate accounting paths.
   - `FormatterGuardrails.accountRawComments(...)`, `FormatterGuardrails.accountRawCommentsWithoutOwnComment(...)`, and `FormatterGuardrails.assertAllCommentsAccounted(...)` should use the run index where a run policy is available.
   - `RawPreservedSource` can account raw comments through `CommentTracker`, and `CommentTracker` can delegate containment identity selection to the started policy.
   - `RecoveredSourceRegions.commentAccounting(...)` should use indexed source-ordered comments for the requested `commentRoot`, while keeping its offset containment and crossing-boundary rules unchanged.
   - Direct helper tests need a started policy when their production path depends on the run index.

5. Migrate query-specific formatter callers already carrying `JavaCommentPlacementPolicy`.
   - Preserve nuanced checks as explicit policy methods, especially:
     - method-chain segment ownership where scope-contained comments should not count as segment-owned comments;
     - trailing method-call argument line comments;
     - block or line comments after a node on the same line;
     - comments between `then` and `else` in `StatementPrinter`.

6. Migrate remaining helpers through narrow policy dependencies rather than ad hoc access to the full formatter context.
   - Keep compact-source clone/removal behavior scoped.
     The goal is to eliminate repeated containment scans, not redesign comment-free source reconstruction in the same step.
   - Treat indexed containment as valid only for original nodes from the current formatting run.
     Clone-local helpers keep isolated JavaParser calls for detached clones or receive the original node whose indexed facts they need.
     Unknown detached nodes must not silently look like comment-free original nodes.

7. Remove direct original-tree containment scans from the print path.
   - Remaining `getAllContainedComments()` calls should be limited to parity tests, JavaParser compatibility tests, clone-local reconstruction, or deliberately isolated transform/debug code where no run policy exists.
   - Do not add a permanent negative test that merely asserts the method is absent.
     Use review and semantic diff checks during each migration instead.

8. Keep `ARCHITECTURE.md`, `docs/java-formatter-internals.md`, and `docs/formatter-coverage.md` aligned when helper dependencies or the formatter pipeline shape changes materially.

## Verification

Current focused coverage:

- `JavaCommentMapTest` protects JavaParser containment order, own-comment exclusion, orphan-first order, child-own-before-child-contained order, canonical trivia identity, and range-less orphan behavior.
- `JavaCommentPlacementPolicyTest` protects available containment, source-order, neighbor-gap, and trailing-comment queries.
- `SourceShapePolicyTest` protects indexed original-node gates and the direct fallback for detached clones.
- `FormatterGuardrailsTest` protects raw and missed-comment accounting.
- `RecoveredSourceRegionsTest` protects fully contained, outside, crossing, and range-less comments.

Run these high-signal formatter fixtures without changing fixture inputs or outputs:

- `chain-comment-ownership`
- `comment-preservation-method-chain-segments`
- `comment-preservation-method-arguments`
- `comment-preservation-leading-statements`
- `comment-preservation-annotation-array`
- `comment-preservation-try-resources`
- `method-chain-trailing-empty-call-comment`
- `method-chain-trailing-lambda-comment`
- `lambda-expression-argument-opener`
- `variable-chain-initializer`
- `field-trailing-comments`
- `member-comment-spacing`
- adopted `prettier-java` comment and member-chain fixtures

Run targeted JVM tests for each migrated query cluster, then the full module:

- `./gradlew :frmtr-core:test --tests dev.lanwen.frmtr.java.JavaCommentPlacementPolicyTest`
- `./gradlew :frmtr-core:test --tests dev.lanwen.frmtr.java.FormatterGuardrailsTest`
- `./gradlew :frmtr-core:test --tests dev.lanwen.frmtr.java.RecoveredSourceRegionsTest`
- `./gradlew :frmtr-core:test --tests dev.lanwen.frmtr.FrmtrTest`
- `./gradlew :frmtr-core:test`

Also run a guardrail-enabled formatter test pass for the comment fixture subset so duplicate-claim and missed-comment failures surface while the indexed path is fresh.

Test fixtures and external projects must not change for this optimization.
Existing fixtures should pass without rebaselining.

## Pros

- Targets the measured largest hot path instead of optimizing unrelated formatter work.
- Keeps comment semantics centralized behind the existing `JavaCommentMap` and `JavaCommentPlacementPolicy` boundary.
- Reduces recursive JavaParser scans and transient list allocations during formatter checks.
- Makes comment ownership queries explicit, which should reduce future ad hoc source-position scans in printers.
- Allows staged rollout with behavior parity tests before broad caller migration.

## Cons

- The index retains containment lists for nodes, so it trades repeated transient allocations for retained per-run memory.
- The remaining migration touches several formatter helpers because full-list filtering and identity accounting are spread across printers, raw accounting, and guardrails.
- Full-list consumers that do not carry comment policy need narrow dependency reshaping.
- Exact JavaParser containment order is subtle and requires permanent parity coverage.
- Accounting tests that construct `CommentTracker` or `RecoveredSourceRegions` without a started policy need small test-helper updates as those paths migrate.

## Risks

- Semantic drift from JavaParser ordering would be user-visible as moved, duplicated, or lost comments.
- Building the index before all AST transforms finish makes the index stale.
  The run index must stay after transform completion and before printing.
- New policy methods can become too broad.
  Keep source-position predicates in `CommentIndex`, containment data in `JavaCommentMap`, placement decisions in `JavaCommentPlacementPolicy`, and rendering/claiming in `CommentTracker`.
- Range-less comments and recovered raw regions are easy to mishandle because recovery treats unknown ranges as unsafe.
- Compact-source paths that clone nodes and remove comments require direct containment scans unless the original identity is available.
- Measuring only fixture runtime may hide allocation wins. Include allocation profiling, not only wall-clock checks.

## Rollout/Measurement Plan

1. Capture a fresh baseline on the same corpus before the next migration cluster.
   - Record wall-clock formatter check time.
   - Record JFR or async-profiler samples for `Node.getAllContainedComments()`.
   - Record allocation pressure around `LinkedList`, `Object[]`, and `Arrays.copyOf`.
   - Record the current direct-call inventory with `rg -n "getAllContainedComments" frmtr-core/src/main/java`.

2. Measure the bottom-up `JavaCommentMap` implementation and indexed boolean gates behind the existing policy API.
   - Run parity tests and the formatter fixture suite.
   - Compare against the baseline before migrating query-specific call sites.

3. Migrate remaining direct callers by cluster.
   - Accounting cluster: guardrails, raw-preserved source, recovered source regions.
   - Existing-policy query cluster: method calls, method chains, statements, conditionals, and commented expression lists.
   - Helper query cluster: callable signatures and other full-list consumers that lack the exact policy query they need.

4. Measure after each cluster.
   - The largest expected first win should come from the central map build.
   - The direct caller migration should reduce remaining samples and allocation spikes during complex comment-heavy files.
   - Any regression in formatter output, guardrails, or recovered-region behavior blocks rollout.
   - Include a retained-memory check on a comment-heavy or large generated source file because the index deliberately trades repeated transient allocations for retained per-run containment lists.

5. Final acceptance target.
   - Existing formatter fixtures pass without fixture changes.
   - Guardrail-enabled comment tests pass.
   - JFR no longer shows `Node.getAllContainedComments()` as a dominant formatter hot path.
   - Allocation profiles show the prior `LinkedList`/array-copy churn materially reduced.
   - Formatter check wall-clock improves on the profiled corpus with no output rebaseline.

## Non-goals

- Replacing the bottom-up containment representation without fresh measurement.
- Changing formatter output semantics, line wrapping policy, comment placement rules, or raw recovery behavior.
- Rebaselining or editing test fixtures.
- Editing external projects to make formatter checks pass.
- Replacing JavaParser or changing parser configuration.
- Redesigning `CommentTracker` claiming semantics.
- Rewriting compact-source or raw-source reconstruction beyond the containment-query migration needed to remove repeated JavaParser scans.
