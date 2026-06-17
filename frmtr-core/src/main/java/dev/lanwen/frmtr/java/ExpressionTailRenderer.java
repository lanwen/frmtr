package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.expr.Expression;
import dev.lanwen.frmtr.doc.Doc;

@FunctionalInterface
interface ExpressionTailRenderer {

    Doc render(Expression expression, ExpressionTail tail, LayoutWidth.LineBudget lineBudget);

    default Doc render(Expression expression, ExpressionTail tail) {
        return render(expression, tail, LayoutWidth.LineBudget.CURRENT);
    }
}
