package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.Statement;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;

final class JavaPrinter {
    private final JavaFormatContext context;
    private final TypePrinter types;
    private final ModuleBlockPrinter moduleBlocks;
    private final ModuleDeclarationPrinter moduleDeclarations;
    private final MemberBlockPrinter memberBlocks;
    private final BlockPrinter blocks;
    private final StatementPrinter statements;
    private final SwitchPrinter switches;
    private final ControlConditionPrinter controlConditions;
    private final BinaryExpressionPrinter binaries;
    private final AnnotationExpressionPrinter annotationExpressions;
    private final DeclarationPrefixPrinter declarationPrefixes;
    private final ConditionalExpressionPrinter conditionals;
    private final LambdaExpressionPrinter lambdas;
    private final ArrayExpressionPrinter arrays;
    private final ObjectCreationPrinter objectCreations;
    private final TextBlockPrinter textBlocks;
    private final CastExpressionPrinter casts;
    private final EnclosedExpressionPrinter enclosedExpressions;
    private final InstanceOfExpressionPrinter instanceOfExpressions;
    private final FieldAccessPrinter fieldAccesses;
    private final MethodReferencePrinter methodReferences;
    private final MethodCallPrinter methodCalls;
    private final EnclosedSuffixDispatcher enclosedSuffixes;
    private final AssignmentExpressionPrinter assignments;
    private final ReturnExpressionPrinter returnExpressions;
    private final CallableSignaturePrinter callableSignatures;
    private final ThrowsClausePrinter throwsClauses;
    private final ConstructorDeclarationPrinter constructors;
    private final MethodDeclarationPrinter methods;
    private final InitializerDeclarationPrinter initializers;
    private final EnumDeclarationPrinter enums;
    private final RecordDeclarationPrinter records;
    private final AnnotationDeclarationPrinter annotationDeclarations;
    private final ClassOrInterfaceDeclarationPrinter classOrInterfaces;
    private final CommentedMethodSignaturePrinter commentedMethodSignatures;
    private final CompilationUnitPrinter compilationUnits;
    private final FieldDeclarationPrinter fields;
    private final VariableDeclarationPrinter variableDeclarations;
    private final ExpressionRuleEnvelope expressionRules;
    private final BodyDeclarationRuleEnvelope bodyDeclarations;
    private final StatementRuleEnvelope statementRules;

    JavaPrinter(FormatterOptions options, SourceText sourceText, boolean recoverParseProblems) {
        this.context = new JavaFormatContext(options, sourceText, recoverParseProblems);
        CommentTracker comments = context.comments;
        JavaCommentPlacementPolicy commentPlacementPolicy = context.commentPlacementPolicy;
        FormatterPragmas formatterPragmas = context.formatterPragmas;
        RawSource rawSource = context.rawSource;
        RawPreservedSource rawPreservedSource = context.rawPreservedSource;
        CompactSourceText compactSource = context.compactSource;
        CommentPlacement commentPlacement = context.commentPlacement;
        this.types = new TypePrinter(options, compactSource::compactTypeLike);
        this.blocks = new BlockPrinter(
                context,
                this::statement,
                formatterPragmas::hasPragma);
        this.binaries = new BinaryExpressionPrinter(
                comments,
                commentPlacementPolicy,
                options,
                this::expression,
                this::brokenMethodCall,
                compactSource::compact,
                compactSource::compactWithoutOwnComment,
                this::continuationStatementWidth,
                this::blockStatementWidth);
        this.annotationExpressions = new AnnotationExpressionPrinter(
                comments,
                options,
                this::expression,
                binaries::nestedLines,
                compactSource::compact,
                this::currentIndentedWidth);
        compactSource.useAnnotationFlatText(annotationExpressions::annotationFlatText);
        this.declarationPrefixes = new DeclarationPrefixPrinter(
                annotationExpressions::annotation,
                annotationExpressions::annotationFlatText);
        this.moduleBlocks = new ModuleBlockPrinter(
                context,
                compactSource::compact,
                compactSource::compactJoin,
                declarationPrefixes::modifiers);
        this.moduleDeclarations = new ModuleDeclarationPrinter(
                comments,
                rawSource,
                rawPreservedSource,
                new CommentedModulePrinter(),
                declarationPrefixes::annotations,
                this::commentText,
                compactSource::compact,
                moduleBlocks::moduleBlock,
                moduleBlocks::canUseStructuredRecoveryForCommentedModule,
                context.recoverParseProblems);
        this.memberBlocks = new MemberBlockPrinter(
                context,
                declarationPrefixes::hasDeclarationAnnotations,
                formatterPragmas::hasPragma);
        this.controlConditions = new ControlConditionPrinter(
                comments,
                rawSource,
                options,
                this::expression,
                compactSource::compact,
                compactSource::compactWithoutOwnComment,
                binaries::expressionHasParenthesizedNestedBinary,
                expression -> binaries.lines(expression, true),
                this::currentIndentedWidth);
        this.switches = new SwitchPrinter(
                context,
                this::statement,
                this::expression,
                this::block,
                blocks::statementSeparator,
                controlConditions::controlCondition,
                binaries::lines,
                declarationPrefixes::modifiers,
                this::currentIndentedWidth);
        this.conditionals = new ConditionalExpressionPrinter(
                context,
                this::expression,
                this::expressionWithoutOwnComment,
                this::currentIndentedWidth,
                this::blockStatementWidth,
                this::continuationStatementWidth,
                binaries::lines,
                binaries::nestedLines,
                binaries::expressionHasParenthesizedNestedBinary);
        this.lambdas = new LambdaExpressionPrinter(
                comments,
                rawSource,
                options,
                this::expression,
                this::statement,
                this::block,
                binaries::lines,
                compactSource::compact,
                compactSource::compactWithoutOwnComment,
                compactSource::compactJoin,
                this::currentIndentedWidth,
                this::blockStatementWidth,
                CommentIndex::startsBefore,
                CommentIndex::startsOnSameLine);
        this.casts = new CastExpressionPrinter(
                options,
                this::expression,
                compactSource::compactTypeLike,
                types::typeBody,
                this::currentIndentedWidth);
        this.enclosedExpressions = new EnclosedExpressionPrinter(
                options,
                this::expression,
                binaries::lines,
                binaries::hasLineComments,
                binaries::linesWithComments,
                compactSource::compact,
                this::currentIndentedWidth,
                this::continuationStatementWidth,
                casts::nestedCastDepth,
                lambdas::parenthesizedLambdaBreak,
                conditionals::conditionalExpression);
        this.arrays = new ArrayExpressionPrinter(
                comments,
                options,
                this::expression,
                enclosedExpressions::brokenEnclosedForSuffix,
                compactSource::compactTypeLike,
                compactSource::compact,
                this::currentIndentedWidth);
        this.objectCreations = new ObjectCreationPrinter(
                context,
                types,
                this::expression,
                lambdas::huggableBlockLambdaArguments,
                this::body,
                compactSource::compact,
                compactSource::compactJoin,
                compactSource::compactTypeLike,
                compactSource::compactTypeLikeWithoutOwnComment,
                this::commentText);
        this.textBlocks = new TextBlockPrinter(rawSource, options);
        this.instanceOfExpressions = new InstanceOfExpressionPrinter(
                options,
                this::expression,
                compactSource::compact,
                compactSource::compactTypeLike,
                this::currentIndentedWidth);
        this.fieldAccesses = new FieldAccessPrinter(comments, this::expression);
        this.methodReferences = new MethodReferencePrinter(
                options,
                compactSource::compact,
                types::compactJoinTypeLike,
                enclosedExpressions::brokenEnclosedForSuffix,
                this::blockStatementWidth);
        this.methodCalls = new MethodCallPrinter(
                context,
                types,
                this::expression,
                enclosedExpressions::brokenEnclosedForSuffix,
                objectCreations::brokenObjectCreation,
                objectCreations::objectCreationPrefix,
                lambdas::huggableBlockLambdaArguments,
                lambdas::huggableBlockLambdaFirstLine,
                lambdas::commentedExpressionLambdaArgument,
                lambdas::huggableMethodCallExpressionLambdaArguments,
                textBlocks::renderUnformattedTextBlock,
                binaryExpr -> binaries.nestedLines(binaryExpr, true),
                this::currentIndentedWidth,
                this::blockStatementWidth);
        this.enclosedSuffixes = new EnclosedSuffixDispatcher(methodCalls, methodReferences);
        this.assignments = new AssignmentExpressionPrinter(
                options,
                this::expression,
                compactSource::compact,
                this::blockStatementWidth,
                enclosedSuffixes::suffixedEnclosedExpression,
                binaries::shouldKeepCastDivisionContinuationFlat,
                binaries::lines,
                objectCreations::brokenObjectCreation,
                methodCalls::assignmentWithBrokenMethodCallArguments,
                conditionals::assignmentWithConditionalValue);
        ExpressionDispatcher expressionDispatcher = new ExpressionDispatcher(
                assignments::assignment,
                arrays::arrayAccess,
                arrays::arrayCreation,
                arrays::arrayInitializer,
                annotationExpressions::annotation,
                binaries::binaryExpression,
                casts::castExpression,
                conditionals::conditionalExpression,
                enclosedExpressions::enclosedExpression,
                fieldAccesses::fieldAccess,
                instanceOfExpressions::instanceOfExpression,
                lambdas::lambdaExpression,
                methodCalls::methodCall,
                methodReferences::methodReference,
                objectCreations::objectCreation,
                switches::switchExpression,
                textBlocks::textBlockLiteral,
                compactSource);
        this.expressionRules = new ExpressionRuleEnvelope(expressionDispatcher::expressionContent);
        this.returnExpressions = new ReturnExpressionPrinter(
                options,
                context.objectCreationLayoutPolicy,
                this::expression,
                compactSource::compact,
                this::currentIndentedWidth,
                methodCalls::compactRootWithBrokenFinalChainSegment,
                methodCalls::forcedMethodCallChain,
                objectCreations::brokenObjectCreation,
                conditionals::conditionalExpression,
                enclosedExpressions::parenthesizedBreak);
        this.commentedMethodSignatures = new CommentedMethodSignaturePrinter(options);
        PackageDeclarationPrinter packageDeclarations = new PackageDeclarationPrinter(comments, rawSource, options);
        ImportDeclarationPrinter importDeclarations = new ImportDeclarationPrinter(comments);
        this.compilationUnits = new CompilationUnitPrinter(
                context,
                packageDeclarations,
                importDeclarations,
                moduleDeclarations::moduleDeclaration,
                this::body);
        this.fields = new FieldDeclarationPrinter(
                comments,
                rawSource,
                options,
                declarationPrefixes::declarationAnnotations,
                declarationPrefixes::modifiers,
                declarationPrefixes::inlineAnnotations,
                compactSource::compactTypeLike,
                compactSource::compact,
                compactSource::compactWithoutOwnComment,
                compactSource::compactJoin,
                this::expression,
                this::expressionWithoutOwnComment,
                binaries::hasLineComments,
                binaries::linesWithComments,
                enclosedSuffixes::suffixedEnclosedExpression,
                arrays::arrayAccessWithBrokenEnclosedName,
                binaries::shouldKeepCastDivisionContinuationFlat,
                binaries::lines,
                methodCalls::methodCall,
                methodCalls::brokenMethodCall,
                methodCalls::mixedFieldMethodCallChain,
                methodCalls::forcedMethodCallChain,
                methodCalls::mixedFieldMethodCallRoot,
                methodCalls::methodCallChainFirstLine,
                methodCalls::methodCallChainRootIsObjectCreation,
                casts::castType,
                conditional -> conditionals.conditionalExpression(conditional, true),
                conditionals::shouldBreakBeforeConditionalInitializer,
                arrays::arrayCreationTypeBreaks,
                arrays::arrayCreationPrefix,
                arrays::arrayInitializer,
                arrays::compactArrayInitializerWithSourceSpacing,
                objectCreations::objectCreationPrefix,
                types::typeNameWithoutArguments,
                types::brokenClassOrInterfaceType,
                methodCalls::shouldPrintScopeAsDoc,
                methodCalls::methodCallPrefix,
                lambdas::huggableBlockLambdaArguments,
                lambdas::lambdaParameters,
                lambdas::lambdaParametersShouldBreak,
                lambdas::lambdaExpression);
        this.variableDeclarations = new VariableDeclarationPrinter(
                options,
                declarationPrefixes::annotations,
                declarationPrefixes::modifiers,
                compactSource::compactTypeLike,
                types::typeBody,
                types::typeCanBreak,
                fields::variable,
                this::currentIndentedWidth);
        this.callableSignatures = new CallableSignaturePrinter(
                comments,
                rawSource,
                options,
                compactSource::compact,
                compactSource::compactTypeLike,
                types::typeBody,
                declarationPrefixes::modifier,
                types::typeCanBreak,
                commentPlacement::unattachedTrailingBlockComment,
                this::commentText);
        this.throwsClauses = new ThrowsClausePrinter(
                options,
                compactSource::compact,
                compactSource::compactJoin,
                this::currentIndentedWidth);
        this.classOrInterfaces = new ClassOrInterfaceDeclarationPrinter(
                comments,
                rawSource,
                rawPreservedSource,
                options,
                new CommentedInterfacePrinter(),
                callableSignatures,
                declarationPrefixes::annotations,
                declarationPrefixes::modifiers,
                types::extendsTypes,
                types::implementsTypes,
                types::permitsTypes,
                types::typeClause,
                types::flatTypeParameters,
                types::flatTypeClause,
                this::currentIndentedWidth,
                declaration -> memberBlocks.memberBlock(declaration.getMembers(), declaration, this::body));
        this.constructors = new ConstructorDeclarationPrinter(
                callableSignatures,
                declarationPrefixes::annotations,
                declarationPrefixes::modifiers,
                compactSource::compactJoin,
                throwsClauses::throwsClause,
                this::block);
        this.methods = new MethodDeclarationPrinter(
                rawSource,
                context.sourceShape,
                rawPreservedSource,
                commentedMethodSignatures,
                callableSignatures,
                declarationPrefixes::declarationAnnotations,
                declarationPrefixes::modifiers,
                types::flatTypeParameters,
                declarationPrefixes::inlineAnnotations,
                compactSource::compact,
                types::typeBody,
                types::typeCanBreak,
                throwsClauses::throwsClause,
                this::block);
        this.initializers = new InitializerDeclarationPrinter(this::block);
        this.enums = new EnumDeclarationPrinter(
                context,
                declarationPrefixes::annotations,
                declarationPrefixes::modifiers,
                enumTypes -> types.typeClause("implements", enumTypes),
                types::implementsTypes,
                enumTypes -> types.flatTypeClause("implements", enumTypes),
                compactSource::compactJoin,
                this::expression,
                this::currentIndentedWidth,
                this::body);
        this.records = new RecordDeclarationPrinter(
                comments,
                options,
                declarationPrefixes::annotations,
                declarationPrefixes::modifiers,
                callableSignatures::typeParameters,
                types::flatTypeParameters,
                compactSource::compact,
                compactSource::compactJoin,
                types::compactJoinTypeLike,
                compactSource::compactTypeLike,
                types::typeBody,
                annotationExpressions::annotation,
                annotationExpressions::annotationFlatText,
                this::currentIndentedWidth,
                declaration -> memberBlocks.memberBlock(declaration.getMembers(), declaration, this::body));
        this.statements = new StatementPrinter(
                comments,
                rawSource,
                context.sourceShape,
                options,
                this::statement,
                switches::switchStatement,
                this::block,
                blocks::blockWithLeading,
                this::body,
                this::expression,
                returnExpressions::returnExpression,
                variableDeclarations::variableDeclaration,
                compactSource::compact,
                compactSource::compactWithoutOwnComment,
                compactSource::compactJoin,
                compactSource::compactTypeLike,
                types::compactJoinTypeLike,
                lambdas::huggableBlockLambdaArguments,
                methodCalls::sourceMultilineSingleObjectCreationArgumentStatement,
                methodCalls::forcedMethodCallChain,
                methodCalls::brokenMethodCall,
                methodCalls::methodCallChainHasComments,
                methodCalls::methodCallChainRootIsObjectCreation,
                methodCalls::methodCallChainRootIsFieldAccess,
                controlConditions::ifCondition,
                controlConditions::controlCondition,
                controlConditions::compactWithOwnBlockComment,
                commentPlacement::ownSameLineBlockCommentBeforeNode,
                this::currentIndentedWidth);
        this.annotationDeclarations = new AnnotationDeclarationPrinter(
                context,
                declarationPrefixes::annotations,
                declarationPrefixes::modifiers,
                compactSource::compactTypeLike,
                this::expression,
                this::body);
        BodyDeclarationDispatcher bodyDeclarationDispatcher = new BodyDeclarationDispatcher(
                rawPreservedSource,
                compactSource::compact,
                classOrInterfaces::classOrInterface,
                records::record,
                enums::enumDeclaration,
                annotationDeclarations::annotationDeclaration,
                annotationDeclarations::annotationMember,
                fields::field,
                methods::method,
                constructors::compactConstructor,
                constructors::constructor,
                initializers::initializer);
        this.bodyDeclarations = new BodyDeclarationRuleEnvelope(
                comments,
                formatterPragmas,
                rawPreservedSource,
                bodyDeclarationDispatcher::bodyContent);
        this.statementRules = new StatementRuleEnvelope(
                comments,
                commentPlacementPolicy,
                formatterPragmas,
                rawPreservedSource,
                statements::statement);
    }

    Doc print(CompilationUnit unit) {
        context.startCommentRun(unit);
        Doc doc = compilationUnits.print(unit);
        context.comments.assertAllCommentsAccounted(unit);
        return doc;
    }

    private Doc body(BodyDeclaration<?> declaration) {
        return bodyDeclarations.body(declaration);
    }

    private Doc expressionWithoutOwnComment(Expression expression) {
        return expressionRules.expressionWithoutOwnComment(expression);
    }

    private int currentIndentedWidth(String text) {
        return context.options.indentUnit().length() + text.length();
    }

    private int blockStatementWidth(String text) {
        return (context.options.indentUnit().length() * 2) + text.length();
    }

    private int continuationStatementWidth(String text) {
        return (context.options.indentUnit().length() * 3) + text.length();
    }

    private Doc block(BlockStmt block) {
        return blocks.block(block);
    }

    private Doc statement(Statement statement) {
        return statementRules.statement(statement);
    }

    private Doc brokenMethodCall(MethodCallExpr expression) {
        return methodCalls.brokenMethodCall(expression);
    }

    private Doc expression(Expression expression) {
        return expressionRules.expression(expression);
    }

    private String commentText(Doc comment) {
        if (comment instanceof Doc.Text(String value)) {
            return value;
        }
        return "";
    }

}
