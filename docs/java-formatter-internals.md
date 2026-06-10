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

`JavaPrinter` wires the current Java formatting collaborators for common type declarations, fields, methods,
constructors, statements, and outer expression callbacks. It creates one per-run `JavaFormatContext` for formatter-wide
options, comment tracking, comment placement policy, formatter pragmas, raw source recovery, compact source text, and
source-position comment placement, plus shared source-shape layout policy for constructor calls, then passes that context
only where it is clearer than threading those shared dependencies separately. It keeps the v1 style deliberately
opinionated and sparse on options.

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
before or after the expression according to source ranges.

`BlockPrinter` sequences already-rendered statements inside block bodies with orphan comments, printable empty
statements, formatter-pragma separator rules, and source-range-sensitive blank lines without deciding how statements
render.

## Expression Printers

Expression printers own layout decisions after `ExpressionDispatcher` selects a concrete expression kind:

- `AssignmentExpressionPrinter`: assignment wrapping, full-expression-statement width gates, suffixed enclosed values,
  binary-value continuations, method-call and conditional assignment hooks, and nested assignment continuations.
- `ReturnExpressionPrinter`: return-value wrapping after statement dispatch selects `return value;`.
- `ConditionalExpressionPrinter`: ternary layout for assignment values, variable initializers, comments around `?` and
  `:`, nested conditional branches, and binary condition wrapping.
- `LambdaExpressionPrinter`: lambda parameter parentheses, commented parameter reconstruction, expression versus block
  bodies, parenthesized lambdas, broken logical bodies, and lambda arguments that can be hugged by method calls or object
  creation.
- `MethodCallPrinter`: ordinary method-call argument dispatch, empty argument comments, commented argument-gap fallback
  lists, text-block arguments, over-wide binary arguments, and suffixes on enclosed scopes. `MethodCallChainPrinter`
  owns chain doc assembly: chain comments including same-line comments between chained calls and leading line-comment
  clusters before chained segments, source-multiline single-object-creation call statements, field-root fluent-chain
  preservation for already-multiline statement chains, compact-root plus broken-final-segment calls, root promotion, and
  final-segment tails.
- `MethodReferencePrinter`: method references, type-argument suffix text, and parenthesized-scope suffixes.
- `EnclosedSuffixDispatcher`: the bridge used when a broken enclosed expression may need a method-call or
  method-reference suffix preserved.
- `TextBlockPrinter`: narrow content probes, formatted text-block reconstruction, raw fallback rendering, same-line
  closing-delimiter preservation, escaped triple-quote source spelling, and parent-depth content indentation.
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
  block-lambda openers, object-creation-root method-call opener grouping, switch-expression body preservation, huggable
  block-lambda method-call initializers, comments around `=`, and initializer-specific width fallbacks.
- `ConstructorDeclarationPrinter`, `MethodDeclarationPrinter`, `InitializerDeclarationPrinter`,
  `CallableSignaturePrinter`, and `ThrowsClausePrinter`: callable headers, signatures, throws-clause placement,
  body-versus-semicolon suffixes, and initializer bodies.
- `DeclarationPrefixPrinter`: leading annotation docs, inline annotation text after modifiers, declaration-annotation
  classification for member spacing, and canonical modifier ordering.
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
rather than scanning an entire declaration or statement for delimiters and keywords. Printers still decide what doc to
build after a shape predicate is true.

`ObjectCreationLayoutPolicy` centralizes constructor-call source-shape decisions that several expression contexts share:
when source-multiline constructor arguments are meaningful enough to preserve, and whether a constructor root may stay
compact when a surrounding method-call chain is forced to break. It does not render constructor docs; direct constructor
printing, return expressions, and method-call chain planning keep their surrounding grammar decisions.

`MethodCallChainSourcePlanner` owns method-call chain source-shape planning before `MethodCallChainPrinter` assembles
docs: structural root collection, selector-line preservation, source-multiline chain signals for statement routing,
type-like and builder-root promotion, and delegation to `ObjectCreationLayoutPolicy` for constructor-root compactness
before broken chains. `MethodCallPrinter` keeps ordinary argument rendering and delegates chain root, comment, and
final-tail assembly to `MethodCallChainPrinter`.

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
