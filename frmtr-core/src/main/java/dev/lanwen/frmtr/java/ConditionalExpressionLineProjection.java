package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import java.util.function.Function;

/**
 * Builds the single-line text used when a caller asks whether a conditional expression should break.
 *
 * <p>This helper owns the width-projection spelling for {@code condition ? then : else} so assignment, return, argument,
 * and initializer contexts do not each reconstruct ternary text independently. The boundary exists because the callers
 * own their surrounding tokens ({@code return}, {@code =}, commas, semicolons), while this helper owns only the
 * conditional expression's source-equivalent line shape.
 *
 * <p>Callers still decide the active indentation baseline and whether an overflowing projection should actually force a
 * broken conditional layout.
 */
final class ConditionalExpressionLineProjection {

    private final Function<Expression, String> compact;

    ConditionalExpressionLineProjection(Function<Expression, String> compact) {
        this.compact = compact;
    }

    String line(ConditionalExpr expression) {
        return compact.apply(expression.getCondition())
            + " ? "
            + compact.apply(expression.getThenExpr())
            + " : "
            + compact.apply(expression.getElseExpr());
    }
}
