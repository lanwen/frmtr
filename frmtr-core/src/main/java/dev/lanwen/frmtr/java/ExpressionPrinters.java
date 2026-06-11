package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.type.Type;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Wires expression-formatting helpers behind the expression rule envelope.
 *
 * <p>This composer owns the construction order for expression dispatch, expression-specific helper cycles, and the
 * source-sensitive callback graph used by method calls, lambdas, object creation, binaries, casts, assignments, and
 * returns. The boundary exists so the top-level printer can coordinate expression, declaration, and statement groups
 * without carrying every expression helper as a field.
 *
 * <p>Callers still decide when an expression context is needed, when statement or declaration rendering should be
 * entered, and how shared type rendering is constructed. This composer leaves those surrounding grammar choices to the
 * declaration and statement composers and exposes only the existing expression helper callbacks they already used.
 */
final class ExpressionPrinters {
    private final JavaFormatContext context;
    private final BinaryExpressionPrinter binaries;
    private final AnnotationExpressionPrinter annotationExpressions;
    private final ConditionalExpressionPrinter conditionals;
    private final LambdaExpressionPrinter lambdas;
    private final ArrayExpressionPrinter arrays;
    private final ObjectCreationPrinter objectCreations;
    private final TextBlockPrinter textBlocks;
    private final CastExpressionPrinter casts;
    private final ClassExpressionPrinter classExpressions;
    private final EnclosedExpressionPrinter enclosedExpressions;
    private final InstanceOfExpressionPrinter instanceOfExpressions;
    private final FieldAccessPrinter fieldAccesses;
    private final MethodReferencePrinter methodReferences;
    private final MethodCallPrinter methodCalls;
    private final EnclosedSuffixDispatcher enclosedSuffixes;
    private final AssignmentExpressionPrinter assignments;
    private final ReturnExpressionPrinter returnExpressions;
    private final ExpressionRuleEnvelope expressionRules;

    ExpressionPrinters(
            JavaFormatContext context,
            TypePrinter types,
            JavaFormatRule<Statement> statementRenderer,
            JavaFormatRule<BlockStmt> blockRenderer,
            JavaFormatRule<BodyDeclaration<?>> bodyRenderer,
            JavaFormatRule<SwitchExpr> switchExpressionRenderer,
            Function<Doc, String> commentText) {
        this.context = context;
        FormatterOptions options = context.options;
        CommentTracker comments = context.comments;
        JavaCommentPlacementPolicy commentPlacementPolicy = context.commentPlacementPolicy;
        RawSource rawSource = context.rawSource;
        CompactSourceText compactSource = context.compactSource;
        this.binaries = new BinaryExpressionPrinter(
                comments,
                commentPlacementPolicy,
                options,
                this::expression,
                this::brokenMethodCall,
                context.sourceShape,
                compactSource::compact,
                compactSource::compactWithoutOwnComment,
                this::continuationStatementWidth,
                this::blockStatementWidth);
        this.annotationExpressions = new AnnotationExpressionPrinter(
                comments,
                commentPlacementPolicy,
                options,
                this::expression,
                binaries::nestedLines,
                compactSource::compact,
                this::currentIndentedWidth);
        compactSource.useAnnotationFlatText(annotationExpressions::annotationFlatText);
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
                context.objectCreationLayoutPolicy,
                options,
                this::expression,
                statementRenderer,
                blockRenderer,
                binaries::nestedLines,
                this::brokenMethodCall,
                this::methodCallArgumentList,
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
        this.classExpressions = new ClassExpressionPrinter(compactSource::compactTypeLike);
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
                bodyRenderer,
                compactSource::compact,
                compactSource::compactJoin,
                compactSource::compactTypeLike,
                compactSource::compactTypeLikeWithoutOwnComment,
                commentText);
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
                objectCreations::objectCreationWithSuffix,
                objectCreations::objectCreationPrefix,
                lambdas::huggableBlockLambdaArguments,
                lambdas::huggableBlockLambdaFirstLine,
                lambdas::commentedExpressionLambdaArgument,
                lambdas::huggableMethodCallExpressionLambdaArguments,
                lambdas::huggableExpressionLambdaArgumentPlan,
                textBlocks::renderUnformattedTextBlock,
                binaryExpr -> binaries.nestedLines(binaryExpr, true),
                this::currentIndentedWidth,
                this::continuationStatementWidth,
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
                methodCalls::assignmentWithBrokenMethodCallArgumentsAndSemicolon,
                conditionals::assignmentWithConditionalValue);
        ExpressionDispatcher expressionDispatcher = new ExpressionDispatcher(
                assignments::assignment,
                arrays::arrayAccess,
                arrays::arrayCreation,
                arrays::arrayInitializer,
                annotationExpressions::annotation,
                binaries::binaryExpression,
                casts::castExpression,
                classExpressions::classExpression,
                conditionals::conditionalExpression,
                enclosedExpressions::enclosedExpression,
                fieldAccesses::fieldAccess,
                instanceOfExpressions::instanceOfExpression,
                lambdas::lambdaExpression,
                methodCalls::methodCall,
                methodReferences::methodReference,
                objectCreations::objectCreation,
                switchExpressionRenderer,
                textBlocks::textBlockLiteral,
                compactSource);
        this.expressionRules = new ExpressionRuleEnvelope(expressionDispatcher::expressionContent);
        this.returnExpressions = new ReturnExpressionPrinter(
                options,
                context.objectCreationLayoutPolicy,
                this::expression,
                lambdas::brokenExpressionLambda,
                compactSource::compact,
                this::currentIndentedWidth,
                methodCalls::sourceMultilineExpressionLambda,
                methodCalls::sourceMultilineArguments,
                methodCalls::compactRootWithBrokenFinalChainSegment,
                methodCalls::forcedMethodCallChain,
                methodCalls::methodCallChainIsSourceMultiline,
                objectCreations::brokenObjectCreation,
                objectCreations::objectCreationWithSuffix,
                conditionals::conditionalExpression,
                enclosedExpressions::parenthesizedBreak);
    }

    Doc expression(Expression expression) {
        return expressionRules.expression(expression);
    }

    Doc expressionWithoutOwnComment(Expression expression) {
        return expressionRules.expressionWithoutOwnComment(expression);
    }

    Doc annotation(AnnotationExpr annotation) {
        return annotationExpressions.annotation(annotation);
    }

    Doc annotationPreservingSourceBreaks(AnnotationExpr annotation) {
        return annotationExpressions.annotationPreservingSourceBreaks(annotation);
    }

    String annotationFlatText(AnnotationExpr annotation) {
        return annotationExpressions.annotationFlatText(annotation);
    }

    Doc assignmentStatement(AssignExpr expression) {
        return assignments.assignmentStatement(expression);
    }

    Doc returnStatement(Expression expression) {
        return returnExpressions.returnStatement(expression);
    }

    Doc objectCreationWithSuffix(ObjectCreationExpr expression, String suffix) {
        return objectCreations.objectCreationWithSuffix(expression, suffix);
    }

    String objectCreationPrefix(ObjectCreationExpr expression) {
        return objectCreations.objectCreationPrefix(expression);
    }

    Doc brokenMethodCall(MethodCallExpr expression) {
        return methodCalls.brokenMethodCall(expression);
    }

    Doc methodCall(MethodCallExpr expression) {
        return methodCalls.methodCall(expression);
    }

    Doc methodCallArgumentList(NodeList<Expression> arguments, Doc line) {
        return methodCalls.methodCallArgumentList(arguments, line);
    }

    String methodCallPrefix(MethodCallExpr expression) {
        return methodCalls.methodCallPrefix(expression);
    }

    Optional<Doc> sourceMultilineMethodCallStatement(MethodCallExpr expression, ExpressionStmt statement) {
        return methodCalls.sourceMultilineMethodCallStatement(expression, statement);
    }

    Optional<Doc> mixedFieldMethodCallChain(MethodCallExpr expression) {
        return methodCalls.mixedFieldMethodCallChain(expression);
    }

    Optional<Expression> mixedFieldMethodCallRoot(MethodCallExpr expression) {
        return methodCalls.mixedFieldMethodCallRoot(expression);
    }

    Optional<Doc> forcedMethodCallChain(MethodCallExpr expression) {
        return methodCalls.forcedMethodCallChain(expression);
    }

    Optional<Doc> forcedMethodCallChainWithSemicolon(MethodCallExpr expression) {
        return methodCalls.forcedMethodCallChainWithSemicolon(expression);
    }

    boolean methodCallChainHasComments(MethodCallExpr expression) {
        return methodCalls.methodCallChainHasComments(expression);
    }

    boolean methodCallChainHasFinalTrailingLineComment(MethodCallExpr expression) {
        return methodCalls.methodCallChainHasFinalTrailingLineComment(expression);
    }

    boolean methodCallChainIsSourceMultiline(MethodCallExpr expression) {
        return methodCalls.methodCallChainIsSourceMultiline(expression);
    }

    boolean methodCallChainRootIsObjectCreation(MethodCallExpr expression) {
        return methodCalls.methodCallChainRootIsObjectCreation(expression);
    }

    boolean methodCallChainRootIsFieldAccess(MethodCallExpr expression) {
        return methodCalls.methodCallChainRootIsFieldAccess(expression);
    }

    String methodCallChainFirstLine(MethodCallExpr expression) {
        return methodCalls.methodCallChainFirstLine(expression);
    }

    boolean shouldPrintScopeAsDoc(Expression expression) {
        return methodCalls.shouldPrintScopeAsDoc(expression);
    }

    boolean binaryHasLineComments(BinaryExpr expression) {
        return binaries.hasLineComments(expression);
    }

    Doc binaryLinesWithComments(BinaryExpr expression) {
        return binaries.linesWithComments(expression);
    }

    Doc binaryLines(Expression expression) {
        return binaries.lines(expression);
    }

    Doc binaryLines(Expression expression, boolean forceBreak) {
        return binaries.lines(expression, forceBreak);
    }

    boolean expressionHasParenthesizedNestedBinary(Expression expression) {
        return binaries.expressionHasParenthesizedNestedBinary(expression);
    }

    boolean shouldKeepCastDivisionContinuationFlat(BinaryExpr expression) {
        return binaries.shouldKeepCastDivisionContinuationFlat(expression);
    }

    Optional<Doc> suffixedEnclosedExpression(Expression expression, Boolean leadingBreak) {
        return enclosedSuffixes.suffixedEnclosedExpression(expression, leadingBreak);
    }

    Doc arrayAccessWithBrokenEnclosedName(ArrayAccessExpr expression) {
        return arrays.arrayAccessWithBrokenEnclosedName(expression);
    }

    boolean arrayCreationTypeBreaks(ArrayCreationExpr expression) {
        return arrays.arrayCreationTypeBreaks(expression);
    }

    String arrayCreationPrefix(ArrayCreationExpr expression) {
        return arrays.arrayCreationPrefix(expression);
    }

    Doc arrayInitializer(ArrayInitializerExpr expression) {
        return arrays.arrayInitializer(expression);
    }

    Doc arrayInitializer(ArrayInitializerExpr expression, boolean forceBreak) {
        return arrays.arrayInitializer(expression, forceBreak);
    }

    String compactArrayInitializerWithSourceSpacing(ArrayInitializerExpr expression, String values) {
        return arrays.compactArrayInitializerWithSourceSpacing(expression, values);
    }

    Doc castType(Type type) {
        return casts.castType(type);
    }

    Doc conditionalExpression(ConditionalExpr expression, boolean forceBreak) {
        return conditionals.conditionalExpression(expression, forceBreak);
    }

    boolean shouldBreakBeforeConditionalInitializer(ConditionalExpr expression) {
        return conditionals.shouldBreakBeforeConditionalInitializer(expression);
    }

    Optional<Doc> huggableBlockLambdaArguments(String prefix, NodeList<Expression> arguments) {
        return lambdas.huggableBlockLambdaArguments(prefix, arguments);
    }

    Optional<Doc> huggableBlockLambdaArguments(
            String prefix,
            NodeList<Expression> arguments,
            ToIntFunction<String> firstLineWidth) {
        return lambdas.huggableBlockLambdaArguments(prefix, arguments, firstLineWidth);
    }

    String lambdaParameters(LambdaExpr expression) {
        return lambdas.lambdaParameters(expression);
    }

    boolean lambdaParametersShouldBreak(LambdaExpr expression, String flatParameters) {
        return lambdas.lambdaParametersShouldBreak(expression, flatParameters);
    }

    Doc lambdaExpression(LambdaExpr expression) {
        return lambdas.lambdaExpression(expression);
    }

    private int currentIndentedWidth(String text) {
        return context.layoutWidth.currentIndented(text);
    }

    private int blockStatementWidth(String text) {
        return context.layoutWidth.blockStatement(text);
    }

    private int continuationStatementWidth(String text) {
        return context.layoutWidth.continuationStatement(text);
    }
}
