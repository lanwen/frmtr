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

    private final BiFunction<Expression, Boolean, Doc> binaryLines;

    private final BiFunction<MethodCallExpr, String, Doc> brokenMethodCallWithClosingLine;

    private final Function<MethodCallExpr, String> methodCallPrefix;

    ReturnBinaryExpressionLayout(
            FormatterOptions options,
            LayoutWidth layoutWidth,
            SourceShapePolicy sourceShapePolicy,
            Function<Expression, Doc> expressionRenderer,
            Function<Expression, String> compact,
            BiFunction<Expression, Boolean, Doc> binaryLines,
            BiFunction<MethodCallExpr, String, Doc> brokenMethodCallWithClosingLine,
            Function<MethodCallExpr, String> methodCallPrefix
    ) {
        this.options = options;
        this.layoutWidth = layoutWidth;
        this.sourceShapePolicy = sourceShapePolicy;
        this.expressionRenderer = expressionRenderer;
        this.compact = compact;
        this.binaryLines = binaryLines;
        this.brokenMethodCallWithClosingLine = brokenMethodCallWithClosingLine;
        this.methodCallPrefix = methodCallPrefix;
    }

    Optional<Doc> directBinaryReturn(BinaryExpr binaryExpr, LayoutContext layout) {
        return directBinaryReturn(binaryExpr, binaryExpr, layout, true);
    }

    Optional<Doc> directBinaryReturn(
            BinaryExpr binaryExpr,
            Expression widthAnchor,
            LayoutContext layout
    ) {
        return directBinaryReturn(binaryExpr, widthAnchor, layout, false);
    }

    private Optional<Doc> directBinaryReturn(
            BinaryExpr binaryExpr,
            Expression widthAnchor,
            LayoutContext layout,
            boolean allowSourceMultilineOverflowContinuation
    ) {
        if (sourceShapePolicy.hasContainedComments(binaryExpr)) {
            return Optional.empty();
        }
        if (directBinaryReturnLineFits(binaryExpr, widthAnchor, layout)) {
            return Optional.of(expressionRenderer.apply(binaryExpr));
        }
        Optional<Doc> methodCallLeft = directBinaryReturnWithMethodCallLeft(binaryExpr, layout);
        if (methodCallLeft.isPresent()) {
            return methodCallLeft;
        }
        if (
            directBinaryReturnFirstLineFits(binaryExpr, widthAnchor, layout)
            && directBinaryReturnContinuationFits(binaryExpr, allowSourceMultilineOverflowContinuation)
        ) {
            return Optional.of(Doc.indent(binaryLines.apply(binaryExpr, true)));
        }
        return Optional.empty();
    }

    private boolean directBinaryReturnLineFits(
            BinaryExpr expression,
            Expression widthAnchor,
            LayoutContext layout
    ) {
        String line = "return " + compact.apply(expression) + ";";
        return returnLineWidth(widthAnchor, line, layout) <= options.lineWidth();
    }

    private Optional<Doc> directBinaryReturnWithMethodCallLeft(
            BinaryExpr binaryExpr,
            LayoutContext layout
    ) {
        if (
            !(binaryExpr.getLeft() instanceof MethodCallExpr methodCall)
            || methodCall.getArguments().isEmpty()
            || methodCall.getArguments().size() == 1
            || sourceShapePolicy.hasContainedComments(methodCall)
            || sourceShapePolicy.hasContainedComments(binaryExpr.getRight())
            || !directBinaryReturnMethodCallFirstLineFits(methodCall, layout)
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
            LayoutContext layout
    ) {
        String line = "return " + methodCallPrefix.apply(methodCall) + "(";
        return returnLineWidth(methodCall, line, layout) <= options.lineWidth();
    }

    private boolean directBinaryReturnMethodCallClosingLineFits(BinaryExpr binaryExpr) {
        return layoutWidth.continuationStatement(
            methodCallBinaryReturnClosingLine(binaryExpr) + ";"
        ) <= options.lineWidth();
    }

    private String methodCallBinaryReturnClosingLine(BinaryExpr binaryExpr) {
        return ") " + binaryExpr.getOperator().asString() + " " + compact.apply(binaryExpr.getRight());
    }

    private boolean directBinaryReturnFirstLineFits(
            BinaryExpr expression,
            Expression widthAnchor,
            LayoutContext layout
    ) {
        String line = "return " + compact.apply(firstBinaryOperand(expression));
        return returnLineWidth(widthAnchor, line, layout) <= options.lineWidth();
    }

    private boolean directBinaryReturnContinuationFits(
            BinaryExpr expression,
            boolean allowSourceMultilineOverflowContinuation
    ) {
        String line = directBinaryReturnLastLinePrefix(expression)
            + compact.apply(lastBinaryOperand(expression))
            + ";";
        return layoutWidth.continuationStatement(line) <= options.lineWidth()
            || directBinaryReturnLastMethodCallOperandFits(expression);
    }

    private boolean directBinaryReturnLastMethodCallOperandFits(BinaryExpr expression) {
        Expression lastOperand = lastBinaryOperand(expression);
        if (
            !(lastOperand instanceof BinaryExpr binaryOperand)
            || !(binaryOperand.getLeft() instanceof MethodCallExpr methodCall)
            || sourceShapePolicy.hasContainedComments(methodCall)
            || sourceShapePolicy.hasContainedComments(binaryOperand.getRight())
            || methodCall.getArguments().isEmpty()
        ) {
            return false;
        }
        String firstLine = directBinaryReturnLastLinePrefix(expression) + methodCallPrefix.apply(methodCall) + "(";
        return layoutWidth.continuationStatement(firstLine) <= options.lineWidth()
            && directBinaryReturnMethodCallClosingLineFits(binaryOperand);
    }

    /**
     * Measures a candidate binary {@code return value;} line at the indentation it renders at — {@code "return "} at the
     * block indent via {@link LayoutWidth#nodeLine} (which counts the enclosing block/type nesting) — not at the value's
     * source column, which overshoots when a {@code return} sits after a label prefix. A {@code return} renders at least
     * two block/type levels deep, so {@code nodeLine} dominates and needs no baseline floor;
     * {@link #directBinaryReturnMethodCallFirstLineFits} folds its first-line probe into this same measurement.
     */
    private int returnLineWidth(Expression expression, String line, LayoutContext layout) {
        return layoutWidth.nodeLine(expression, line);
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
