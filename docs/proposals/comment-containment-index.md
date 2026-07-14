# Java Comment Containment Index Proposal

Status: Partially implemented; central `JavaCommentMap` indexing is implemented, while direct caller migration remains proposed.

> **Remaining actionable work:** the performance payoff is still open — (1) replace `JavaCommentMap.from(unit)`'s
> per-node `getAllContainedComments()` walk with a bottom-up, JavaParser-order-compatible index build, and (2) migrate
> the direct `getAllContainedComments()` caller clusters (accounting paths, then existing-policy printers, then the
> helpers that do not yet carry the policy) onto `JavaCommentPlacementPolicy` query methods. The boundary
> (`JavaCommentMap` / `JavaCommentPlacementPolicy` / `CommentIndex` / `CommentTracker`) already exists; the steps below
> are the remaining migration.

## Summary

Speed up Java formatter checks by replacing repeated JavaParser recursive comment-containment scans with a
formatter-owned, per-run comment containment index behind the existing `JavaCommentMap` and
`JavaCommentPlacementPolicy` boundary.

The formatter already has the right architectural boundary: `JavaPrinter.print(CompilationUnit)` calls
`JavaFormatContext.startCommentRun(unit)`, which initializes `JavaCommentPlacementPolicy`, which builds
`JavaCommentMap.from(unit)`. The current map still calls `Node.getAllContainedComments()` once for every node in
`unit.stream()`, and many formatter helpers still call `getAllContainedComments()` directly. The proposal is to make
the map itself compute JavaParser-compatible containment bottom-up once per run, expose query methods for all formatter
callers, and migrate direct call sites in staged groups.

Direct caller migration remains proposal-only. Do not change test fixtures, build files, external projects, or unrelated
dirty files as part of the remaining migration work.

## Current Hot Path

JFR before cleanup showed `Node.getAllContainedComments()` as the largest sampled hot path, around 28%, with allocation
pressure from `LinkedList`, `Object[]`, and `Arrays.copyOf`.

Prior investigation found the central multiplier:

- `JavaPrinter.print(CompilationUnit)` initializes comment state before printing.
- `JavaCommentPlacementPolicy.startRun(unit)` calls `JavaCommentMap.from(unit)`.
- `JavaCommentMap.from(unit)` walks `unit.stream()` and records each node.
- `JavaCommentMap.recordNode(...)` calls `node.getAllContainedComments()` for every node.
- JavaParser recomputes contained comments recursively and allocates fresh intermediate lists each time.

There are also remaining direct formatter-core call sites. Current counts by file are concentrated in:

- `MethodCallChainPrinter`: 12 calls
- `MethodCallPrinter`: 6 calls
- `VariableInitializerLayout`: 6 calls
- `CompactSourceText`: 5 calls
- `FormatterGuardrails`: 5 calls
- `StatementPrinter`: 4 calls
- `ExpressionLambdaArgumentLayout`: 4 calls
- `ArrayExpressionPrinter`: 3 calls
- Smaller clusters in `CommentIndex`, `ControlConditionPrinter`, `ObjectCreationLayoutPolicy`,
  `MethodCallChainSourcePlanner`, `EnumDeclarationPrinter`, `RecoveredSourceRegions`,
  `CommentedExpressionListPrinter`, `ConditionalExpressionPrinter`, `LambdaExpressionPrinter`, and
  `CallableSignaturePrinter`.

Those call sites serve three different purposes and should not be migrated with a single blunt replacement:

- Cheap safety gates such as `getAllContainedComments().isEmpty()` decide whether compact/source-shaped layout is safe.
- Source-position queries need source-ordered comments, line-comment filtering, same-line ownership, and
  between-neighbor gaps.
- Accounting paths need raw `Comment` identities for `CommentTracker`, `FormatterGuardrails`, raw-preserved source, and
  recovered source regions.

## Proposed Design

Keep the existing public shape of the formatter comment boundary, but replace its internals:

- `JavaCommentMap` remains the per-run snapshot owner.
- `JavaCommentPlacementPolicy` remains the read-only query API for printers and helpers.
- `CommentTracker` remains the stateful identity-based claim/accounting owner.
- `CommentIndex` remains low-level range predicates and ordering only; it should not directly ask JavaParser for
  recursive containment.

Build containment bottom-up in `JavaCommentMap.from(unit)` after all source-equivalent transforms have completed and
before any printing starts. The implementation should preserve JavaParser semantics for every node:

1. A node's contained comments include that node's orphan comments first.
2. Then, for each child in child order, include the child's own attached comment when present.
3. Then include that child's contained comments.
4. Do not include the node's own attached comment in that node's contained list.

That order is the compatibility contract. Tests should compare the formatter-owned lists against JavaParser
`getAllContainedComments()` on representative parsed trees before any caller migration relies on the index.

Recommended map internals:

- Use `IdentityHashMap<Node, ...>` for node lookups, matching current formatter behavior.
- Add an `IdentityHashMap<Comment, JavaCommentTrivia>` so every raw JavaParser comment identity maps to one canonical
  `JavaCommentTrivia` wrapper for the run.
- Store `ownComment(Node)`, `orphanComments(Node)`, and `containedComments(Node)` as immutable views.
- Store cheap booleans or derived helpers for common gates such as `hasContainedComments(Node)` and
  `hasContainedLineComments(Node)` so callers do not stream lists only to check emptiness.
- Expose raw identity views where accounting needs raw `Comment` values, for example `containedRawComments(Node)` or
  policy methods that return `JavaCommentTrivia` while `CommentTracker` unwraps identity through `trivia.comment()`.
- Reuse `List.of()` for empty lists and copy only once for non-empty lists. The index will still retain containment
  lists per node, but it avoids JavaParser's repeated recursive recomputation and transient allocation chain.

Recommended policy/query additions:

- `boolean hasContainedComments(Node node)`
- `boolean hasContainedLineComments(Node node)`
- `boolean containsComment(Node node, JavaCommentTrivia comment)` or `containsCommentIdentity(Node node, Comment comment)`
- `List<JavaCommentTrivia> containedComments(Node node, Predicate<JavaCommentTrivia> predicate)`
- `List<JavaCommentTrivia> containedLineComments(Node node)`
- `List<JavaCommentTrivia> sourceOrderedContainedComments(Node node)` when callers currently sort locally
- `List<JavaCommentTrivia> unattachedTrailingLineCommentsAfterNode(Node node)` to mirror the existing block-comment query
- raw-accounting helpers used by `RawPreservedSource`, `RecoveredSourceRegions`, and `FormatterGuardrails`

The policy should be the formatter-owned surface for placement and containment. Printers should not need to know whether
the map is implemented as precomputed lists, compact arrays, intervals, or another index structure.

## Implementation Steps

1. Add parity coverage before behavior migration.
   - Add focused `JavaCommentMap` tests that parse small Java snippets and assert the indexed contained-comment order
     matches JavaParser's current `getAllContainedComments()` order.
   - Cover own comments, orphan comments, nested child comments, comments on method-call arguments, comments in arrays,
     comments in lambda bodies, comments in conditionals, and range-less orphan comments added in tests.
   - Assert that a node's own comment is not in its own contained list unless JavaParser itself exposes it through a child
     relationship.

2. Replace `JavaCommentMap.from(unit)` internals with bottom-up construction.
   - Traverse the parsed tree once, compute child containment before parent containment, and store immutable result lists.
   - Keep current lifecycle semantics: `JavaCommentPlacementPolicy.startRun(unit)` may run once, and queries before start
     still fail fast.
   - Preserve raw `Comment` object identity. `CommentTracker` and guardrails depend on identity, not text equality.

3. Add policy methods for common containment queries.
   - Start with cheap gates and raw accounting because they are the highest-frequency uses and easiest to preserve:
     `hasContainedComments`, `hasContainedLineComments`, `containedRawComments`, and source-ordered contained comments.
   - Keep `CommentIndex` focused on range predicates. Move its recursive containment methods behind
     `JavaCommentPlacementPolicy`.

4. Migrate accounting paths.
   - `FormatterGuardrails.accountRawComments(...)`,
     `FormatterGuardrails.accountRawCommentsWithoutOwnComment(...)`, and
     `FormatterGuardrails.assertAllCommentsAccounted(...)` should use the run index where a run policy is available.
   - `RawPreservedSource` can account raw comments through `CommentTracker`, and `CommentTracker` can delegate containment
     identity selection to the started policy.
   - `RecoveredSourceRegions.commentAccounting(...)` should use indexed source-ordered comments for the requested
     `commentRoot`, while keeping its offset containment and crossing-boundary rules unchanged.
   - Tests that construct these helpers directly will need started policy/test-helper wiring in the implementation PR.

5. Migrate formatter callers already carrying `JavaCommentPlacementPolicy`.
   - Replace simple gates in `MethodCallChainPrinter`, `MethodCallPrinter`, `StatementPrinter`,
     `CommentedExpressionListPrinter`, and `ConditionalExpressionPrinter` with policy calls.
   - Preserve more nuanced checks as explicit policy methods, especially:
     - method-chain segment ownership where scope-contained comments should not count as segment-owned comments;
     - trailing method-call argument line comments;
     - block or line comments after a node on the same line;
     - comments between `then` and `else` in `StatementPrinter`.

6. Migrate helpers that do not currently carry the policy.
   - `CompactSourceText`, `VariableInitializerLayout`, `ArrayExpressionPrinter`, `ObjectCreationLayoutPolicy`,
     `ExpressionLambdaArgumentLayout`, `ControlConditionPrinter`, `CallableSignaturePrinter`, and
     `MethodCallChainSourcePlanner` need narrow dependencies rather than ad hoc access to the full formatter context.
   - Prefer passing a small query surface, such as `CommentContainment` or selected predicates, over turning
     `JavaFormatContext` into a service locator.
   - Keep compact-source clone/removal behavior scoped. The goal is to eliminate repeated containment scans, not redesign
     comment-free source reconstruction in the same step.
   - Treat indexed containment as valid only for original nodes from the current formatting run. Clone-local helpers may
     keep isolated JavaParser calls for detached clones, or they must receive the original node whose indexed facts they
     need. Unknown detached nodes should not silently look like comment-free original nodes.

7. Remove direct formatter-core containment scans from the print path.
   - After migration, remaining `getAllContainedComments()` calls should be limited to `JavaCommentMap` parity tests,
     JavaParser compatibility tests, or deliberately isolated transform/debug code where no run policy exists.
   - Do not add a permanent negative test that merely asserts the method is absent. Use review and diff checks during the
     implementation PR instead.

8. Update architecture documentation in the implementation change.
   - This proposal does not edit `ARCHITECTURE.md`.
   - The future implementation PR should update `ARCHITECTURE.md`, `docs/java-formatter-internals.md`, and
     `docs/formatter-coverage.md` if the helper dependencies or public formatter pipeline shape changes.

## Test Plan

Add or run focused tests in the implementation PR. Use AssertJ for new assertions.

Add focused unit tests:

- `JavaCommentMapTest`: parity with JavaParser containment order, own-comment exclusion, orphan-first order,
  child-own-before-child-contained order, canonical trivia identity for the same raw `Comment`, and range-less orphan
  behavior.
- `JavaCommentPlacementPolicyTest`: new query methods for `hasContainedComments`, contained line comments,
  source-ordered contained comments, contains-by-identity, between-neighbor comments, before-first/after-last comments,
  and unattached trailing line comments.
- `FormatterGuardrailsTest`: raw accounting and missed-comment accounting through the indexed path with guardrails on.
- `RecoveredSourceRegionsTest`: fully contained, outside, crossing, and range-less comments using indexed comments.

Run existing high-signal formatter fixtures without changing fixture inputs or outputs:

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

Run targeted JVM tests first, then the full module:

- `./gradlew :frmtr-core:test --tests dev.lanwen.frmtr.java.JavaCommentPlacementPolicyTest`
- `./gradlew :frmtr-core:test --tests dev.lanwen.frmtr.java.FormatterGuardrailsTest`
- `./gradlew :frmtr-core:test --tests dev.lanwen.frmtr.java.RecoveredSourceRegionsTest`
- `./gradlew :frmtr-core:test --tests dev.lanwen.frmtr.FrmtrTest`
- `./gradlew :frmtr-core:test`

Also run a guardrail-enabled formatter test pass for the comment fixture subset so duplicate-claim and missed-comment
failures surface while the indexed path is still fresh.

Test fixtures and external projects must not be changed for this optimization. Existing fixtures should continue to pass
without rebaselining.

## Pros

- Targets the measured largest hot path instead of optimizing unrelated formatter work.
- Keeps comment semantics centralized behind the existing `JavaCommentMap` and `JavaCommentPlacementPolicy` boundary.
- Reduces recursive JavaParser scans and transient list allocations during formatter checks.
- Makes comment ownership queries explicit, which should reduce future ad hoc source-position scans in printers.
- Allows staged rollout with behavior parity tests before broad caller migration.

## Cons

- The index retains containment lists for nodes, so it trades repeated transient allocations for retained per-run memory.
- The migration touches many formatter helpers because direct containment calls are spread across layout gates,
  source-shape planning, raw accounting, and guardrails.
- Helpers that do not currently carry comment policy will need dependency reshaping.
- Exact JavaParser containment order is subtle and must be locked down before relying on the formatter-owned algorithm.
- Accounting tests that currently construct `CommentTracker` or `RecoveredSourceRegions` without a started policy will
  need small test-helper updates during implementation.

## Risks

- Semantic drift from JavaParser ordering would be user-visible as moved, duplicated, or lost comments.
- Building the index before all AST transforms finish would make the index stale. The run index must be built after
  transform completion and before printing.
- New policy methods could become too broad. Keep source-position predicates in `CommentIndex`, containment data in
  `JavaCommentMap`, placement decisions in `JavaCommentPlacementPolicy`, and rendering/claiming in `CommentTracker`.
- Range-less comments and recovered raw regions are easy to mishandle because recovery treats unknown ranges as unsafe.
- Compact-source paths that clone nodes and remove comments may still need careful handling; do not collapse those into
  indexed source queries unless behavior is proven equivalent.
- Measuring only fixture runtime may hide allocation wins. Include allocation profiling, not only wall-clock checks.

## Rollout/Measurement Plan

1. Capture a fresh baseline on the same corpus used for the original JFR before implementation.
   - Record wall-clock formatter check time.
   - Record JFR or async-profiler samples for `Node.getAllContainedComments()`.
   - Record allocation pressure around `LinkedList`, `Object[]`, and `Arrays.copyOf`.
   - Record the current direct-call inventory with `rg -n "getAllContainedComments" frmtr-core/src/main/java`.

2. Land the bottom-up `JavaCommentMap` implementation behind the existing policy API.
   - Run parity tests and the current formatter fixture suite.
   - Measure again before migrating all direct call sites. This isolates the gain from replacing the central
     `JavaCommentMap.from(unit)` multiplier.

3. Migrate direct callers by cluster.
   - Accounting cluster: guardrails, raw-preserved source, recovered source regions.
   - Existing-policy printer cluster: method calls, method chains, statements, conditionals, commented expression lists.
   - Helper dependency cluster: compact source, variable initializers, arrays, object creation, lambda argument layout,
     control conditions, callable signatures, and method-chain source planning.

4. Measure after each cluster.
   - The largest expected first win should come from the central map build.
   - The direct caller migration should reduce remaining samples and allocation spikes during complex comment-heavy files.
   - Any regression in formatter output, guardrails, or recovered-region behavior blocks rollout.
   - Include a retained-memory check on a comment-heavy or large generated source file, because the index deliberately
     trades repeated transient allocations for retained per-run containment lists.

5. Final acceptance target.
   - Existing formatter fixtures pass without fixture changes.
   - Guardrail-enabled comment tests pass.
   - JFR no longer shows `Node.getAllContainedComments()` as a dominant formatter hot path.
   - Allocation profiles show the prior `LinkedList`/array-copy churn materially reduced.
   - Formatter check wall-clock improves on the profiled corpus with no output rebaseline.

## Non-goals

- Implementing the optimization in this proposal task.
- Changing formatter output semantics, line wrapping policy, comment placement rules, or raw recovery behavior.
- Rebaselining or editing test fixtures.
- Editing external projects to make formatter checks pass.
- Replacing JavaParser or changing parser configuration.
- Redesigning `CommentTracker` claiming semantics.
- Rewriting compact-source or raw-source reconstruction beyond the containment-query migration needed to remove repeated
  JavaParser scans.
