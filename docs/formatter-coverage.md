# Formatter Coverage Map

This map records which JavaParser AST kinds are currently owned by structured formatter printers and which paths still
use raw or compact source-derived text. It is an audit aid for formatter maintainability work, not a test matrix or a
promise that every JavaParser subtype has a dedicated structured rule.

When formatter ownership moves, update this file in the same change as the code. Output-changing work still belongs in
golden fixtures and idempotence/reparse coverage; this map only explains the decision tree that selects existing
owners.

## Whole-File And Transform Flow

Single-file formatting flows through these ownership boundaries:

1. `Frmtr.format(...)` applies `FormatterOptions`.
2. `JavaFormatter` handles pragma-required opt-in, JavaParser configuration, token/comment storage, parse errors, and
   the transform stage.
3. `JavaTransformPipeline` applies source-equivalent AST transforms. The current transform is `ImportSortTransform`,
   which orders existing imports before printing. When `dev.lanwen.frmtr.debug.guardrails=true`, the pipeline asks
   `FormatterGuardrails` to assert that transforms keep the same `CompilationUnit` root and preserve existing
   JavaParser child-node identities across the tree, preserve JavaParser-visible comment identities, and reorder
   existing import declarations without replacing nodes or moving attached comments.
4. `JavaPrinter` creates the per-run `JavaFormatContext`, shared type rendering, and the `ExpressionPrinters`,
   `DeclarationPrinters`, and `StatementPrinters` composer groups. `JavaPrinter.print(CompilationUnit)` builds the
   `JavaCommentMap` through `JavaCommentPlacementPolicy`, delegates whole-file layout through `DeclarationPrinters` to
   `CompilationUnitPrinter`, then asks `CommentTracker` to run the debug-only missed comment guardrail.
5. `CompilationUnitPrinter` sequences file-leading comments, package declarations, imports, optional module
   declarations, pragma-sensitive top-level declaration separators, compact unnamed-class members, and trailing orphan
   comments.

Top-level AST ownership:

| JavaParser AST kind | Primary owner | Notes |
| --- | --- | --- |
| `CompilationUnit` | `CompilationUnitPrinter` | Owns package/import/module/type ordering, pragma-sensitive top-level declaration separation, and orphan comment placement around the first and last type. |
| `PackageDeclaration` | `PackageDeclarationPrinter` | Owns package line text and source-leading package comments. |
| `ImportDeclaration` | `ImportDeclarationPrinter` | Owns one import line. Import ordering is owned by `ImportSortTransform`; import block separation is owned by `CompilationUnitPrinter`. |
| `ModuleDeclaration` | `ModuleDeclarationPrinter` and `ModuleBlockPrinter` | `ModuleDeclarationPrinter` owns header and raw commented-module fallback selection; `ModuleBlockPrinter` owns structured directives. |
| Top-level `BodyDeclaration<?>` | `BodyDeclarationRuleEnvelope` then `BodyDeclarationDispatcher` | The envelope applies body-level pragma/raw/leading-comment gates; the dispatcher narrows formatted content to declaration printers. |
| Compact unnamed-class members | `CompilationUnitPrinter` then `BodyDeclarationRuleEnvelope` then `BodyDeclarationDispatcher` | JavaParser's compact wrapper class is not printed as a class; its members are treated as top-level declarations. |

## Body Declarations

`BodyDeclarationRuleEnvelope` owns the body-declaration pragma gate, raw declaration recovery, and the outer leading
comment slot. Formatted declaration content then routes through `BodyDeclarationDispatcher`, which owns only broad
subtype dispatch and compact fallback for unknown declaration kinds. Declaration printers own declaration-specific
layout, and `MemberBlockPrinter` owns member sequencing inside type bodies.

| JavaParser AST kind | Structured owner | Fallback or boundary notes |
| --- | --- | --- |
| `ClassOrInterfaceDeclaration` | `ClassOrInterfaceDeclarationPrinter` | Interfaces whose headers contain inline block comments route through `CommentedInterfacePrinter` and `RawPreservedSource`. |
| `RecordDeclaration` | `RecordDeclarationPrinter` | Owns record headers, component lists including breakable generic component type bodies, full-header width checks, implements continuations, and body start layout. Members return to `MemberBlockPrinter` and the body-declaration envelope/dispatcher path. |
| `EnumDeclaration` | `EnumDeclarationPrinter` | Owns enum headers, constants, enum semicolons, body orphan comments, and enum constant argument layout. Ordinary members return to body dispatch; recovered enum-constant gaps stay inside this printer. |
| `AnnotationDeclaration` | `AnnotationDeclarationPrinter` | Owns annotation type headers and member blocks; recovered annotation-member gaps stay inside this printer. |
| `AnnotationMemberDeclaration` | `AnnotationDeclarationPrinter` | Owns annotation member declarations and default values, delegating default expressions back through expression rendering. Unsafe recovered members preserve their default-value source as raw body gaps. |
| `FieldDeclaration` | `FieldDeclarationPrinter`, `VariableInitializerLayout` | `FieldDeclarationPrinter` owns field declarations and variable sequencing; `VariableInitializerLayout` owns initializer break decisions, direct block-lambda opener grouping, object-creation-root method-call opener grouping through the canonical method-call argument-list renderer, huggable block-lambda method-call initializers, switch-expression initializer body preservation, comments around `=`, and shared variable initializer policy. |
| `MethodDeclaration` | `MethodDeclarationPrinter` | Commented signatures with small bodies can route through `CommentedMethodSignaturePrinter` and `RawPreservedSource`; ordinary bodies delegate to `BlockPrinter`. |
| `CompactConstructorDeclaration` | `ConstructorDeclarationPrinter` | Owns compact constructor headers and delegates bodies to `BlockPrinter`. |
| `ConstructorDeclaration` | `ConstructorDeclarationPrinter` | Owns constructor headers, parameter lists, throws clauses, and body delegation. |
| `InitializerDeclaration` | `InitializerDeclarationPrinter` | Owns static and instance initializer prefixes and delegates bodies to `BlockPrinter`. |
| Other `BodyDeclaration<?>` subtypes | `BodyDeclarationRuleEnvelope` then `BodyDeclarationDispatcher.rawDeclaration(...)` | Leading comments are printed by the envelope; the declaration body is emitted as `RawPreservedSource.rawWithoutOwnComment(..., CompactSourceText.compact(...))`. |

Body-level raw routing:

- `FormatterPragmas.bodyAction(...)` can force a declaration through raw preserved source when formatting is disabled or
  ignored.
- `FORMAT` passes through the envelope's leading-comment gate before structured rendering.
- `RawPreservedSource` must wrap any raw/source-derived body fallback so debug comment accounting sees comments
  intentionally preserved by raw text.

## Statements

`StatementRuleEnvelope` owns statement-level formatter pragma state, raw statement recovery, leading comments, trailing
line comments, and `TryStmt` comment exceptions. Formatted statement content then routes to `StatementPrinter`, which
owns the statement-kind branch. Switch statements are selected there with the other statements, then delegate reusable
switch-entry layout to `SwitchPrinter`.

| JavaParser AST kind | Primary owner | Notes |
| --- | --- | --- |
| `SwitchStmt` | `StatementRuleEnvelope` then `StatementPrinter` then `SwitchPrinter.switchStatement(...)` | Statement-level raw/comment gates run first; `StatementPrinter` selects the switch-statement branch with the other statements, then delegates labels, guards, entries, and switch body layout to switch-owned formatting. |
| `BlockStmt` | `StatementPrinter` then `BlockPrinter` | `BlockPrinter` owns statement sequencing, orphan comments, printable empty statements, and block separators. |
| `ReturnStmt` | `StatementPrinter` and `ReturnExpressionPrinter` | `StatementPrinter` owns statement assembly; `ReturnExpressionPrinter` owns return-value wrapping. |
| `ThrowStmt` | `StatementPrinter` | Throws expressions delegate through expression rendering. |
| `YieldStmt` | `StatementPrinter` | Used by switch entries and ordinary statement dispatch. |
| `ExplicitConstructorInvocationStmt` | `StatementPrinter` | Owns `this(...)` and `super(...)` calls, type arguments, and huggable lambda arguments. |
| `ExpressionStmt` | `StatementPrinter` | Local `VariableDeclarationExpr` routes to `VariableDeclarationPrinter`; wide method-call statements, source-multiline field-root fluent chains, and preserved multiline single-object-creation calls can route through `MethodCallPrinter`; other expressions delegate through `ExpressionRuleEnvelope` and `ExpressionDispatcher`. |
| `EmptyStmt` | `StatementPrinter` | Emits `;`. |
| `AssertStmt` | `StatementPrinter` | Uses compact source text for check/message shape. |
| `BreakStmt` | `StatementPrinter` | Owns label and inline block-comment handling. |
| `ContinueStmt` | `StatementPrinter` | Owns label and label-comment handling. |
| `LabeledStmt` | `StatementPrinter` | Owns label/comment extraction, then delegates nested block or statement formatting back to the normal owners. |
| `LocalClassDeclarationStmt` | `StatementPrinter` then `BodyDeclarationRuleEnvelope` then `BodyDeclarationDispatcher` | The contained class declaration is formatted as a body declaration. |
| `LocalRecordDeclarationStmt` | `StatementPrinter` then `BodyDeclarationRuleEnvelope` then `BodyDeclarationDispatcher` | The contained record declaration is formatted as a body declaration. |
| `IfStmt` | `StatementPrinter` and `ControlConditionPrinter` | `StatementPrinter` owns if/else chain structure, empty branches, and between-branch comments; `ControlConditionPrinter` owns parenthesized condition layout including source-multiline binary condition breaks. Nested statements return through statement dispatch. |
| `WhileStmt` | `StatementPrinter` | Conditions route through `ControlConditionPrinter`; bodies route through statement/block owners. |
| `DoStmt` | `StatementPrinter` | Conditions route through `ControlConditionPrinter`; bodies route through statement/block owners. |
| `TryStmt` | `StatementPrinter` | Owns resource layout, catch/finally sequencing, and adjacent block-comment handoff. Method-call resource openers reuse the canonical method-call argument-list renderer; blocks route to `BlockPrinter`. |
| `SynchronizedStmt` | `StatementPrinter` | Conditions route through `ControlConditionPrinter`; body routes to `BlockPrinter`. |
| `ForStmt` | `StatementPrinter` | Owns init/compare/update compact text and body routing. |
| `ForEachStmt` | `StatementPrinter` | Owns variable/iterable compact text and body routing. |
| Other `Statement` subtypes | `StatementPrinter` compact fallback | Emits `Doc.text(CompactSourceText.compact(statement))` after `StatementRuleEnvelope` has handled the outer leading/trailing comment gate. |

Switch-specific ownership:

- `StatementPrinter` owns `SwitchStmt` selection with the other statements, while `SwitchPrinter` owns reusable labels,
  guards, statement groups, rule entries, and switch block layout for statement and expression switches.
- `SwitchEntry` layout is local to `SwitchPrinter`: statement groups, compact empty rule blocks, commented rule bodies,
  and inline rule bodies.
- In parse-error recovery mode, malformed switch entries stay inside `SwitchPrinter`: safe entry siblings render normally,
  while unsafe entry gaps are raw-preserved inside the switch block so selectors and switch braces remain formatter-owned.
- `TypePatternExpr` and `RecordPatternExpr` labels are switch-label concerns. Flat labels use normalized source text;
  wide record patterns can wrap structurally.
- Single-line rule entries with source-only syntax, currently block comments inside the rule text or `null, default`,
  are preserved with `RawPreservedSource`.

## Expressions

`ExpressionRuleEnvelope` owns the shared expression entry points, including the clone-before-own-comment-removal path
used by expression callers that have already claimed the attached comment separately. Formatted expression content then
routes through `ExpressionDispatcher`, which owns only broad subtype dispatch and compact fallback for unknown
expression kinds. Specialized expression printers own the layout decision tree for their selected AST kind.

| JavaParser AST kind | Structured owner | Notes |
| --- | --- | --- |
| `AssignExpr` | `AssignmentExpressionPrinter` | Owns assignment wrapping, nested assignment continuations, and assignment-specific hooks for binary, method-call, conditional, object-creation, and enclosed suffix cases. |
| `ArrayAccessExpr` | `ArrayExpressionPrinter` | Owns array access and suffix behavior for broken enclosed scopes. |
| `ArrayCreationExpr` | `ArrayExpressionPrinter` | Owns array creation prefixes, type breaks, and initializer delegation. |
| `ArrayInitializerExpr` | `ArrayExpressionPrinter` | Owns initializer braces, compact initializer acceptance, and initializer comments. |
| `AnnotationExpr` and subclasses | `AnnotationExpressionPrinter` | Covers marker, normal, and single-member annotations plus annotation member values, including line comments inside annotation array values. |
| `BinaryExpr` | `BinaryExpressionPrinter` | Owns binary flattening, operator position, line comments between operands, precedence parentheses, and cast-division continuation policy. |
| `CastExpr` | `CastExpressionPrinter` | Owns cast type layout, including breakable generic and intersection cast types, and nested cast depth checks. |
| `ConditionalExpr` | `ConditionalExpressionPrinter` | Owns ternary layout for assignments, initializers, comments around `?` and `:`, nested branches, and binary condition wrapping. |
| `EnclosedExpr` | `EnclosedExpressionPrinter` | Owns parenthesized expression layout and suffix preservation for array, method-call, and method-reference suffixes. |
| `FieldAccessExpr` | `FieldAccessPrinter` | Owns dotted field access and comment-sensitive name splitting. Compact field-access text is also reconstructed by `CompactSourceText`. |
| `InstanceOfExpr` | `InstanceOfExpressionPrinter` | Owns `instanceof` continuations and binary-operator-position-aware placement. |
| `LambdaExpr` | `LambdaExpressionPrinter` | Owns parameter parentheses policy, commented parameter reconstruction, expression/block bodies, and huggable lambda argument shapes. |
| `MethodCallExpr` | `MethodCallPrinter`, `BreakableArgumentExpressionPrinter`, `MethodCallChainPrinter` | `MethodCallPrinter` owns ordinary calls, canonical argument-list rendering, method-call suffixes, text-block arguments, and lambda arguments; `BreakableArgumentExpressionPrinter` owns reusable broken argument-expression policy for over-wide or source-multiline binary arguments; `MethodCallChainPrinter` owns call chains, chain comments, same-line chained-call comment ownership, leading line-comment clusters before chained segments, source-shaped chain planning/root promotion via `MethodCallChainSourcePlanner`, source-multiline field-root fluent-chain statements, source-multiline single-object-creation call statements, return compact-root/final-argument breaks, final-segment tails, and broken chain roots. |
| `MethodReferenceExpr` | `MethodReferencePrinter` | Owns method references, type-argument suffix text, and parenthesized-scope suffixes. |
| `ObjectCreationExpr` | `ObjectCreationPrinter` | Owns constructor calls, argument breaks selected by `ObjectCreationLayoutPolicy`, lambda arguments, generic type-body breaks, and anonymous class member sequencing. |
| `SwitchExpr` | `SwitchPrinter.switchExpression(...)` | Uses the same reusable switch label, guard, entry, and block layout as `SwitchStmt` without owning statement dispatch; declaration printers preserve switch-expression initializer bodies on the equals line. |
| `TextBlockLiteralExpr` | `TextBlockPrinter` | Owns narrow fixture-backed content probes and raw source-derived fallback rendering for unrecognized text blocks. |
| Other `Expression` subtypes | `ExpressionRuleEnvelope` then `ExpressionDispatcher` compact fallback | Emits `Doc.text(CompactSourceText.compact(expression))`. This covers simple names, literals, `this`, `super`, class literals, unary/postfix forms, pattern nodes outside switch-label paths, and any JavaParser expression kind without a dedicated branch. |

Expression-adjacent owners that are selected by statement or declaration context rather than direct expression dispatch:

- `VariableDeclarationPrinter` owns local `VariableDeclarationExpr` after `StatementPrinter` identifies a local variable
  declaration statement.
- `ReturnExpressionPrinter` owns return-value wrapping after `StatementPrinter` identifies a `ReturnStmt`.
- `ControlConditionPrinter` owns parenthesized conditions for `if`, loops, synchronized statements, and statement-switch
  selectors after the statement or statement-switch renderer selects that context, including source-multiline binary `if`
  condition breaks; switch expressions use compact selector text directly.
- `EnclosedSuffixDispatcher` is the bridge for broken enclosed expressions that need method-call or method-reference
  suffixes preserved.

## Comment, Raw, And Compact Boundaries

These helpers define the fallback and comment-accounting boundaries used by the maps above.

| Helper | Boundary owned |
| --- | --- |
| `JavaCommentMap` | Per-run snapshot of JavaParser own, orphan, and contained comment associations. It preserves lookup identity and does not classify placement. |
| `JavaCommentPlacementPolicy` | Read-only placement queries over `JavaCommentMap`, including leading, adjacent-leading clusters, trailing-line, orphan, contained, between-neighbor, and same-line comment decisions. It does not render or claim comments. |
| `CommentTracker` | Stateful comment consumption for policy-selected leading, adjacent-leading, trailing, orphan, and raw-preserved comments in one formatting run. |
| `CommentIndex` | Read-only source-position predicates and ordering for comments and nodes. It does not render or mark comments consumed. |
| `CommentPlacement` | Source-position-sensitive attached and unattached block-comment docs for callers that need more than ordinary leading/trailing slots, backed by `JavaCommentPlacementPolicy`. |
| `JavaCommentTrivia` and `JavaCommentKind` | Comment classification and reusable range checks for comment accounting and layout rules. |
| `FormatterPragmas` | Formatter off/on and ignore state used by declaration dispatch and the statement rule envelope. |
| `FormatterGuardrails` | Debug-only transform and comment-accounting checks enabled by `dev.lanwen.frmtr.debug.guardrails`. |
| `RawSource` | Token-range recovery, raw-without-own-comment text, line-by-line trailing whitespace stripping, and single-line whitespace normalization. |
| `RawPreservedSource` | The canonical raw-output boundary. It wraps raw or source-derived text in `Doc.Text` and records contained comments as deliberately raw-preserved. |
| `CompactSourceText` | Source-equivalent compact text for width gates and compact fallbacks, including raw string-literal spelling, reconstructed field accesses, comment-free expressions for anonymous-class headers, type-like generic spacing cleanup, and clone-before-comment-removal variants. |
| `LayoutWidth` | Shared indentation baselines for flat-width probes used by statement, field initializer, method-chain, and declaration helpers. |
| `SourceShape` | Source-line-shape predicates that let printers preserve existing multiline forms without scanning whole declarations or owning the resulting doc layout. |
| `BreakableArgumentExpressionPrinter` | Shared method-call argument-expression break policy used by direct method calls, initializer method-call openers, and try-resource method-call openers. |
| `ObjectCreationLayoutPolicy` | Shared constructor-call source-shape policy for preserving multiline constructor arguments and keeping small constructor roots compact across direct object creation, return expressions, and method-call chains. |
| `MethodCallChainSourcePlanner` | Source-shaped method-call chain planning, including root collection, selector-line preservation, builder/type-like root promotion, and constructor-root policy delegation before `MethodCallChainPrinter` assembles docs. |
| `CommentedTokenText` | Comment-aware tokenization and token-line helpers used by raw fallback printers. |
| `CommentedModulePrinter` | Raw-source reconstruction for commented `module-info.java` headers and directives selected by `ModuleDeclarationPrinter`. |
| `CommentedInterfacePrinter` | Raw-source reconstruction for interface headers and abstract method signatures with comments inside declaration syntax. |
| `CommentedMethodSignaturePrinter` | Raw-source reconstruction for method signatures with comments when the body is small enough that the fallback does not become a second statement formatter. |

## Known Intentional Fallbacks

These fallbacks are intentional current behavior. Before replacing one with structured formatting, add a dedicated owner
branch, fixture coverage for source/comment edge cases, and update this map.

| Fallback | Current trigger | What new structured coverage would need |
| --- | --- | --- |
| Body pragma raw pass | `FormatterPragmas.bodyAction(...)` returns `RAW`. | Usually no replacement; pragma semantics require source preservation. Any changed pragma behavior needs pragma fixtures and comment-accounting review. |
| Statement pragma raw pass | `FormatterPragmas.statementAction(...)` returns `RAW` or `RAW_WITH_TRAILING_HARD_LINE`. | Usually no replacement; pragma semantics require source preservation. Any changed pragma behavior needs pragma fixtures and statement separator review. |
| Unknown body declarations | `BodyDeclarationDispatcher` default branch after `BodyDeclarationRuleEnvelope`. | Add a typed dispatcher branch and declaration printer, including member sequencing if relevant, raw/comment accounting, and golden fixtures. |
| Unknown statements | `StatementPrinter` default branch. | Add a typed statement branch, nested statement/expression delegation, comment placement rules, and golden fixtures. |
| Unknown expressions | `ExpressionDispatcher` default branch. | Add a typed expression branch, recursive expression delegation, comment placement rules, width policy, and golden fixtures. |
| Unknown module directives | `ModuleBlockPrinter` default branch. | Add a directive branch, target list/comment rules as needed, and module fixtures. |
| Commented module declarations | `ModuleDeclarationPrinter` sees `/*` or `//` in the raw module declaration. | Structured module comment slots for headers and directives, plus fixtures that prove comments are printed once. |
| Commented interface headers and abstract signatures | `ClassOrInterfaceDeclarationPrinter` detects a commented interface header. | Structured comment slots for interface headers, extends clauses, and abstract method signatures. |
| Commented method signatures | `MethodDeclarationPrinter` receives a non-empty result from `CommentedMethodSignaturePrinter`. | Structured signature comment slots that work for larger method bodies without source-string body formatting. |
| Source-only switch rule entries | `SwitchPrinter.rawSingleLineSwitchEntry(...)` sees single-line rule text with block comments or `null, default`. | Structured representation of those label/body comment positions or source-only labels, plus switch fixtures. |
| Recovered malformed switch entries | `SwitchPrinter` plans `SwitchEntry` sibling gaps in parse-error recovery mode. | Structured recovery for additional JavaParser switch-entry shapes once JavaParser exposes them without collapsing the switch owner. |
| Recovered malformed enum constants | `EnumDeclarationPrinter` plans `EnumConstantDeclaration` sibling gaps in parse-error recovery mode. | Structured recovery for additional JavaParser enum-constant shapes once JavaParser exposes them without collapsing the compilation unit. |
| Recovered malformed annotation declaration members | `AnnotationDeclarationPrinter` plans annotation body member gaps in parse-error recovery mode. | Structured recovery for additional JavaParser annotation-member shapes once JavaParser exposes them without collapsing the compilation unit. |
| Unrecognized text-block content | `TextBlockPrinter` content probes decline. | A real content formatter or additional probe, plus text-block fixtures for raw spelling, indentation, closing delimiter placement, and escapes. |

Compact text is also a normal support mechanism for flat width checks, type-like snippets, labels, resources, and other
source-equivalent fragments. A compact call is not automatically a missing printer; it is a missing coverage item only
when a caller uses compact text as the final emitted document for an AST kind that should have structured layout.
