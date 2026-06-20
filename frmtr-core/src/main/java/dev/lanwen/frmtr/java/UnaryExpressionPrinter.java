package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.UnaryExpr;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Renders unary expressions whose operand can own useful internal line breaks.
 *
 * <p>This helper owns only unary expression shapes that should not fall back to raw compact text. In practice that is
 * the logical-complement form around a parenthesized binary expression, where the {@code !} prefix stays attached and
 * the binary tree breaks inside the existing parentheses. General unary spelling, operand dispatch, and binary
 * continuation policy remain with compact source text, expression dispatch, and {@link EnclosedExpressionPrinter}.
 */
final class UnaryExpressionPrinter {

    private final FormatterOptions options;

    private final Function<Node, String> compact;

    private final ToIntFunction<String> currentIndentedWidth;

    private final BiFunction<Expression, Boolean, Doc> parenthesizedBreak;

    UnaryExpressionPrinter(
            FormatterOptions options,
            Function<Node, String> compact,
            ToIntFunction<String> currentIndentedWidth,
            BiFunction<Expression, Boolean, Doc> parenthesizedBreak
    ) {
        this.options = options;
        this.compact = compact;
        this.currentIndentedWidth = currentIndentedWidth;
        this.parenthesizedBreak = parenthesizedBreak;
    }

    Doc unaryExpression(UnaryExpr expression) {
        if (
            expression.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT
            && expression.getExpression() instanceof EnclosedExpr enclosedExpr
            && enclosedExpr.getInner() instanceof BinaryExpr
            && expressionLineWidth(expression) > options.lineWidth()
        ) {
            return Doc.concat(Doc.text("!"), parenthesizedBreak.apply(enclosedExpr.getInner(), true));
        }
        return Doc.text(compact.apply(expression));
    }

    private int expressionLineWidth(UnaryExpr expression) {
        String flat = compact.apply(expression);
        int sourceWidth = expression.getRange()
                .map(range -> Math.max(0, range.begin.column - 1) + flat.length())
                .orElse(0);
        return Math.max(sourceWidth, currentIndentedWidth.applyAsInt(flat));
    }
}
