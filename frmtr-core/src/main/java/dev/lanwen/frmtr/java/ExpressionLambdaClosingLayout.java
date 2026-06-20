package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import java.util.Optional;

/**
 * Owns call-closing placement for source-multiline expression-lambda arguments.
 *
 * <p>Expression-lambda argument rendering already has to choose where the lambda body breaks; this helper isolates the
 * smaller source-shape question of whether the surrounding call's closing parenthesis should remain attached to that
 * body line. The caller still owns lambda planning, body docs, and argument-list rendering; this helper only preserves
 * the non-obvious same-line closing shape for simple logical lambda bodies.
 */
final class ExpressionLambdaClosingLayout {

    /**
     * Reports whether the call close should stay on the lambda body line for the same source shape.
     *
     * <p>The attached close is intentionally limited to simple two-term logical bodies whose operands are equality
     * comparisons. More complex logical bodies keep the closing parenthesis on its own line so added nesting or method
     * calls do not hide the call boundary.
     */
    boolean callClosingStaysOnLambdaBodyLine(LambdaExpr lambdaExpr, Expression bodyExpression) {
        return simpleTwoTermNonCallLogicalBody(bodyExpression)
            && lambdaExpr.getParentNode()
                    .filter(MethodCallExpr.class::isInstance)
                    .map(MethodCallExpr.class::cast)
                    .flatMap(parent -> parent.getRange().flatMap(
                            parentRange -> bodyExpression.getRange().map(
                                bodyRange -> parentRange.end.line == bodyRange.end.line
                            )
                    ))
                    .orElse(false);
    }

    private boolean simpleTwoTermNonCallLogicalBody(Expression bodyExpression) {
        Optional<BinaryExpr> logicalBody = logicalBinaryBody(bodyExpression);
        if (logicalBody.isEmpty()) {
            return false;
        }
        BinaryExpr binaryExpr = logicalBody.orElseThrow();
        return equalityComparisonOperand(binaryExpr.getLeft()) && equalityComparisonOperand(binaryExpr.getRight());
    }

    private Optional<BinaryExpr> logicalBinaryBody(Expression body) {
        if (body instanceof EnclosedExpr enclosedExpr) {
            return logicalBinaryBody(enclosedExpr.getInner());
        }
        if (
            body instanceof BinaryExpr binaryExpr
            && (binaryExpr.getOperator() == BinaryExpr.Operator.AND
                || binaryExpr.getOperator() == BinaryExpr.Operator.OR)
        ) {
            return Optional.of(binaryExpr);
        }
        return Optional.empty();
    }

    private boolean equalityComparisonOperand(Expression expression) {
        return expression instanceof BinaryExpr binaryExpr
            && (binaryExpr.getOperator() == BinaryExpr.Operator.EQUALS
                || binaryExpr.getOperator() == BinaryExpr.Operator.NOT_EQUALS);
    }
}
