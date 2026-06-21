package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Renders return-statement expressions after statement dispatch has already selected {@code return value;} syntax.
 *
 * <p>This helper owns the return-specific expression decision tree: the whole-return-line width gate, forced method-call
 * chains, forced conditional breaks, and parenthesized continuations for logical complements, enclosed expressions, and
 * binary expressions. The boundary exists because these choices depend on the surrounding {@code return} keyword and
 * semicolon, but the return statement itself still belongs to {@link StatementPrinter}.
 *
 * <p>{@link JavaPrinter} and the existing expression helpers still own broad expression dispatch, compact source text,
 * method-call chain layout, conditional layout, parenthesized expression breaks, and width calculations. This helper
 * keeps only the return-context branch order and receives every reusable formatting decision as a callback.
 */
final class ReturnExpressionPrinter {

    private final FormatterOptions options;

    private final LayoutWidth layoutWidth;

    private final ObjectCreationLayoutPolicy objectCreationLayoutPolicy;

    private final SourceShapePolicy sourceShapePolicy;

    private final Function<Expression, Doc> expression;

    private final ExpressionTailRenderer expressionWithTail;

    private final Function<LambdaExpr, Doc> brokenLambdaExpression;

    private final Function<Expression, String> compact;

    private final ConditionalExpressionLineProjection conditionalProjection;

    private final ToIntFunction<String> currentIndentedWidth;

    private final ToIntFunction<String> continuationStatementWidth;

    private final Function<MethodCallExpr, Optional<Doc>> sourceMultilineExpressionLambda;

    private final Function<MethodCallExpr, Optional<Doc>> sourceMultilineMethodCall;

    private final BiFunction<
        MethodCallExpr,
        LayoutWidth.LineBudget,
        Optional<Doc>
    > compactRootWithBrokenFinalChainSegment;

    private final BiFunction<MethodCallExpr, LayoutWidth.LineBudget, Optional<Doc>> forcedMethodCallChain;

    private final BiFunction<MethodCallExpr, ToIntFunction<String>, Optional<Doc>> forcedMethodCallChainWithFirstLine;

    private final Function<MethodCallExpr, Doc> brokenMethodCall;

    private final BiFunction<MethodCallExpr, String, Doc> brokenMethodCallWithClosingLine;

    private final Function<MethodCallExpr, String> methodCallPrefix;

    private final Predicate<MethodCallExpr> methodCallChainIsSourceMultiline;

    private final Predicate<MethodCallExpr> methodCallChainHasFinalTrailingLineComment;

    private final Predicate<MethodCallExpr> sourceMultilineExpressionLambdaBody;

    private final Function<ObjectCreationExpr, Doc> brokenObjectCreation;

    private final BiFunction<ObjectCreationExpr, String, Doc> objectCreationWithSuffix;

    private final BiFunction<ConditionalExpr, Boolean, Doc> conditionalExpression;

    private final BiFunction<Expression, Boolean, Doc> binaryLines;

    private final BiFunction<Expression, Boolean, Doc> parenthesizedBreak;

    private final ReturnBinaryExpressionLayout binaryReturns;

    ReturnExpressionPrinter(
            FormatterOptions options,
            LayoutWidth layoutWidth,
            ObjectCreationLayoutPolicy objectCreationLayoutPolicy,
            SourceShapePolicy sourceShapePolicy,
            Function<Expression, Doc> expression,
            ExpressionTailRenderer expressionWithTail,
            Function<LambdaExpr, Doc> brokenLambdaExpression,
            Function<Expression, String> compact,
            ToIntFunction<String> currentIndentedWidth,
            ToIntFunction<String> continuationStatementWidth,
            Function<MethodCallExpr, Optional<Doc>> sourceMultilineExpressionLambda,
            Function<MethodCallExpr, Optional<Doc>> sourceMultilineMethodCall,
            BiFunction<MethodCallExpr, LayoutWidth.LineBudget, Optional<Doc>> compactRootWithBrokenFinalChainSegment,
            BiFunction<MethodCallExpr, LayoutWidth.LineBudget, Optional<Doc>> forcedMethodCallChain,
            BiFunction<MethodCallExpr, ToIntFunction<String>, Optional<Doc>> forcedMethodCallChainWithFirstLine,
            Function<MethodCallExpr, Doc> brokenMethodCall,
            BiFunction<MethodCallExpr, String, Doc> brokenMethodCallWithClosingLine,
            Function<MethodCallExpr, String> methodCallPrefix,
            Predicate<MethodCallExpr> methodCallChainIsSourceMultiline,
            Predicate<MethodCallExpr> methodCallChainHasFinalTrailingLineComment,
            Predicate<MethodCallExpr> sourceMultilineExpressionLambdaBody,
            Function<ObjectCreationExpr, Doc> brokenObjectCreation,
            BiFunction<ObjectCreationExpr, String, Doc> objectCreationWithSuffix,
            BiFunction<ConditionalExpr, Boolean, Doc> conditionalExpression,
            BiFunction<Expression, Boolean, Doc> binaryLines,
            BiFunction<Expression, Boolean, Doc> parenthesizedBreak
    ) {
        this.options = options;
        this.layoutWidth = layoutWidth;
        this.objectCreationLayoutPolicy = objectCreationLayoutPolicy;
        this.sourceShapePolicy = sourceShapePolicy;
        this.expression = expression;
        this.expressionWithTail = expressionWithTail;
        this.brokenLambdaExpression = brokenLambdaExpression;
        this.compact = compact;
        this.conditionalProjection = new ConditionalExpressionLineProjection(compact);
        this.currentIndentedWidth = currentIndentedWidth;
        this.continuationStatementWidth = continuationStatementWidth;
        this.sourceMultilineExpressionLambda = sourceMultilineExpressionLambda;
        this.sourceMultilineMethodCall = sourceMultilineMethodCall;
        this.compactRootWithBrokenFinalChainSegment = compactRootWithBrokenFinalChainSegment;
        this.forcedMethodCallChain = forcedMethodCallChain;
        this.forcedMethodCallChainWithFirstLine = forcedMethodCallChainWithFirstLine;
        this.brokenMethodCall = brokenMethodCall;
        this.brokenMethodCallWithClosingLine = brokenMethodCallWithClosingLine;
        this.methodCallPrefix = methodCallPrefix;
        this.methodCallChainIsSourceMultiline = methodCallChainIsSourceMultiline;
        this.methodCallChainHasFinalTrailingLineComment = methodCallChainHasFinalTrailingLineComment;
        this.sourceMultilineExpressionLambdaBody = sourceMultilineExpressionLambdaBody;
        this.brokenObjectCreation = brokenObjectCreation;
        this.objectCreationWithSuffix = objectCreationWithSuffix;
        this.conditionalExpression = conditionalExpression;
        this.binaryLines = binaryLines;
        this.parenthesizedBreak = parenthesizedBreak;
        this.binaryReturns = new ReturnBinaryExpressionLayout(
            options,
            layoutWidth,
            sourceShapePolicy,
            expression,
            compact,
            continuationStatementWidth,
            binaryLines,
            brokenMethodCallWithClosingLine,
            methodCallPrefix
        );
    }

    Doc returnStatement(Expression expression) {
        return returnStatement(expression, LayoutWidth.LineBudget.BLOCK);
    }

    Doc returnStatement(Expression expression, LayoutWidth.LineBudget lineBudget) {
        if (expression instanceof ObjectCreationExpr objectCreation) {
            if (
                returnLineOverflows(objectCreation, lineBudget)
                || objectCreationLayoutPolicy.shouldPreserveReturnSourceMultilineArguments(objectCreation)
            ) {
                return Doc.concat(Doc.text("return "), brokenObjectCreation.apply(objectCreation), Doc.text(";"));
            }
            return Doc.concat(Doc.text("return "), objectCreationWithSuffix.apply(objectCreation, ";"));
        }
        if (
            expression instanceof MethodCallExpr methodCall
            && methodCallChainHasFinalTrailingLineComment.test(methodCall)
        ) {
            return Doc.concat(
                Doc.text("return "),
                expressionWithTail.render(methodCall, ExpressionTail.SEMICOLON, lineBudget)
            );
        }
        return Doc.concat(Doc.text("return "), returnExpression(expression, lineBudget), Doc.text(";"));
    }

    /**
     * Prints the return value flat unless the complete {@code return value;} line is too wide.
     *
     * <p>The gate checks the keyword and semicolon with the value because a value that fits by itself can still overflow
     * once it is placed inside a return statement. When the whole statement fits, expression dispatch keeps its ordinary
     * shape; only overflowing return lines enter the return-specific break tree.
     */
    Doc returnExpression(Expression expression) {
        return returnExpression(expression, LayoutWidth.LineBudget.BLOCK);
    }

    private Doc returnExpression(Expression expression, LayoutWidth.LineBudget lineBudget) {
        Optional<BinaryExpr> sourceMultilineEnclosedBinary = sourceMultilineEnclosedBinary(expression);
        if (sourceMultilineEnclosedBinary.isPresent()) {
            BinaryExpr binaryExpr = sourceMultilineEnclosedBinary.orElseThrow();
            return binaryReturns.directBinaryReturn(binaryExpr, expression, lineBudget).orElseGet(
                () -> parenthesizedBreak.apply(binaryExpr, true)
            );
        }
        if (sourceMultilineObjectCreation(expression)) {
            return brokenObjectCreation.apply((ObjectCreationExpr) expression);
        }
        if (
            expression instanceof ConditionalExpr conditionalExpr
            && conditionalReturnLineOverflows(conditionalExpr, lineBudget)
        ) {
            return conditionalExpression.apply(conditionalExpr, true);
        }
        if (
            expression instanceof MethodCallExpr methodCall
            && objectCreationRootMethodCallReturnLineOverflows(methodCall, lineBudget)
        ) {
            Optional<Doc> forcedChain = returnWithForcedMethodCallChain(methodCall, lineBudget);
            if (forcedChain.isPresent()) {
                return forcedChain.orElseThrow();
            }
        }
        if (
            expression instanceof MethodCallExpr methodCall
            && (methodCallChainIsSourceMultiline.test(methodCall)
                || sourceSpansMultipleLines(methodCall)
                || sourceMultilineExpressionLambdaBody.test(methodCall))
        ) {
            Optional<Doc> forcedChain = returnWithForcedMethodCallChain(methodCall, lineBudget);
            if (forcedChain.isPresent()) {
                return forcedChain.orElseThrow();
            }
        }
        if (returnLineFits(expression, lineBudget)) {
            return this.expression.apply(expression);
        }
        if (
            expression instanceof BinaryExpr binaryExpr
            && binaryReturns.shouldUseExpressionRenderer(binaryExpr)
        ) {
            return this.expression.apply(binaryExpr);
        }
        return brokenReturnExpression(expression, lineBudget).orElseGet(() -> this.expression.apply(expression));
    }

    private boolean objectCreationRootMethodCallReturnLineOverflows(
            MethodCallExpr expression,
            LayoutWidth.LineBudget lineBudget
    ) {
        Optional<Expression> scope = expression.getScope();
        if (scope.filter(ObjectCreationExpr.class::isInstance).isEmpty()) {
            return false;
        }
        String line = "return " + methodCallPrefix.apply(expression) + "(";
        return returnLineWidth(expression, line, lineBudget) > options.lineWidth();
    }

    private boolean conditionalReturnLineOverflows(ConditionalExpr expression, LayoutWidth.LineBudget lineBudget) {
        String line = "return " + conditionalProjection.line(expression) + ";";
        return returnLineWidth(expression, line, lineBudget) > options.lineWidth();
    }

    private Optional<BinaryExpr> sourceMultilineEnclosedBinary(Expression expression) {
        if (
            expression instanceof EnclosedExpr enclosedExpr
            && enclosedExpr.getInner() instanceof BinaryExpr binaryExpr
            && sourceShapePolicy.wasMultiline(expression)
        ) {
            return Optional.of(binaryExpr);
        }
        return Optional.empty();
    }

    private boolean sourceMultilineObjectCreation(Expression expression) {
        return expression instanceof ObjectCreationExpr objectCreationExpr
            && objectCreationLayoutPolicy.shouldPreserveSourceMultilineArguments(objectCreationExpr);
    }

    private boolean sourceSpansMultipleLines(Expression expression) {
        return sourceShapePolicy.wasMultiline(expression);
    }

    private boolean returnLineFits(Expression expression, LayoutWidth.LineBudget lineBudget) {
        return !returnLineOverflows(expression, lineBudget);
    }

    private boolean returnLineOverflows(Expression expression, LayoutWidth.LineBudget lineBudget) {
        String line = "return " + compact.apply(expression) + ";";
        return returnLineWidth(expression, line, lineBudget) > options.lineWidth();
    }

    private int returnLineWidth(Expression expression, String line, LayoutWidth.LineBudget lineBudget) {
        int budgetWidth = layoutWidth.line(lineBudget, line);
        int sourceColumnWidth = expression.getRange()
                .map(range -> Math.max(0, range.begin.column - 1 - "return ".length()) + line.length())
                .orElse(0);
        return Math.max(budgetWidth, sourceColumnWidth);
    }

    /**
     * Tries the width-triggered return branches in the same order as the old inline printer.
     *
     * <p>Method calls and conditionals are tried first because their helpers already know how to force a useful break for
     * the whole expression. Parenthesized-looking values are handled next so the long part moves inside parentheses
     * instead of leaving a wide value directly after {@code return}.
     */
    private Optional<Doc> brokenReturnExpression(Expression expression, LayoutWidth.LineBudget lineBudget) {
        Optional<Doc> methodCallChain = returnWithForcedMethodCallChain(expression, lineBudget);
        if (methodCallChain.isPresent()) {
            return methodCallChain;
        }
        Optional<Doc> conditionalBreak = returnWithForcedConditionalBreak(expression);
        if (conditionalBreak.isPresent()) {
            return conditionalBreak;
        }
        Optional<Doc> lambdaBreak = returnWithForcedLambdaBreak(expression);
        if (lambdaBreak.isPresent()) {
            return lambdaBreak;
        }
        Optional<Doc> logicalComplementBreak = returnWithLogicalComplementBreak(expression);
        if (logicalComplementBreak.isPresent()) {
            return logicalComplementBreak;
        }
        return returnWithParenthesizedValueBreak(expression, lineBudget);
    }

    private Optional<Doc> returnWithForcedMethodCallChain(Expression expression, LayoutWidth.LineBudget lineBudget) {
        if (!(expression instanceof MethodCallExpr methodCall)) {
            return Optional.empty();
        }
        Optional<Doc> expressionLambda = sourceMultilineExpressionLambda.apply(methodCall);
        if (expressionLambda.isPresent()) {
            return expressionLambda;
        }
        if (!methodCallChainIsSourceMultiline.test(methodCall)) {
            Optional<Doc> sourceMultilineCall = sourceMultilineMethodCall.apply(methodCall);
            if (sourceMultilineCall.isPresent()) {
                return sourceMultilineCall;
            }
        }
        if (methodCallChainIsSourceMultiline.test(methodCall)) {
            return forcedMethodCallChainWithFirstLine
                    .apply(methodCall, text -> returnLineWidth(methodCall, "return " + text, lineBudget))
                    .or(() -> forcedMethodCallChain.apply(methodCall, lineBudget));
        }
        if (methodCall.getScope().filter(ObjectCreationExpr.class::isInstance).isPresent()) {
            return forcedMethodCallChainWithFirstLine
                    .apply(methodCall, text -> returnLineWidth(methodCall, "return " + text, lineBudget))
                    .or(() -> forcedMethodCallChain.apply(methodCall, lineBudget));
        }
        return compactRootWithBrokenFinalChainSegment.apply(methodCall, lineBudget)
                .or(() -> forcedMethodCallChainWithFirstLine.apply(
                        methodCall,
                        text -> returnLineWidth(methodCall, "return " + text, lineBudget)
                ))
                .or(() -> forcedMethodCallChain.apply(methodCall, lineBudget))
                .or(() -> Optional.of(brokenMethodCall.apply(methodCall)));
    }

    private Optional<Doc> returnWithForcedConditionalBreak(Expression expression) {
        if (!(expression instanceof ConditionalExpr conditionalExpr)) {
            return Optional.empty();
        }
        return Optional.of(conditionalExpression.apply(conditionalExpr, true));
    }

    private Optional<Doc> returnWithForcedLambdaBreak(Expression expression) {
        if (!(expression instanceof LambdaExpr lambdaExpr) || lambdaExpr.getExpressionBody().isEmpty()) {
            return Optional.empty();
        }
        if (lambdaExpr.getExpressionBody().filter(MethodCallExpr.class::isInstance).isPresent()) {
            return Optional.of(this.expression.apply(lambdaExpr));
        }
        return Optional.of(brokenLambdaExpression.apply(lambdaExpr));
    }

    /**
     * Keeps {@code !} attached while breaking the enclosed operand inside its existing parentheses.
     *
     * <p>The logical-complement case is separate from ordinary enclosed expressions because the prefix operator should
     * stay visible at the return value start; only the inner parenthesized expression needs the multi-line shape.
     */
    private Optional<Doc> returnWithLogicalComplementBreak(Expression expression) {
        if (
            !(expression instanceof UnaryExpr unaryExpr)
            || unaryExpr.getOperator() != UnaryExpr.Operator.LOGICAL_COMPLEMENT
            || !(unaryExpr.getExpression() instanceof EnclosedExpr enclosedExpr)
        ) {
            return Optional.empty();
        }
        return Optional.of(Doc.concat(Doc.text("!"), parenthesizedBreak.apply(enclosedExpr.getInner(), false)));
    }

    /**
     * Breaks grouped return values by moving the long expression inside parentheses and direct binary values as
     * continuation lines.
     *
     * <p>Already enclosed expressions keep their source grouping and break only the inner value. Direct binary values use
     * the binary-expression policy directly unless comments inside the binary need the parenthesized shape to keep their
     * ownership obvious.
     */
    private Optional<Doc> returnWithParenthesizedValueBreak(Expression expression, LayoutWidth.LineBudget lineBudget) {
        if (expression instanceof EnclosedExpr enclosedExpr) {
            if (enclosedExpr.getInner() instanceof BinaryExpr binaryExpr) {
                Optional<Doc> directBinary = binaryReturns.directBinaryReturn(binaryExpr, enclosedExpr, lineBudget);
                if (directBinary.isPresent()) {
                    return directBinary;
                }
            }
            return Optional.of(parenthesizedBreak.apply(enclosedExpr.getInner(), false));
        }
        if (expression instanceof BinaryExpr binaryExpr) {
            Optional<Doc> directBinary = binaryReturns.directBinaryReturn(binaryExpr, lineBudget);
            if (directBinary.isPresent()) {
                return directBinary;
            }
            return Optional.of(parenthesizedBreak.apply(binaryExpr, false));
        }
        return Optional.empty();
    }
}
