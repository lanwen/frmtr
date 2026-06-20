package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.expr.Expression;
import dev.lanwen.frmtr.doc.Doc;

/**
 * Renders expression docs that need caller-owned suffix or separator tails before trailing line comments.
 *
 * <p>This helper boundary exists so statement terminators, list separators, and initializer tails can stay attached to
 * the expression content they close. It intentionally does not decide which expression printer should handle a node, or
 * whether a comment is trailing enough to require tail-aware rendering.
 */
@FunctionalInterface
interface ExpressionTailRenderer {
    Doc render(Expression expression, ExpressionTail tail, LayoutWidth.LineBudget lineBudget);

    default Doc render(Expression expression, ExpressionTail tail) {
        return render(expression, tail, LayoutWidth.LineBudget.CURRENT);
    }
}
