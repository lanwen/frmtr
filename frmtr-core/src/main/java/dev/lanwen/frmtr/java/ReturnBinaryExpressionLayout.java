package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Owns direct binary-expression layout in return statements.
 *
 * <p>Return statements have a binary-specific escape hatch: plain overflowing binary returns should use normal binary
 * continuations instead of inventing {@code return (...)} grouping, while explicit source grouping and comment-bearing
 * binaries still fall back to the caller's parenthesized expression policy. This helper keeps that return-binary
 * decision together and leaves {@link ReturnExpressionPrinter} to orchestrate the broader return-expression tree.
 */
final class ReturnBinaryExpressionLayout {

    private final FormatterOptions options;

    private final LayoutWidth layoutWidth;

    private final SourceShapePolicy sourceShapePolicy;

    private final Function<Expression, Doc> expressionRenderer;

    private final Function<Expression, String> compact;

    private final ToIntFunction<String> continuationStatementWidth;

    private final BiFunction<Expression, Boolean, Doc> binaryLines;

    private final BiFunction<MethodCallExpr, String, Doc> brokenMethodCallWithClosingLine;

    private final Function<MethodCallExpr, String> methodCallPrefix;

    ReturnBinaryExpressionLayout(
            FormatterOptions options,
            LayoutWidth layoutWidth,
            SourceShapePolicy sourceShapePolicy,
            Function<Expression, Doc> expressionRenderer,
            Function<Expression, String> compact,
            ToIntFunction<String> continuationStatementWidth,
            BiFunction<Expression, Boolean, Doc> binaryLines,
            BiFunction<MethodCallExpr, String, Doc> brokenMethodCallWithClosingLine,
            Function<MethodCallExpr, String> methodCallPrefix
    ) {
        this.options = options;
        this.layoutWidth = layoutWidth;
        this.sourceShapePolicy = sourceShapePolicy;
        this.expressionRenderer = expressionRenderer;
        this.compact = compact;
        this.continuationStatementWidth = continuationStatementWidth;
        this.binaryLines = binaryLines;
        this.brokenMethodCallWithClosingLine = brokenMethodCallWithClosingLine;
        this.methodCallPrefix = methodCallPrefix;
    }

    Optional<Doc> directBinaryReturn(BinaryExpr binaryExpr, LayoutWidth.LineBudget lineBudget) {
        return directBinaryReturn(binaryExpr, binaryExpr, lineBudget, true);
    }

    Optional<Doc> directBinaryReturn(
            BinaryExpr binaryExpr,
            Expression widthAnchor,
            LayoutWidth.LineBudget lineBudget
    ) {
        return directBinaryReturn(binaryExpr, widthAnchor, lineBudget, false);
    }

    /**
     * Reports whether return rendering should let ordinary expression dispatch keep a source-multiline string
     * concatenation around a multiline method-call argument.
     */
    boolean shouldUseExpressionRenderer(BinaryExpr expression) {
        return expression.getOperator() == BinaryExpr.Operator.PLUS
            && sourceShapePolicy.containsSourceMultilineMethodCallArgument(expression);
    }

    private Optional<Doc> directBinaryReturn(
            BinaryExpr binaryExpr,
            Expression widthAnchor,
            LayoutWidth.LineBudget lineBudget,
            boolean allowSourceMultilineOverflowContinuation
    ) {
        if (!binaryExpr.getAllContainedComments().isEmpty()) {
            return Optional.empty();
        }
        if (directBinaryReturnLineFits(binaryExpr, widthAnchor, lineBudget)) {
            return Optional.of(expressionRenderer.apply(binaryExpr));
        }
        Optional<Doc> methodCallLeft = directBinaryReturnWithMethodCallLeft(binaryExpr, lineBudget);
        if (methodCallLeft.isPresent()) {
            return methodCallLeft;
        }
        if (
            directBinaryReturnFirstLineFits(binaryExpr, widthAnchor, lineBudget)
            && directBinaryReturnContinuationFits(binaryExpr, allowSourceMultilineOverflowContinuation)
        ) {
            return Optional.of(Doc.indent(binaryLines.apply(binaryExpr, true)));
        }
        return Optional.empty();
    }

    private boolean directBinaryReturnLineFits(
            BinaryExpr expression,
            Expression widthAnchor,
            LayoutWidth.LineBudget lineBudget
    ) {
        String line = "return " + compact.apply(expression) + ";";
        return returnLineWidth(widthAnchor, line, lineBudget) <= options.lineWidth();
    }

    private Optional<Doc> directBinaryReturnWithMethodCallLeft(
            BinaryExpr binaryExpr,
            LayoutWidth.LineBudget lineBudget
    ) {
        if (
            !(binaryExpr.getLeft() instanceof MethodCallExpr methodCall)
            || methodCall.getArguments().isEmpty()
            || (methodCall.getArguments().size() == 1 && !sourceShapePolicy.methodCallArgumentsSpanMultipleLines(methodCall))
            || !methodCall.getAllContainedComments().isEmpty()
            || !binaryExpr.getRight().getAllContainedComments().isEmpty()
            || !directBinaryReturnMethodCallFirstLineFits(methodCall, lineBudget)
            || !directBinaryReturnMethodCallClosingLineFits(binaryExpr)
        ) {
            return Optional.empty();
        }
        return Optional.of(
            brokenMethodCallWithClosingLine.apply(methodCall, methodCallBinaryReturnClosingLine(binaryExpr))
        );
    }

    private boolean directBinaryReturnMethodCallFirstLineFits(
            MethodCallExpr methodCall,
            LayoutWidth.LineBudget lineBudget
    ) {
        String line = "return " + methodCallPrefix.apply(methodCall) + "(";
        return layoutWidth.line(lineBudget, line) <= options.lineWidth();
    }

    private boolean directBinaryReturnMethodCallClosingLineFits(BinaryExpr binaryExpr) {
        return continuationStatementWidth.applyAsInt(
            methodCallBinaryReturnClosingLine(binaryExpr) + ";"
        ) <= options.lineWidth();
    }

    private String methodCallBinaryReturnClosingLine(BinaryExpr binaryExpr) {
        return ") " + binaryExpr.getOperator().asString() + " " + compact.apply(binaryExpr.getRight());
    }

    private boolean directBinaryReturnFirstLineFits(
            BinaryExpr expression,
            Expression widthAnchor,
            LayoutWidth.LineBudget lineBudget
    ) {
        String line = "return " + compact.apply(firstBinaryOperand(expression));
        return returnLineWidth(widthAnchor, line, lineBudget) <= options.lineWidth();
    }

    private boolean directBinaryReturnContinuationFits(
            BinaryExpr expression,
            boolean allowSourceMultilineOverflowContinuation
    ) {
        if (
            allowSourceMultilineOverflowContinuation
            && sourceShapePolicy.wasMultiline(expression)
            && !hasUnparenthesizedAndUnderOr(expression)
            && !sourceShapePolicy.containsSourceMultilineMethodCallArgument(expression)
        ) {
            return true;
        }
        String line = directBinaryReturnLastLinePrefix(expression)
            + compact.apply(lastBinaryOperand(expression))
            + ";";
        return continuationStatementWidth.applyAsInt(line) <= options.lineWidth()
            || directBinaryReturnLastMethodCallOperandFits(expression);
    }

    private boolean directBinaryReturnLastMethodCallOperandFits(BinaryExpr expression) {
        Expression lastOperand = lastBinaryOperand(expression);
        if (
            !(lastOperand instanceof BinaryExpr binaryOperand)
            || !(binaryOperand.getLeft() instanceof MethodCallExpr methodCall)
            || !methodCall.getAllContainedComments().isEmpty()
            || !binaryOperand.getRight().getAllContainedComments().isEmpty()
            || methodCall.getArguments().isEmpty()
        ) {
            return false;
        }
        String firstLine = directBinaryReturnLastLinePrefix(expression) + methodCallPrefix.apply(methodCall) + "(";
        return continuationStatementWidth.applyAsInt(firstLine) <= options.lineWidth()
            && directBinaryReturnMethodCallClosingLineFits(binaryOperand);
    }

    /**
     * Measures a candidate binary {@code return value;} line at the indentation it will actually render at, not at the
     * source column the value sat in.
     *
     * <p>This mirrors {@link ReturnExpressionPrinter}'s width gate: the earlier estimate derived the second term from
     * {@code expression.getRange().begin.column}, the value's <em>source</em> column, so a {@code return} co-located
     * after a label prefix overshot 120 on the first pass and collapsed once the {@code return} moved onto its own line —
     * the {@code begin.column}-driven break-then-collapse cycle tracked in #137. {@link LayoutWidth#nodeLine} counts the
     * enclosing block/type nesting to reproduce the deterministic rendered indentation ({@code "return "} at the block
     * indent) regardless of source column, matching the source-column-to-rendered-column correction from #155/#161.
     */
    private int returnLineWidth(Expression expression, String line, LayoutWidth.LineBudget lineBudget) {
        int budgetWidth = layoutWidth.line(lineBudget, line);
        int renderedColumnWidth = layoutWidth.nodeLine(expression, line);
        return Math.max(budgetWidth, renderedColumnWidth);
    }

    private boolean hasUnparenthesizedAndUnderOr(Expression expression) {
        if (expression instanceof EnclosedExpr) {
            return false;
        }
        if (!(expression instanceof BinaryExpr binaryExpr)) {
            return false;
        }
        if (
            binaryExpr.getOperator() == BinaryExpr.Operator.OR
            && (unparenthesizedOperator(binaryExpr.getLeft(), BinaryExpr.Operator.AND)
                || unparenthesizedOperator(binaryExpr.getRight(), BinaryExpr.Operator.AND))
        ) {
            return true;
        }
        return hasUnparenthesizedAndUnderOr(binaryExpr.getLeft())
            || hasUnparenthesizedAndUnderOr(binaryExpr.getRight());
    }

    private boolean unparenthesizedOperator(Expression expression, BinaryExpr.Operator operator) {
        return !(expression instanceof EnclosedExpr)
            && expression instanceof BinaryExpr binaryExpr
            && binaryExpr.getOperator() == operator;
    }

    private String directBinaryReturnLastLinePrefix(BinaryExpr expression) {
        if (options.binaryOperatorPosition() == FormatterOptions.BinaryOperatorPosition.START) {
            return expression.getOperator().asString() + " ";
        }
        return "";
    }

    private Expression firstBinaryOperand(BinaryExpr expression) {
        Expression left = expression.getLeft();
        while (left instanceof BinaryExpr leftBinary && leftBinary.getOperator() == expression.getOperator()) {
            left = leftBinary.getLeft();
        }
        return left;
    }

    private Expression lastBinaryOperand(BinaryExpr expression) {
        Expression right = expression.getRight();
        while (right instanceof BinaryExpr rightBinary && rightBinary.getOperator() == expression.getOperator()) {
            right = rightBinary.getRight();
        }
        return right;
    }
}
