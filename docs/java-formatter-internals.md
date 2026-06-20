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

`SwitchPrinter` renders switch expressions and reusable switch-entry grammar used by statement switches: selector line
comments, empty versus non-empty switch blocks, default and pattern labels, record-pattern label wrapping, guards,
compact empty rule blocks, rule entries, statement-group entries, source-only raw single-line rule entries, and switch
entry bodies. It leaves statement switch selection, nested statement rendering, expression rendering, ordinary block
rendering, statement separators, compact source text, compact type text, modifiers, and width calculations with its
callers and collaborators.

`ControlConditionPrinter` renders expressions after statement grammar or statement-switch rendering has selected a
parenthesized control-condition context. It owns compact selector, if, and loop condition text, width-triggered broken
conditions, source-multiline binary `if` condition preservation, and the block-comment placement fork that keeps comments
before or after the expression according to source ranges. `ControlConditionMethodCallLayout` owns the narrow
method-call operand policy inside those conditions: source-multiline method-call operands in logical terms, method-call
shape facts used by logical-condition preservation, and top-level control-context width measurement for broken
method-call conditions.

`BlockPrinter` sequences already-rendered statements inside block bodies with orphan comments, printable empty
statements, formatter-pragma separator rules, and source-range-sensitive blank lines without deciding how statements
render.

## Expression Printers

Expression printers own layout decisions after `ExpressionDispatcher` selects a concrete expression kind:

- `AssignmentExpressionPrinter`: assignment wrapping, full-expression-statement width gates, suffixed enclosed values,
  binary-value continuations, method-call and conditional assignment hooks, and nested assignment continuations.
- `ReturnExpressionPrinter`: return-value wrapping after statement dispatch selects `return value;`.
  `ReturnBinaryExpressionLayout` owns direct binary return layout, including method-call-left return continuations and
  the choice to keep source-multiline method-call arguments with normal expression rendering. Return-specific constructor
  source-shape policy is delegated to `ObjectCreationLayoutPolicy`.
- `ConditionalExpressionPrinter`: ternary layout for assignment values, variable initializers, comments around `?` and
  `:`, nested conditional branches, and binary condition wrapping.
- `LambdaExpressionPrinter`: expression versus block bodies, parenthesized lambdas, broken logical bodies, and lambda
  arguments that can be hugged by method calls or object creation. `LambdaParameterHeaderLayout` owns the canonical
  parameter/header rendering used by lambda and call layouts: parameter parentheses, commented parameter reconstruction,
  width-triggered header breaks, and source-multiline parameter detection. `ExpressionLambdaArgumentLayout` owns the
  call-argument side of expression lambdas: shared eligibility, first-line/body-opener planning, and packed method-call
  or constructor bodies for call and chain printers. `ExpressionLambdaClosingLayout` owns source-shaped call-closing
  placement for simple logical expression-lambda bodies, and `PackedLambdaBody` carries the selected body doc with that
  closing placement. `SourceMultilineLambdaCallLayout` owns source-multiline expression lambda method-call bodies and
  block-lambda parameter lists that were already multiline in source.
- `MethodCallPrinter`: ordinary method-call argument-list rendering, empty argument comments, commented argument-gap
  fallback lists, over-wide binary arguments, and suffixes on enclosed scopes. `TextBlockArgumentSourceLayout` owns the
  source indentation recovery for text-block arguments that appear inside expression-lambda method-call bodies; ordinary
  text-block literal content remains with `TextBlockPrinter`.
  `BreakableArgumentExpressionPrinter` owns the shared break policy for method-call arguments whose expression form must
  stay broken, including over-wide or source-multiline binary string concatenations reused by initializer and
  try-resource opener paths. `MethodCallChainPrinter`
  owns chain doc assembly: chain comments including same-line comments between chained calls and leading line-comment
  clusters before chained segments, source-multiline first-segment lambda call attachment via
  `SourceMultilineLambdaCallLayout`, source-multiline single-object-creation call statements, field-root fluent-chain
  preservation for already-multiline statement chains, compact-root plus broken-final-segment calls, root promotion, and
  final-segment tails.
- `MethodReferencePrinter`: method references, type-argument suffix text, and parenthesized-scope suffixes.
- `EnclosedSuffixDispatcher`: the bridge used when a broken enclosed expression may need a method-call or
  method-reference suffix preserved.
- `TextBlockPrinter`: value-preserving text-block rendering. It reproduces the literal from its source token verbatim so
  the JLS-computed `String` value (escapes, significant whitespace, blank lines) is never changed; it intentionally does
  not recognize or reformat embedded languages, because that would change the literal's runtime value.
- `ObjectCreationPrinter`: constructor-call prefixes, comments around `new` and created types, forced constructor
  argument breaks selected by `ObjectCreationLayoutPolicy`, generic type-body breaks, huggable lambda arguments, commented
  constructor argument gaps, and anonymous class body member sequencing.
- `ArrayExpressionPrinter`: array access, array creation, array initializer braces, compact literal initializer
  acceptance, array-creation type breaks, forced initializer breaks, and initializer comments.
- `AnnotationExpressionPrinter`: marker, normal, and single-member annotation shapes, annotation member pairs, compact
  annotation text, raw string-literal tokens inside compact annotation values, annotation array member values including
  line comments between values, trailing line comments, and binary annotation-value continuations.
- `BinaryExpressionPrinter`: binary flattening, operator position, line comments between operands, precedence
  parentheses, end-position method-call operand breaks, and cast-division continuation decisions.
- `CastExpressionPrinter`: cast type layout, line-width-aware intersection and generic type-body breaks, operand rendering,
  and nested cast depth checks.
- `EnclosedExpressionPrinter`: parenthesized expression layout and broken enclosed scopes that keep array, method-call,
  and method-reference suffixes attached.
- `InstanceOfExpressionPrinter`: instance-check continuations and binary-operator-position-aware placement.
- `FieldAccessPrinter`: dotted field access and comment-sensitive name splitting.

## Declaration And Type Printers

Declaration and type printers own Java declaration grammar after `BodyDeclarationDispatcher`, `StatementPrinter`, or
`CompilationUnitPrinter` selects the declaration context:

- `CompilationUnitPrinter`: whole-file layout for source-leading package comments, orphan comments, package
  declarations, import sections, optional module declarations, formatter-pragma adjacency and separator rules between
  top-level declarations, compact unnamed-class member expansion, and trailing orphan comments.
- `PackageDeclarationPrinter` and `ImportDeclarationPrinter`: package and import line rendering while compilation-unit
  ordering stays with `CompilationUnitPrinter` and import ordering stays with `ImportSortTransform`.
- `ModuleDeclarationPrinter` and `ModuleBlockPrinter`: module headers, raw commented-module fallback selection,
  structured directive layout, and module body sequencing.
- `TypePrinter`: shared type-clause rendering, declaration type-parameter flat text, compact type-list joining, and
  breakable generic type bodies.
- `ClassOrInterfaceDeclarationPrinter`, `RecordDeclarationPrinter`, `EnumDeclarationPrinter`, and
  `AnnotationDeclarationPrinter`: type-specific headers, record component type bodies, record full-header wrapping,
  body starts, member sequencing handoffs, and type-specific recovery boundaries.
- `FieldDeclarationPrinter` and `VariableDeclarationPrinter`: field and local variable declaration layout, declaration
  prefixes, local-only declaration-prefix decisions, and handoff to `VariableInitializerLayout`.
  `VariableInitializerLayout` owns shared initializer policy including equals-line cast type-body breaks, direct
  block-lambda openers, object-creation-root method-call opener grouping through the canonical method-call argument-list
  renderer, switch-expression body preservation, huggable block-lambda method-call initializers, comments around `=`, and
  initializer-specific width fallbacks.
- `ConstructorDeclarationPrinter`, `MethodDeclarationPrinter`, `InitializerDeclarationPrinter`,
  `CallableSignaturePrinter`, and `ThrowsClausePrinter`: callable headers, signatures, throws-clause placement,
  callable parameter annotation prefixes, body-versus-semicolon suffixes, and initializer bodies.
- `DeclarationPrefixPrinter`: leading annotation docs, comments between declaration-leading annotations, inline annotation
  text after modifiers, declaration-annotation classification for member spacing, and canonical modifier ordering.
- `MemberBlockPrinter`: already-rendered type member sequencing with orphan comments, opening-brace line comments, and
  source-range-sensitive blank lines.

## Raw, Compact, And Comment Boundaries

`RawPreservedSource` is the canonical raw-output boundary for Java printer fallbacks. It wraps `RawSource` output or
already-computed source-derived text in `Doc.Text` while atomically accounting for comments preserved by that output,
including the variant where the node's own attached comment has already been emitted separately.

`RawSource` centralizes JavaParser token-range text access and whitespace normalization used by printer rules when
formatting requires raw source text or compact source-derived text. `CompactSourceText` centralizes source-equivalent
compact text: raw string-literal token spelling, recursive field-access reconstruction, comment-free expression
reconstruction for anonymous-class headers, generic delimiter spacing cleanup for type-like snippets, comma joining, and
clone-before-comment-removal behavior. `LayoutWidth` centralizes the indentation baselines used for flat-width probes so
statement, field, and chain helpers do not each recreate their own width arithmetic.

`SourceShape` centralizes source-line-shape predicates used to preserve existing multiline forms when the structured
formatter has an otherwise equivalent compact form. It uses JavaParser node ranges first and bounded `SourceText` slices
between neighboring AST-owned syntax, such as the first thrown exception line or the gap after the last try resource,
rather than scanning an entire declaration or statement for delimiters and keywords. It also owns the shared predicates
for method-call operands, nested source-multiline method-call arguments, and logical control-condition source shape.
Printers still decide what doc to build after a shape predicate is true.

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
and same-line placement queries without rendering docs or mutating claim state.

`CommentTracker` is the package-private per-run comment accounting helper for comments selected by
`JavaCommentPlacementPolicy` as leading, adjacent-leading, trailing, or orphan comments. It renders comments through
`JavaCommentTrivia`, stores raw-preserved comment marks supplied by `RawPreservedSource`, and exposes the debug-only
end-of-compilation-unit assertion that all JavaParser-visible comments were either printed or raw-accounted.

`JavaCommentKind` and `JavaCommentTrivia` classify JavaParser comments as line, block, Javadoc, or unknown trivia, expose
reusable source-position queries through `CommentIndex`, and let `CommentTracker` preserve identity-based printed-comment
claims without making printers repeat raw subclass and range checks.

`CommentIndex` centralizes read-only source-position classification for comments, including explicit-fallback begin/end
line lookups, line/column comparisons, line-range containment, same-begin-line checks, source-order sorting, contained
line-comment selection, and between-node comment gaps. It does not render comments or mutate printed-comment state.

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
