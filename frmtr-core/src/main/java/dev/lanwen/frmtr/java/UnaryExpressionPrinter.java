package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.UnaryExpr;
import dev.lanwen.frmtr.doc.Doc;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Renders unary expressions whose operand can own useful internal line breaks.
 *
 * <p>This helper owns only unary expression shapes that should not fall back to raw compact text. In practice that is
 * the logical-complement form around a parenthesized binary expression, where the {@code !} prefix stays attached and
 * the binary tree breaks inside the existing parentheses. General unary spelling, operand dispatch, and binary
 * continuation policy remain with compact source text, expression dispatch, and {@link EnclosedExpressionPrinter}.
 */
final class UnaryExpressionPrinter {

    private final Function<Node, String> compact;

    private final BiFunction<Expression, Boolean, Doc> parenthesizedBreak;

    UnaryExpressionPrinter(
            Function<Node, String> compact,
            BiFunction<Expression, Boolean, Doc> parenthesizedBreak
    ) {
        this.compact = compact;
        this.parenthesizedBreak = parenthesizedBreak;
    }

    /**
     * Chooses between the flat complement and a break inside the parentheses, deferring the flat-versus-broken width
     * decision to the renderer via {@link Doc#conditionalGroup}.
     *
     * <p>Only the structural predicate lives here: a logical-complement whose operand is a parenthesized binary
     * expression is the one unary shape whose operand can own useful internal breaks. Whether that complement actually
     * fits is measured by the renderer at the true running column — the flat alternative is rendered when it fits the
     * remaining width where the {@code !} lands, otherwise the broken alternative breaks the binary tree inside the
     * existing parentheses. This replaces an earlier width probe that reconstructed the rendered column from the source
     * layout and mis-measured tab indentation, which let a genuinely over-width complement print flat and then break on a
     * later pass, so {@code format(format(x)) != format(x)}. Measuring at the real column removes that column
     * reconstruction entirely, so the decision is idempotent by construction. Every other unary shape falls back to
     * compact source text.
     */
    Doc unaryExpression(UnaryExpr expression) {
        if (
            expression.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT
            && expression.getExpression() instanceof EnclosedExpr enclosedExpr
            && enclosedExpr.getInner() instanceof BinaryExpr
        ) {
            Doc flat = Doc.text(compact.apply(expression));
            Doc broken = Doc.concat(Doc.text("!"), parenthesizedBreak.apply(enclosedExpr.getInner(), true));
            return Doc.conditionalGroup(List.of(flat, broken));
        }
        return Doc.text(compact.apply(expression));
    }
}
