package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.InstanceOfExpr;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Renders {@code instanceof} expressions after broad expression dispatch has selected instance checks.
 *
 * <p>This helper owns the instance-check layout decision tree: compact flat output when the expression fits, otherwise
 * a broken left-expression-plus-continuation form whose {@code instanceof} placement follows the configured binary
 * operator position. The boundary exists because pattern matching and ordinary type checks share the same break shape,
 * while the surrounding expression dispatcher still decides how left operands, compact pattern text, compact type text,
 * and width measurements are produced.
 *
 * <p>{@link JavaPrinter} still owns broad expression dispatch, compact source-derived text, compact type and pattern
 * text, and current indentation width. This helper receives those decisions as callbacks and only decides how a selected
 * {@link InstanceOfExpr} is assembled.
 */
final class InstanceOfExpressionPrinter {

    private final FormatterOptions options;

    private final JavaFormatRule<Expression> expression;

    private final Function<Node, String> compact;

    private final Function<Node, String> compactTypeLike;

    private final ToIntFunction<String> currentIndentedWidth;

    InstanceOfExpressionPrinter(
            FormatterOptions options,
            JavaFormatRule<Expression> expression,
            Function<Node, String> compact,
            Function<Node, String> compactTypeLike,
            ToIntFunction<String> currentIndentedWidth
    ) {
        this.options = options;
        this.expression = expression;
        this.compact = compact;
        this.compactTypeLike = compactTypeLike;
        this.currentIndentedWidth = currentIndentedWidth;
    }

    /**
     * Prints an {@code instanceof} check flat when it fits, otherwise breaks after the left expression.
     *
     * <p>The start/end binary operator setting controls which side of the line break owns the {@code instanceof} token:
     * start-position output begins the continuation line with {@code instanceof}, while end-position output keeps
     * {@code instanceof} with the left expression and moves only the checked type or pattern onto the next line. Pattern
     * text stays compact-source-owned so JavaParser pattern spelling is not reinterpreted in this helper.
     */
    Doc instanceOfExpression(InstanceOfExpr expression) {
        String flat = compact.apply(expression);
        if (currentIndentedWidth.applyAsInt(flat) <= options.lineWidth()) {
            return Doc.text(flat);
        }
        Doc left = this.expression.format(expression.getExpression());
        String right = expression.getPattern()
                .map(compact)
                .orElseGet(() -> compactTypeLike.apply(expression.getType()));
        if (options.binaryOperatorPosition() == FormatterOptions.BinaryOperatorPosition.START) {
            return Doc.concat(left, Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.text("instanceof " + right))));
        }
        return Doc.concat(left, Doc.text(" instanceof"), Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.text(right))));
    }
}
