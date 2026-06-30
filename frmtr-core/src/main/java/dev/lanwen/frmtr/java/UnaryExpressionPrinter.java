package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AssignExpr;
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

    private final LayoutWidth layoutWidth;

    private final Function<Node, String> compact;

    private final ToIntFunction<String> currentIndentedWidth;

    private final BiFunction<Expression, Boolean, Doc> parenthesizedBreak;

    UnaryExpressionPrinter(
            FormatterOptions options,
            LayoutWidth layoutWidth,
            Function<Node, String> compact,
            ToIntFunction<String> currentIndentedWidth,
            BiFunction<Expression, Boolean, Doc> parenthesizedBreak
    ) {
        this.options = options;
        this.layoutWidth = layoutWidth;
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

    /**
     * Measures the complement at the indentation it will actually render at, not at the source column of its {@code !}.
     *
     * <p>The earlier estimate added {@code range.begin.column} to the flat text, but JavaParser counts a leading
     * {@code \t} as a single column, so a tab-indented over-width complement under-measured, looked like it fit, and was
     * emitted flat with the formatter's space indentation — at which point it genuinely overflowed and a later pass broke
     * it, so {@code format(format(x)) != format(x)}. The {@code if (...)} sibling of this bug was fixed in #135 by
     * measuring at the rendered indentation via {@link LayoutWidth#nodeLine}; that executor deferred the value case here
     * because a {@code nodeLine}-only measure omits the {@code Type name = } / {@code target = } prefix a complement keeps
     * when it stays on the assignment line, which would under-measure an inline complement and regress it to an over-width
     * flat line.
     *
     * <p>This measure sidesteps that prefix the way #161/#164 thread the assignment prefix into their width probes: an
     * over-width complement that is an assignment/initializer value always breaks onto the assignment continuation line
     * (the surrounding declaration/assignment already chose that break deterministically from its own rendered-indent
     * probe), so it is measured at the continuation indentation — one indent unit past the value's nesting depth via
     * {@link LayoutWidth#nodeIndentWidth}. That continuation column is never wider than the inline {@code prefix +} column,
     * so a complement that still fits inline after {@code =} also fits at the continuation indent and correctly stays flat;
     * no prefix reconstruction is needed. A complement in any other context renders on its own line at its nesting depth,
     * so it is measured with {@link LayoutWidth#nodeLine}. The {@code currentIndentedWidth} floor is kept so a complement
     * nested directly under a member (no enclosing block) is still measured against at least one indentation unit.
     */
    private int expressionLineWidth(UnaryExpr expression) {
        String flat = compact.apply(expression);
        int renderedWidth = isAssignmentOrInitializerValue(expression)
            ? layoutWidth.nodeIndentWidth(expression) + options.indentUnit().length() + flat.length()
            : layoutWidth.nodeLine(expression, flat);
        return Math.max(renderedWidth, currentIndentedWidth.applyAsInt(flat));
    }

    private boolean isAssignmentOrInitializerValue(UnaryExpr expression) {
        return expression.getParentNode()
                .map(parent -> parent instanceof VariableDeclarator
                    || (parent instanceof AssignExpr assignExpr && assignExpr.getValue() == expression))
                .orElse(false);
    }
}
