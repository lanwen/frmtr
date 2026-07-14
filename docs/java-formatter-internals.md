# Java Formatter Internals

Status: current architecture detail.

This document explains the `frmtr-core` Java formatter internals that are too detailed for
[ARCHITECTURE.md](../ARCHITECTURE.md). The exhaustive AST ownership map lives in
[formatter-coverage.md](formatter-coverage.md); this document focuses on collaborator boundaries and why they exist.

## Formatter Entry

`JavaFormatter` owns JavaParser configuration, pragma gating, parse-error handling, and the declared transform stage
between parsing and printing. It enables token storage and comment attribution because formatter rules need
syntax-adjacent trivia.

`FormatterOptions.JavaLanguageLevel` is the public parser-level setting. `JavaFormatter` converts it to JavaParser's own
language-level enum internally. The default is `LATEST_AVAILABLE`, which maps to JavaParser's bleeding-edge parser mode,
while `UNSET` deliberately selects JavaParser raw mode. Callers that need a strict release gate should choose a concrete
`JAVA_*` value.

`FormatterOptions.ParseErrorBehavior` is the public parse-problem policy. The default is `RECOVER`, which lets JavaParser
return a partial compilation unit for supported recovered sibling-list printing; `FAIL` preserves strict
fail-on-any-problem behavior. The private `JavaParseResult` boundary carries the compilation unit, parser problems, and
problem flag so diagnostics, debug APIs, and recovery printers share one parse handoff point.

Parse failures are reported with structured `FormatterException.SourceProblem` data: parser message, one-based location
when known, nearest enclosing declaration source line when detected, and source context around the failure. Long source
lines are cropped to a 256-character column window around the reported position. JavaParser 3.28.1 does not expose typed
line or column accessors for `TokenMgrException`; lexical failures that omit `Problem` locations therefore use a fallback
that parses the generated token-manager message only for that typed exception.

The public `Frmtr` API wraps recoverable internal formatter failures, including parser dependency linkage failures and
assertions, as `FormatterException.internal(...)` so adapters can report concise failures without treating them as
VM-level crashes. `Frmtr.debugDoc(...)` shares that wrapping and the same parser, transform, and Java printing path as
formatting, but returns `DocDebugRenderer` output instead of rendered source.

## Transforms

`JavaFormatTransform` is the package-private transform contract for source-equivalent AST normalization that runs after
parse and before printing. Each transform returns a `JavaTransformResult` carrying the transformed `CompilationUnit` plus
transform identity metadata.

`JavaTransformPipeline` sequences transform results over one JavaParser tree and currently exposes only the final unit to
the formatter. Transforms are skipped whenever JavaParser reports parse problems, because partially recovered syntax
should not be reordered or mutated before raw-region printing.

`ImportChunks` models source import chunks whose blank/comment gaps and detached leading comments are hard boundaries.
`ImportSortTransform` sorts the compilation unit's existing import declarations into static-then-ordinary name order
inside those safe chunks without cloning nodes, leaving comments and node identity attached for the printer.

## Printer Graph

`JavaPrinter` is the composition root for one Java printing run. It creates one per-run `JavaFormatContext` for
formatter-wide options, comment tracking, comment placement policy, formatter pragmas, raw source recovery, compact
source text, and source-position comment placement, plus shared source-shape layout policy for constructor calls. It then
constructs shared `TypePrinter` support and three composer helpers:

- `ExpressionPrinters`: wires the expression rule envelope, expression dispatcher, and expression-specific helpers for
  assignments, calls, lambdas, arrays, binaries, object creation, casts, conditionals, text blocks, and returns.
- `DeclarationPrinters`: wires declaration prefixes, module/type/member/callable/field/local-variable printers,
  compilation-unit layout, and the body-declaration envelope.
- `StatementPrinters`: wires block sequencing, switch formatting, control conditions, statement dispatch, and the
  statement envelope.

The composer helpers own construction order and local cycles only. The formatter behavior still narrows through the same
envelope gates, dispatchers, and specialized printers described below; `JavaPrinter` stays deliberately opinionated and
sparse on public options.

`JavaFormatRule` is the package-private node-rule contract used at dispatcher boundaries. After `JavaPrinter` and a
dispatcher or printer have selected a declaration, statement, or expression category, a typed rule formats exactly that
AST node while callers still own pragma state, comment attachment, raw-source recovery, compact fallback policy, and
context selection.

`LayoutContext` is the immutable positional context threaded into every rule (`JavaFormatRule<N>` is
`(node, LayoutContext) -> Doc`). It separates three concerns that would otherwise blur together: run-scoped services
live on `JavaFormatContext`, per-node-*type* dispatch lives on the dispatchers, and the per-node *positional* facts — the
ones the node's own IR cannot see — live here. It carries the same-line `leftEdgePrefix` ahead of the node (an
assignment target, `return `, a declarator `name = `), the `trailingContent` a caller glues after it (a header's ` {` or
`;`), the `enclosing` construct, and whether the line is already committed to a `leadingBreak`. So a width gate can
measure the node's first line at its true rendered column instead of inferring the prefix from a stale source column. It
is never stored as a field and never mutated: a descent that wants a different position derives a fresh value, and
trying a candidate layout is just calling a rule with a derived context and discarding the result, so there is nothing
to roll back.

The main envelope and dispatcher boundaries are:

- `StatementRuleEnvelope`: applies the outer statement pragma, raw, and comment gate before formatted statement content
  dispatch.
- `StatementPrinter`: renders structured statement bodies and delegates switch-entry grammar, expression formatting,
  local variable declarations, declaration bodies, method-call statement shape, control-condition shape, and block
  rendering back to existing owners.
- `ExpressionRuleEnvelope`: applies the outer expression entry gate, including clone-before-own-comment-removal
  rendering for callers that have already claimed an attached comment.
- `ExpressionTail` and `ExpressionTailRenderer`: carry caller-owned semicolon, comma, or empty tails through expression
  rendering so terminators and separators are emitted before trailing `//` comments instead of being stranded after
  them.
- `ExpressionDispatcher`: narrows broad `Expression` AST kinds and delegates to specialized expression printers or
  compact fallback text.
- `BodyDeclarationRuleEnvelope`: applies the outer body-declaration pragma, raw, and leading-comment gate before
  formatted declaration content dispatch.
- `BodyDeclarationDispatcher`: narrows broad `BodyDeclaration` AST kinds and delegates declaration layout to specialized
  printers or compact fallback text.

## Statement And Control Printers

`SwitchPrinter` renders switch expressions and reusable switch-entry grammar used by statement switches: empty versus
non-empty switch blocks, default and pattern labels, record-pattern label wrapping, guards, compact empty rule blocks,
rule entries, statement-group entries, source-only raw single-line rule entries, and switch entry bodies. It leaves
selector condition comments to `ControlConditionPrinter`, and leaves statement switch selection, nested statement
rendering, expression rendering, ordinary block rendering, statement separators, compact source text, compact type text,
modifiers, and width calculations with its callers and collaborators.

`ControlConditionPrinter` renders expressions after statement grammar or statement-switch rendering has selected a
parenthesized control-condition context. It owns compact selector, if, and loop condition text, width-triggered broken
conditions, source-multiline binary `if` condition preservation, line-comment preservation inside condition
parentheses, and the block-comment placement fork that keeps comments before or after the expression according to source
ranges. It also owns the narrow raw-source fallback for recovered selector comments that JavaParser leaves in the source
gap between a parsed condition expression and the closing parenthesis. `ControlConditionMethodCallLayout` owns the
narrow method-call operand policy inside those conditions: source-multiline method-call operands in logical terms,
method-call shape facts used by logical-condition preservation, and top-level control-context width measurement for
broken method-call conditions.

`BlockPrinter` sequences already-rendered statements inside block bodies with orphan comments, printable empty
statements, formatter-pragma separator rules, and source-range-sensitive blank lines without deciding how statements
render.

## Expression Printers

Expression printers own layout decisions after `ExpressionDispatcher` selects a concrete expression kind. The per-kind
owner/helper enumeration is the [formatter-coverage.md](formatter-coverage.md) expression and shared-layout-helper
tables; this section keeps only the cross-cutting rationale a table cannot express.

`ArgumentHeaviness` is the structural (source-shape-free) predicate that forces a call/constructor argument list to break
one-per-line **even when it fits** — a constructor with five or more arguments, or any list that both nests a
call/constructor argument and reaches the nested-token threshold. The method-call, object-creation, and
`super(...)`/`this(...)` printers compose it with their width-driven groups via `Doc.BREAK_PARENT`, and it also
suppresses the block-lambda hug when a sibling argument carries a heavy constructor root. Being a structural break rather
than a width read, the shape is a fixpoint regardless of how the author wrote the arguments.

`MethodCallChainPrinter` composes the chain-split `*Layout` helpers — `ChainFanLayout` (the
canonical-fan break-rule registries and fan builders), `ChainCommentLayout`, `ChainSelectorLambdaLayout`,
`ChainSegmentWidthLayout`, `ChainSegmentPaddingLayout`, `PackedMethodCallChainLayout`, and
`MixedFieldMethodCallChainLayout` — alongside `MethodCallChainSourcePlanner`, delegating chain doc assembly to them while
keeping its own comment-claim traversal. Every fan-admission predicate is a pure function of the AST (no source-shape
read, no width re-measure); the fan shape is built once and *ranked against* the compact/attached alternative by the
renderer at the true column, which is what makes the fanned shape a fixpoint across passes rather than a
source-shape-sensitive per-printer decision.

`TextBlockPrinter` is value-preserving: it reproduces the literal from its source token verbatim so the JLS-computed
`String` value (escapes, significant whitespace, blank lines) is never changed, and it intentionally does not recognize
or reformat embedded languages, because that would change the literal's runtime value. This is the property the
AST-equivalence check (below) leans on to compare text-block content by its JLS value.

## Declaration And Type Printers

Declaration and type printers own Java declaration grammar after `BodyDeclarationDispatcher`, `StatementPrinter`, or
`CompilationUnitPrinter` selects the declaration context. The per-kind owner/helper enumeration is the
[formatter-coverage.md](formatter-coverage.md) declaration table; two boundaries carry rationale a table cannot express.

`CompilationUnitPrinter` splits the orphan comments before the first type at the structural prologue boundary (the last
line of the package/imports/module): comments at or above it stay file-boundary content rendered before `package`, while
a comment below the whole prologue and before the first type is that type's detached leading documentation — a Javadoc
JavaParser left unattached because a blank line separated it from the type — and is rendered immediately above the first
type instead of floated to the package boundary. With no structural prologue the boundary collapses to the first-type
line, leaving the file-boundary slot's behavior unchanged.

`CStyleArrayDeclarators` is the legacy C-style array declarator concern shared by the field, local-variable, and
initializer-layout printers. A declarator whose brackets are written after the name (`String filters[]`) is modeled by
JavaParser as an `ArrayType` whose token range spans the name, so a naive shared type prefix would re-emit the name and
produce non-compiling `String filters[] filters`. This helper shares only the element type as the declaration prefix and
re-emits each declarator's brackets after its own name, keeping them in their original source position. The position is
preserved (not normalized to `String[] filters`) because the AST-equivalence guardrail's `EqualsVisitor` treats the two
bracket origins as structurally different; preserving it keeps the output both valid and AST-equivalent, including
mixed-array-level declarations such as `int rowSpan[], columnCount`.

## Raw, Compact, And Comment Boundaries

`SourceShapePolicy` is the single per-run home for "should the formatter respect the author's source shape here?"
decisions, carried on `JavaFormatContext` and built once per run. It exists so a printer asks one named,
intent-revealing question — never reconstructing source-shape logic inline against raw text or `getRange()` arithmetic —
and so the formatter has exactly one definition, and therefore one fixpoint, per decision.

frmtr's direction is **reprint from scratch** (`docs/proposals/reprint-by-default-break-rules.md`): the formatter does
not preserve the author's line layout, so the only source-shape reads that survive are the *fixpoint-safe* ones whose
answer the formatter's own output reproduces or canonicalizes. What remains on the policy is that small set: the
width-fit gate (`fitsOnOneLine`), blank-line preservation (`hadBlankLineBetween` / `hadBlankLineBefore`), the
contained-comments compact-safety gate (`hasContainedComments`), the logical control-condition shape
(`logicalConditionExpression`), and the try-with-resources section shape (`tryResources`, returning a
`TryResourcesShape`). The method-call / chain / object-creation / lambda hub reflows by width or by a
structural `BreakRule`, so no "preserve the author's line breaks" read exists for it to observe. `SourceShapeException` is the
closed enumerated registry of every permitted read (each tagged `FIXPOINT_SAFE` or `RETIREMENT_TARGET`), and
`SourceShapeExceptionGovernanceTest` fails if an uncategorized policy method appears or if the pinned retirement-target
count (zero) rises — an enforced "reprint by default, these exceptions only" contract.

The containment gate delegates to the run-indexed
`JavaCommentPlacementPolicy.hasContainedComments` rather than re-scanning JavaParser, so the formatter keeps one
containment index; compact-source reconstruction that strips comments on clones (`CompactSourceText`) keeps its own
direct `getAllContainedComments` scan, because the run index reports an unknown clone as comment-free and would change
which reconstruction path is taken. Raw recovery/fallback text generation is **not** a source-shape decision, so it
does not live behind the policy: printers that must emit a node's raw source for recovery or a fallback retrieve it from
`RawSource` directly (the string forms `raw` / `rawWithoutOwnComment`), or from `RawPreservedSource` when that raw
output also needs comment accounting. The policy deliberately does not absorb `SourceText` slicing,
`RawPreservedSource` comment accounting, or recovery-boundary rules; it calls them.

`SourceShapeCouplingGuardTest` enforces the two patterns this consolidation drove to zero: no
`rawSource....contains("\n")` multiline probe and no `previous.end.line + 1` blank-line gap arithmetic outside the
policy and the slicing/raw-output/compact/recovery allowlist (`SourceShapePolicy`, `SourceText`,
`RawSource`, `RawPreservedSource`, `CompactSourceText`, and the recovered-region planners). The broader rule is a
**review checklist, not a test**: when reviewing a Java printer change, reject new `getRange().*.line`,
`getTokenRange()`, or other AST-position layout arithmetic in a printer when an equivalent `SourceShapePolicy` question
exists, and require new source-shape decisions to be added as a named policy method rather than spelled inline. That
range arithmetic legitimately remains inside the recovery and source-slicing helpers above, which is why it is a
checklist item rather than a pattern match.

`RawPreservedSource` is the canonical raw-output boundary for Java printer fallbacks. It wraps `RawSource` output or
already-computed source-derived text in `Doc.Text` while atomically accounting for comments preserved by that output,
including the variant where the node's own attached comment has already been emitted separately.

`RawSource` centralizes JavaParser token-range text access and whitespace normalization used by printer rules when
formatting requires raw source text or compact source-derived text. Its whitespace normalization keeps the newline that
terminates a `//` line comment instead of collapsing it into the surrounding whitespace run: a line comment runs to
end-of-line in Java, so collapsing that newline would pull the next token (an element separator comma, a closing brace,
the next operand) onto the comment line where `//` swallows it, producing non-compiling text. Compact text that contains
a line comment is therefore multi-line, which makes flat-width probes reject it and fall back to a structured layout.
`CompactSourceText` centralizes source-equivalent
compact text: raw string-literal token spelling, recursive field-access reconstruction, comment-free expression
reconstruction for anonymous-class headers, generic delimiter spacing cleanup for type-like snippets, comma joining, and
clone-before-comment-removal behavior. `LayoutWidth` centralizes the indentation baselines used for flat-width probes so
statement, field, and chain helpers do not each recreate their own width arithmetic.

The surviving syntax-specific reads (`tryResources`, `logicalConditionExpression`) use JavaParser node ranges first and
bounded `SourceText` slices between neighboring AST-owned syntax, such as the gap after the last try resource, rather
than scanning an entire declaration or statement for delimiters and keywords. Printers still decide what doc to build
after a shape read is true; the read's fixpoint-safety is what lets that decision survive a reprint.

A printer that consumes a source-shape read still owns whether preserving the author's shape is meaningful for that
construct. The try-with-resources resource section illustrates why a surviving read is fixpoint-safe. `TryStatementLayout`
(the try-statement cluster `StatementPrinter` delegates to) keeps the `tryResources().spansMultipleLines()` read for a
section that holds two or more resources (the author's one-resource-per-line layout is deliberate and stable), but for a
single resource it collapses the section to flat whenever the flat `try (...) {}`
form fits the width. A single resource "spans multiple lines" only because its own initializer call was broken across
lines, which is incidental to argument layout rather than a resource-section shape; honoring it would let each pass
re-observe the prior pass's break and refuse the flat form forever, so the section flip-flops between the opener break and
the attached-argument break (the issue #98 non-convergence). The collapse rebuilds the flat resource text through a
comment-free declaration reconstruction (modifiers, type, and per-variable `name = compact(init)`) rather than
token-text normalization, so an argument-broken declaration collapses with canonical interior spacing. Line
comments that sit on the line directly above `try (` belong to the try statement, not the resource section, and the
enclosing block already renders them before `try`; the in-section leading-comment gate filters comments that begin before
the `try` keyword so the statement's own leading comment does not force the section to stay broken.

`BinaryExpressionPrinter`'s broken-binary method-call operand break is the same boundary for argument layout (issue
#119, the third wrap-convergence regime after #98/#117). When a binary is laid out one operator per line, whether a
method-call operand keeps its argument list flat or explodes it is decided purely from the flat operand's width on its
broken line — `methodCallOperandShouldBreak` and its siblings (`methodCallBinaryOperandShouldBreak`,
`leadingOperatorMethodCallBinaryOperandShouldNest`, `shouldBreakEndPositionMethodCallOperand`) are width+AST-only and
consult no source-shape read (they have no "were the call's arguments multiline?" read to reach for).
Basing the break on whether the author broke the call's arguments would let an exploded operand re-observe its own broken
shape every pass, so identical operands in one chain rendered differently and near the width boundary the operand never
settled. The width-only decision means the same
binary AST formats to one shape regardless of how the call's arguments were wrapped in source. (The comment-aware
operand paths still route through the comment-between-operands queries, which are independent of this break decision.)

`ObjectCreationLayoutPolicy` centralizes constructor-call source-shape decisions that several expression contexts share:
when source-multiline constructor arguments are meaningful enough to preserve, when returned object creations should
preserve source-multiline constructor arguments, and whether a constructor root may stay compact when a surrounding
method-call chain is forced to break. It does not render constructor docs; direct constructor printing, return
expressions, and method-call chain planning keep their surrounding grammar decisions.

`MethodCallChainSourcePlanner` owns method-call chain source-shape planning before `MethodCallChainPrinter` assembles
docs: structural root collection, selector-line preservation, source-multiline chain signals for statement routing,
type-like and builder-root promotion, and delegation to `ObjectCreationLayoutPolicy` for constructor-root compactness
before broken chains. `MethodCallPrinter` keeps the canonical method-call argument-list renderer used by direct calls,
initializer method-call openers, and try-resource method-call openers, while delegating chain root, comment, and
final-tail assembly to `MethodCallChainPrinter`. `BreakableArgumentExpressionPrinter` keeps the argument-expression
break policy reusable across those entry points so over-wide or source-multiline binary string concatenations do not get
collapsed by a surrounding argument-list fit.

`CommentedModulePrinter`, `CommentedMethodSignaturePrinter`, and `CommentedInterfacePrinter` own raw-source escape
hatches for module declarations, method signatures, interface headers, and abstract method signatures whose inline
comments are not exposed by JavaParser in a structured form useful to normal printing.

`CommentedExpressionListPrinter` centralizes broken parenthesized expression lists whose argument gaps contain line
comments. `SourceOrderedCommentInterleaver` centralizes source-order merging of syntax siblings with orphan comments for
block-like printers while callers retain grammar-specific separator decisions. `CommentedTokenText` centralizes the
small comment-aware tokenization and token-line text helpers used by raw-source fallback formatting.

`JavaCommentMap` captures JavaParser's own, orphan, and contained comment associations once at the
`JavaPrinter.print(CompilationUnit)` boundary. `JavaCommentPlacementPolicy` reads that map to answer leading,
adjacent-leading line-cluster, trailing-line, orphan, contained, before-first-child, between-neighbor, after-last-child,
and same-line placement queries without rendering docs or mutating claim state. Adjacent-leading queries stay anchored
to the node's JavaParser range for ordinary statement-leading comments; before-first and between-neighbor line-comment
queries keep ordinary sibling gaps anchored to the next node's parser range and use a narrow range-start cluster recovery
only when JavaParser attaches a standalone line-comment cluster to the first child or container that visually precedes
the code token.

`CommentTracker` is the package-private per-run comment owner. Comments are trivia, not AST nodes, so more than one
printer path can legitimately reach the same comment, and a naive build-order race can drop it or print it twice. The
tracker settles that up front with a **record-only dry-run pre-pass**. `JavaPrinter.print(CompilationUnit)` runs the
full print traversal once with the tracker in recording mode (`beginRecording`): each family (leading, adjacent-leading,
trailing, own, orphan, interleaved) offers its comments, the tracker records the *first* `(node, slot)` to offer each
comment into an identity-keyed `ownership` map, and the scratch document is discarded. It then resets the per-render
state (`endRecordingAndReset` — keeping only the `ownership` map) and runs the real render. The `(node, slot)` key is an
`OwnerKey`: an anchor `Node` compared by **reference identity** (not JavaParser's deep structural `equals`, so two
syntactically identical comments stay distinct owners) paired with an `OwnerSlot` role — `LEADING`, `ADJACENT_LEADING`,
`TRAILING` (plus the `CONTENT_TRAILING` / `ENCLOSED_TRAILING` / `UNATTACHED_TRAILING` variants that let an outer
envelope, an inner content renderer, and an enclosing construct co-offer the same node's trailing comment without their
keys colliding), `OWN`, `ORPHAN`, and `INTERLEAVED`. The dry-run deliberately records the *real-traversal* first
claimant rather than a source-order approximation, because a pure source-order rule diverges from the emergent winner on
the contested leading/own families; recording the true traversal owner is what keeps the two-pass model byte-neutral.

The real render then routes every family through the **claim-neutral `ownedComment` rail**: a slot renders a comment
only when the pre-pass recorded that exact `(node, slot)` as its owner, and every non-owner offer renders `Doc.EMPTY`.
Because emptiness is a pure function of recorded ownership and the rail mutates no per-render "printed" state, an owner
may emit the same comment through the rail in more than one eagerly-built ranked layout arm (`Doc.bestFitting` /
`Doc.conditionalGroup`) without dropping or duplicating it — the renderer keeps only the arm it picks, and the losing
arms' identical offer is harmless. Comment *text* rendering stays in one routine (`JavaFormatter.commentDoc`) keyed on
the classified kind, so the rail decides only *whether* a comment renders in a given slot, never *how* it looks. The
debug missed-comment guardrail is correspondingly keyed on the recorded-ownership set (plus the raw-preserved marks
supplied by `RawPreservedSource`), because the recorded owner — not a printed flag — is now the evidence that a comment
reached output.

`JavaCommentKind` and `JavaCommentTrivia` classify JavaParser comments as line, block, Javadoc, Markdown (JEP 467 `///`
documentation comments), or unknown trivia, expose reusable source-position queries through `CommentIndex`, and give
`CommentTracker` the identity-based comment classification it keys ownership on without making printers repeat raw
subclass and range checks. `MarkdownComment` is a JavaParser `JavadocComment` subclass, so it is classified before Javadoc and still answers
`isJavadoc()` for documentation-placement decisions, but `JavaFormatter.commentDoc` renders it through the `///`
line-comment family rather than the reflowing Javadoc path: a contiguous `///` run is one multi-line node whose
continuation lines carry their original source indentation, so each line's leading whitespace is stripped and re-emitted
at the structural indent (mirroring a `//` block) to keep the rendering idempotent instead of drifting one indent level
per format pass.

`CommentIndex` centralizes read-only source-position classification for comments, including explicit-fallback begin/end
line lookups, line/column comparisons, line-range containment, same-begin-line checks, source-order sorting, contained
line-comment selection, and between-node comment gaps. It does not render comments or record comment ownership.

## Recovery Helpers

For parse-problem `RECOVER` runs, `JavaFormatContext` also carries `SourceText`, `RecoveredListPlanner`, and
`RecoveredSourceRegions` into the printer graph.

`SourceText` maps JavaParser line/column ranges to half-open source offsets and slices the original source text for
recovery paths, with `SourceRegion` carrying the offset span plus debug-oriented line/column labels. `RecoveredListPlanner`
plans formatter-owned sibling lists into valid siblings and raw gaps, while `RecoveredSourceRegions` emits those raw gaps
as labeled source islands and accounts fully contained comments.

The current recovered sibling-list owners are `BlockPrinter`, `MemberBlockPrinter`, `CompilationUnitPrinter`,
`ModuleBlockPrinter`, `SwitchPrinter`, `EnumDeclarationPrinter`, and `AnnotationDeclarationPrinter`. Statement,
expression, and declaration envelopes keep conservative guards so recovered regions outside these slices do not flow
through unsupported printers as normal parsed nodes.

The implemented behavior and rationale are documented in [error-recovery-behavior.md](error-recovery-behavior.md).

## Guardrails

`FormatterGuardrails` hosts opt-in internal pipeline diagnostics. Setting `dev.lanwen.frmtr.debug.guardrails=true`
enables development-only checks such as duplicate comment-claim failures, end-of-format missed-comment accounting
failures, and transform identity checks while the default path keeps existing best-effort skip behavior and formatter
output unchanged.

Transform checks run from `JavaTransformPipeline` and assert that source-equivalent transforms keep the same
`CompilationUnit` root, preserve existing JavaParser child-node identities across the tree, preserve JavaParser-visible
comment identities, and reorder existing import declarations without cloning or moving their attached comments.
Raw-source fallback paths that intentionally preserve JavaParser-visible comments go through `RawPreservedSource`, so the
final assertion reports only comments that were neither structured-printed nor deliberately raw-preserved.

### AST-equivalence verify mode

A separate opt-in check, gated by its own system property `dev.lanwen.frmtr.debug.verify` (read by
`FormatterGuardrails.verifyEnabled()`), re-parses the formatter's output and asserts it represents the same program as
the input. It exists because the comment and transform guardrails never observe the *printer*: they cannot catch a
printer change that alters the token stream (the historical enum-separator data-loss bug). When the toggle is on,
`JavaFormatter.format` re-parses the rendered output with the same `JavaParser` configuration used for the input
(stored tokens, attributed comments, language level) and hands both compilation units to
`FormatterGuardrails.assertAstEquivalent`, which delegates the comparison to `AstEquivalence`. A divergence throws an
actionable `AssertionError` naming what differed (a dropped/duplicated import, or the first structural divergence with a
minimized re-print excerpt), surfaced through `Frmtr.format` as a `FormatterException.internal(...)`.

`AstEquivalence` decides equivalence structurally with JavaParser's own `EqualsVisitor` after normalizing both trees
identically: comments are stripped; imports are sorted with the formatter's own `ImportSortTransform.FORMATTER_IMPORT_ORDER`
(so the deliberate reorder cancels, while a dropped or duplicated import still differs); parentheses (`EnclosedExpr`) are
unwrapped (sound because precedence is encoded in the tree shape, not the parentheses, and the comparison is structural
rather than a printed-string compare); single-parameter lambda parenthesization is canonicalized; redundant empty
statements inside blocks are dropped; and modifier order is sorted. Text-block **content** is compared by its JLS String
value (each text block on both sides is replaced by a `StringLiteralExpr` of `translateEscapes()`): the formatter renders
text blocks verbatim (see `TextBlockPrinter`), so re-indentation does not change the value while any real content change
does and is flagged. (An earlier cut excluded text-block content because the formatter then rewrote recognized embedded
snippets and so changed the literal's value; once `TextBlockPrinter` was made value-preserving, content came back in
scope, and comparing it across the corpus now also guards that the formatter keeps preserving text-block values.) The
check is **off by
default** so normal `format(...)` runs do no extra work and stay byte-identical; it is enabled for the whole
`frmtr-core` test suite (via the test task's system property) so every golden fixture is also AST-checked, and is
skipped for recovered (partially parsed) inputs, where AST-equivalence is ill-defined.

`IdempotencePropertyTest` (roadmap B3, layer 2) reuses `AstEquivalence.equivalent(...)` directly as an explicit
property over a corpus broader than the golden fixtures: every fixture input verbatim, two parse-preserving whitespace
perturbations of each (rebuilt from JavaParser's token stream, rewriting only whitespace tokens so literal and comment
content is never touched and the program parses to the same tree), and diverse hand-written snippets. It asserts
one-pass idempotence + semantic preservation on well-shaped inputs and semantic preservation + parse-stability on
perturbed inputs, and deliberately never asserts convergence (`format(perturbed(x)) == format(x)`), because the
formatter preserves intentional source shape. Perturbed shapes that expose genuine defects are excluded as documented
findings rather than masked.
