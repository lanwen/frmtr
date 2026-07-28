package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Owns direct binary-expression layout in return statements.
 *
 * <p>Return statements have a binary-specific escape hatch: plain overflowing binary returns should use normal binary
 * continuations instead of inventing {@code return (...)} grouping, while explicit source grouping and comment-bearing
 * binaries still fall back to the caller's parenthesized expression policy. This helper decides which shapes are
 * eligible and hands the ranked list to the renderer, leaving {@link ReturnExpressionPrinter} to orchestrate the broader
 * return-expression tree.
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

    /**
     * The shape for a binary return value the author wrote inside parentheses. A comment-bearing binary offers no ranked
     * shape at all: only the parenthesized fallback keeps comment ownership obvious, so it takes the value whole.
     */
    Doc enclosedBinaryReturn(BinaryExpr binaryExpr, Expression widthAnchor, Doc parenthesizedFallback) {
        if (sourceShapePolicy.hasContainedComments(binaryExpr)) {
            return parenthesizedFallback;
        }
        List<Doc> brokenShapes = new ArrayList<>();
        if (continuationKeepsOperandsFlat(binaryExpr, widthAnchor)) {
            brokenShapes.add(continuationShape(binaryExpr));
        }
        brokenShapes.add(parenthesizedFallback);
        return rankedBinaryReturn(binaryExpr, brokenShapes);
    }

    /**
     * The shape for a bare (unparenthesized) binary return value. The operand-per-line continuation is itself the
     * always-valid last resort here — the author wrote no parentheses, so none are invented — which is also the shape a
     * comment-bearing binary takes directly.
     */
    Doc bareBinaryReturn(BinaryExpr binaryExpr) {
        if (sourceShapePolicy.hasContainedComments(binaryExpr)) {
            return continuationShape(binaryExpr);
        }
        return rankedBinaryReturn(binaryExpr, List.of(continuationShape(binaryExpr)));
    }

    /**
     * Offers the flat one-liner first and, when it does not fit, ranks the eligible broken shapes: the method-call-left
     * suffix form ahead of {@code remainingShapes}, whose own order is the caller's preference. Descending priorities
     * keep that order among the shapes that fit, so the renderer picks the most preferred fitting one.
     */
    private Doc rankedBinaryReturn(BinaryExpr binaryExpr, List<Doc> remainingShapes) {
        List<Doc> brokenShapes = new ArrayList<>();
        methodCallLeftShape(binaryExpr).ifPresent(brokenShapes::add);
        brokenShapes.addAll(remainingShapes);
        return Doc.conditionalGroup(List.of(
            expressionRenderer.apply(binaryExpr),
            Doc.bestFitting(brokenShapes, descendingPriorities(brokenShapes.size()))
        ));
    }

    /**
     * The {@code return call(}⏎{@code   args}⏎{@code ) op rhs} shape, offered when the left operand is a
     * multi-argument, comment-free call. Whether its closing line actually fits is the renderer's verdict.
     */
    private Optional<Doc> methodCallLeftShape(BinaryExpr binaryExpr) {
        if (
            !(binaryExpr.getLeft() instanceof MethodCallExpr methodCall)
            || methodCall.getArguments().size() < 2
            || sourceShapePolicy.hasContainedComments(methodCall)
            || sourceShapePolicy.hasContainedComments(binaryExpr.getRight())
        ) {
            return Optional.empty();
        }
        return Optional.of(
            brokenMethodCallWithClosingLine.apply(methodCall, methodCallBinaryReturnClosingLine(binaryExpr))
        );
    }

    private Doc continuationShape(BinaryExpr binaryExpr) {
        return Doc.indent(binaryLines.apply(binaryExpr, true));
    }

    /**
     * Whether dropping the author's parentheses for an operand-per-line continuation keeps every operand on its own
     * flat line. This is a shape-eligibility question, not a fit one: a continuation whose first or last operand must
     * itself break reads worse than the parenthesized block, so it is not offered at all — the renderer would otherwise
     * happily break that operand and rank the degenerate shape as fitting.
     */
    private boolean continuationKeepsOperandsFlat(BinaryExpr binaryExpr, Expression widthAnchor) {
        return firstOperandFitsOnReturnLine(binaryExpr, widthAnchor)
            && lastOperandFitsOnContinuationLine(binaryExpr);
    }

    /**
     * Measures the {@code return <first operand>} line at the indentation it renders at via
     * {@link LayoutWidth#nodeLine} (which counts the enclosing block/type nesting), not at the value's source column,
     * which overshoots when a {@code return} sits after a label prefix.
     */
    private boolean firstOperandFitsOnReturnLine(BinaryExpr expression, Expression widthAnchor) {
        String line = "return " + compact.apply(firstBinaryOperand(expression));
        return layoutWidth.nodeLine(widthAnchor, line) <= options.lineWidth();
    }

    private boolean lastOperandFitsOnContinuationLine(BinaryExpr expression) {
        String line = lastLinePrefix(expression) + compact.apply(lastBinaryOperand(expression)) + ";";
        return layoutWidth.continuationStatement(line) <= options.lineWidth()
            || lastOperandBreaksAsMethodCall(expression);
    }

    /**
     * The escape for a final operand that is itself a {@code call(args) op rhs} binary: it may exceed the continuation
     * line flat, because it breaks into the same call-plus-closing-line shape whose own lines fit.
     */
    private boolean lastOperandBreaksAsMethodCall(BinaryExpr expression) {
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
        String firstLine = lastLinePrefix(expression) + methodCallPrefix.apply(methodCall) + "(";
        return layoutWidth.continuationStatement(firstLine) <= options.lineWidth()
            && layoutWidth.continuationStatement(
                methodCallBinaryReturnClosingLine(binaryOperand) + ";"
            ) <= options.lineWidth();
    }

    private String methodCallBinaryReturnClosingLine(BinaryExpr binaryExpr) {
        return ") " + binaryExpr.getOperator().asString() + " " + compact.apply(binaryExpr.getRight());
    }

    private String lastLinePrefix(BinaryExpr expression) {
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

    private static int[] descendingPriorities(int size) {
        int[] priorities = new int[size];
        for (int i = 0; i < size; i++) {
            priorities[i] = size - 1 - i;
        }
        return priorities;
    }
}
