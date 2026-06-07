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
    private final ExpressionDispatcher expressionDispatcher;
    private final BodyDeclarationDispatcher bodyDeclarations;
    private final StatementDispatcher statementDispatcher;

    JavaPrinter(FormatterOptions options) {
        this.context = new JavaFormatContext(options);
        JavaFormatter.CommentTracker comments = context.comments;
        FormatterPragmas formatterPragmas = context.formatterPragmas;
        RawSource rawSource = context.rawSource;
        CompactSourceText compactSource = context.compactSource;
        CommentPlacement commentPlacement = context.commentPlacement;
        this.types = new TypePrinter(options, compactSource::compactTypeLike);
        this.blocks = new BlockPrinter(comments, this::statement, formatterPragmas::hasPragma);
        this.binaries = new BinaryExpressionPrinter(
                comments,
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
        this.declarationPrefixes = new DeclarationPrefixPrinter(
                annotationExpressions::annotation,
                annotationExpressions::annotationFlatText);
        this.moduleBlocks = new ModuleBlockPrinter(
                comments,
                options,
                compactSource::compact,
                compactSource::compactJoin,
                declarationPrefixes::modifiers);
        this.moduleDeclarations = new ModuleDeclarationPrinter(
                comments,
                rawSource,
                new CommentedModulePrinter(),
                declarationPrefixes::annotations,
                this::commentText,
                compactSource::compact,
                moduleBlocks::moduleBlock);
        this.memberBlocks = new MemberBlockPrinter(rawSource, comments, declarationPrefixes::hasDeclarationAnnotations);
        this.controlConditions = new ControlConditionPrinter(
                comments,
                options,
                compactSource::compact,
                compactSource::compactWithoutOwnComment,
                binaries::lines,
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
                (expression, forceBreak) -> binaries.lines(expression, forceBreak),
                (expression, forceBreak) -> binaries.nestedLines(expression, forceBreak),
                binaries::expressionHasParenthesizedNestedBinary);
        this.lambdas = new LambdaExpressionPrinter(
                comments,
                rawSource,
                options,
                this::expression,
                this::statement,
                this::block,
                (expression, forceBreak) -> binaries.lines(expression, forceBreak),
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
                this::currentIndentedWidth);
        this.enclosedExpressions = new EnclosedExpressionPrinter(
                options,
                this::expression,
                (expression, forceBreak) -> binaries.lines(expression, forceBreak),
                compactSource::compact,
                this::currentIndentedWidth,
                this::continuationStatementWidth,
                casts::nestedCastDepth,
                lambdas::parenthesizedLambdaBreak,
                (expression, forceBreak) -> conditionals.conditionalExpression(expression, forceBreak));
        this.arrays = new ArrayExpressionPrinter(
                comments,
                options,
                this::expression,
                enclosedExpressions::brokenEnclosedForSuffix,
                compactSource::compactTypeLike,
                compactSource::compact,
                this::currentIndentedWidth);
        this.objectCreations = new ObjectCreationPrinter(
                comments,
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
                lambdas::huggableBlockLambdaArguments,
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
                (expression, forceBreak) -> binaries.lines(expression, forceBreak),
                objectCreations::brokenObjectCreation,
                methodCalls::assignmentWithBrokenMethodCallArguments,
                conditionals::assignmentWithConditionalValue);
        this.expressionDispatcher = new ExpressionDispatcher(
                assignments,
                arrays,
                annotationExpressions,
                binaries,
                casts,
                conditionals,
                enclosedExpressions,
                fieldAccesses,
                instanceOfExpressions,
                lambdas,
                methodCalls,
                methodReferences,
                objectCreations,
                switches,
                textBlocks,
                compactSource);
        this.returnExpressions = new ReturnExpressionPrinter(
                options,
                this::expression,
                compactSource::compact,
                this::currentIndentedWidth,
                methodCalls::forcedMethodCallChain,
                (expression, forceBreak) -> conditionals.conditionalExpression(expression, forceBreak),
                enclosedExpressions::parenthesizedBreak);
        this.commentedMethodSignatures = new CommentedMethodSignaturePrinter(options);
        PackageDeclarationPrinter packageDeclarations = new PackageDeclarationPrinter(comments, rawSource, options);
        ImportDeclarationPrinter importDeclarations = new ImportDeclarationPrinter(comments);
        this.compilationUnits = new CompilationUnitPrinter(
                comments,
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
                (expression, forceBreak) -> binaries.lines(expression, forceBreak),
                methodCalls::methodCall,
                methodCalls::brokenMethodCall,
                methodCalls::mixedFieldMethodCallChain,
                methodCalls::forcedMethodCallChain,
                methodCalls::mixedFieldMethodCallRoot,
                methodCalls::methodCallChainRoot,
                methodCalls::methodCallChainRootIsObjectCreation,
                casts::castType,
                conditional -> conditionals.conditionalExpression(conditional, true),
                conditionals::shouldBreakBeforeConditionalInitializer,
                arrays::arrayCreationTypeBreaks,
                arrays::arrayCreationPrefix,
                arrays::arrayInitializer,
                objectCreations::objectCreationPrefix,
                types::typeNameWithoutArguments,
                types::brokenClassOrInterfaceType,
                methodCalls::shouldPrintScopeAsDoc,
                methodCalls::methodCallPrefix,
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
                options,
                new CommentedInterfacePrinter(),
                callableSignatures,
                declarationPrefixes::annotations,
                declarationPrefixes::modifiers,
                types::extendsTypes,
                types::implementsTypes,
                types::permitsTypes,
                (keyword, headerTypes, breakBeforeClause) -> types.typeClause(keyword, headerTypes, breakBeforeClause),
                types::flatTypeParameters,
                types::flatTypeClause,
                this::currentIndentedWidth,
                declaration -> memberBlocks.memberBlock(declaration.getMembers(), declaration, this::body));
        this.constructors = new ConstructorDeclarationPrinter(
                comments,
                callableSignatures,
                declarationPrefixes::annotations,
                declarationPrefixes::modifiers,
                compactSource::compactJoin,
                throwsClauses::throwsClause,
                this::block);
        this.methods = new MethodDeclarationPrinter(
                comments,
                rawSource,
                commentedMethodSignatures,
                callableSignatures,
                declarationPrefixes::declarationAnnotations,
                declarationPrefixes::modifiers,
                types::flatTypeParameters,
                declarationPrefixes::inlineAnnotations,
                compactSource::compact,
                throwsClauses::throwsClause,
                this::block);
        this.initializers = new InitializerDeclarationPrinter(comments, this::block);
        this.enums = new EnumDeclarationPrinter(
                comments,
                rawSource,
                options,
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
                annotationExpressions::annotation,
                annotationExpressions::annotationFlatText,
                this::currentIndentedWidth,
                declaration -> memberBlocks.memberBlock(declaration.getMembers(), declaration, this::body));
        this.statements = new StatementPrinter(
                comments,
                rawSource,
                options,
                this::statement,
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
                methodCalls::forcedMethodCallChain,
                methodCalls::brokenMethodCall,
                methodCalls::methodCallChainHasComments,
                methodCalls::methodCallChainRootIsObjectCreation,
                methodCalls::methodCallChainRootIsFieldAccess,
                binaries::expressionHasParenthesizedNestedBinary,
                binaries::lines,
                controlConditions::controlCondition,
                controlConditions::compactWithOwnBlockComment,
                commentPlacement::ownSameLineBlockCommentBeforeNode,
                this::currentIndentedWidth);
        this.annotationDeclarations = new AnnotationDeclarationPrinter(
                comments,
                declarationPrefixes::annotations,
                declarationPrefixes::modifiers,
                compactSource::compactTypeLike,
                this::expression,
                this::body);
        this.bodyDeclarations = new BodyDeclarationDispatcher(
                formatterPragmas,
                comments::leading,
                rawSource::rawWithoutOwnComment,
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
        this.statementDispatcher = new StatementDispatcher(
                comments,
                formatterPragmas,
                rawSource,
                statements::statement,
                switches::switchStatement);
    }

    Doc print(CompilationUnit unit) {
        return compilationUnits.print(unit);
    }

    private Doc body(BodyDeclaration<?> declaration) {
        return bodyDeclarations.body(declaration);
    }

    private Doc expressionWithoutOwnComment(Expression expression) {
        return expressionDispatcher.expressionWithoutOwnComment(expression);
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
        return statementDispatcher.statement(statement);
    }

    private Doc brokenMethodCall(MethodCallExpr expression) {
        return methodCalls.brokenMethodCall(expression);
    }

    private Doc expression(Expression expression) {
        return expressionDispatcher.expression(expression);
    }

    private String commentText(Doc comment) {
        if (comment instanceof Doc.Text text) {
            return text.value();
        }
        return "";
    }

}
