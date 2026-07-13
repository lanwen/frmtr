package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;

/**
 * Owns the operator-precedence rules that decide when a nested binary must keep explicit grouping parentheses for
 * {@link BinaryExpressionPrinter}.
 *
 * <p>This helper hosts the precedence-and-associativity family: the two deciders — {@link #shouldParenthesizeLeftBinary}
 * for a division/remainder left operand whose left-associative grouping is not otherwise preserved, and
 * {@link #shouldParenthesizeNestedBinary} for a nested operator sitting under an outer one — the operator-family
 * classifiers those deciders consult (arithmetic, additive, multiplicative, shift, bitwise, relational, equality), and
 * the {@link #expressionHasParenthesizedNestedBinary} scan that reports whether any nested binary in a subtree needs
 * those parentheses. The boundary exists so the binary printer's operand and left/right renderers — and the ternary and
 * control-condition callers that reach this through {@link BinaryExpressionPrinter}'s method handle — can consult one
 * precedence authority instead of each carrying the operator-family tables inline.
 *
 * <p>The helper is a pure function of the operator families and the expression tree: it reports whether grouping
 * parentheses are required, but never renders them, measures width, or decides a line shape. Emitting the parentheses,
 * choosing flat versus broken operands, and every width decision stay with the caller.
 */
final class NestedBinaryParenthesesLayout {

    /**
     * Handles the left side of division and remainder, where normal left associativity still needs extra grouping.
     *
     * <p>Cases such as {@code (a * b) / c} and {@code (a % b) / c} are only source-equivalent when the left nested
     * operation keeps its parentheses.
     */
    boolean shouldParenthesizeLeftBinary(BinaryExpr.Operator outer, BinaryExpr.Operator inner) {
        return (outer == BinaryExpr.Operator.DIVIDE || outer == BinaryExpr.Operator.REMAINDER)
            && (inner == BinaryExpr.Operator.MULTIPLY || inner == BinaryExpr.Operator.REMAINDER);
    }

    /**
     * Decides whether a nested binary operator must keep explicit parentheses under an outer operator.
     *
     * <p>The branches mirror Java precedence and associativity groups in simple families: multiplicative, additive,
     * shift, bitwise, and equality. Each true branch means flattening or raw compact text would change how the
     * expression reads, so the nested expression stays wrapped.
     */
    boolean shouldParenthesizeNestedBinary(BinaryExpr.Operator outer, BinaryExpr.Operator inner) {
        if (
            isMultiplicativeOperator(outer)
            && (inner == BinaryExpr.Operator.DIVIDE || inner == BinaryExpr.Operator.REMAINDER)
        ) {
            return true;
        }
        if (isAdditiveOperator(outer) && inner == BinaryExpr.Operator.REMAINDER) {
            return true;
        }
        if (isShiftOperator(outer) && (isArithmeticOperator(inner) || isShiftOperator(inner))) {
            return true;
        }
        if (
            isBitwiseOperator(outer)
            && (isShiftOperator(inner)
                || isRelationalOperator(inner)
                || isEqualityOperator(inner)
                || (outer == BinaryExpr.Operator.BINARY_OR
                    && (inner == BinaryExpr.Operator.BINARY_AND || inner == BinaryExpr.Operator.XOR))
                || (outer == BinaryExpr.Operator.XOR && inner == BinaryExpr.Operator.BINARY_AND))
        ) {
            return true;
        }
        return isEqualityOperator(outer) && isEqualityOperator(inner);
    }

    private boolean isShiftOperator(BinaryExpr.Operator operator) {
        return operator == BinaryExpr.Operator.LEFT_SHIFT
            || operator == BinaryExpr.Operator.SIGNED_RIGHT_SHIFT
            || operator == BinaryExpr.Operator.UNSIGNED_RIGHT_SHIFT;
    }

    private boolean isArithmeticOperator(BinaryExpr.Operator operator) {
        return operator == BinaryExpr.Operator.PLUS
            || operator == BinaryExpr.Operator.MINUS
            || operator == BinaryExpr.Operator.MULTIPLY
            || operator == BinaryExpr.Operator.DIVIDE
            || operator == BinaryExpr.Operator.REMAINDER;
    }

    private boolean isAdditiveOperator(BinaryExpr.Operator operator) {
        return operator == BinaryExpr.Operator.PLUS || operator == BinaryExpr.Operator.MINUS;
    }

    private boolean isMultiplicativeOperator(BinaryExpr.Operator operator) {
        return operator == BinaryExpr.Operator.MULTIPLY
            || operator == BinaryExpr.Operator.DIVIDE
            || operator == BinaryExpr.Operator.REMAINDER;
    }

    private boolean isRelationalOperator(BinaryExpr.Operator operator) {
        return operator == BinaryExpr.Operator.LESS
            || operator == BinaryExpr.Operator.GREATER
            || operator == BinaryExpr.Operator.LESS_EQUALS
            || operator == BinaryExpr.Operator.GREATER_EQUALS;
    }

    private boolean isBitwiseOperator(BinaryExpr.Operator operator) {
        return operator == BinaryExpr.Operator.BINARY_AND
            || operator == BinaryExpr.Operator.XOR
            || operator == BinaryExpr.Operator.BINARY_OR;
    }

    private boolean isEqualityOperator(BinaryExpr.Operator operator) {
        return operator == BinaryExpr.Operator.EQUALS || operator == BinaryExpr.Operator.NOT_EQUALS;
    }

    /**
     * Reports whether any nested binary in an expression needs explicit parentheses under the operator-family rules.
     *
     * <p>Callers use this before choosing a compact raw string for conditions or ternaries; when the predicate is true,
     * they ask expression rendering to rebuild the binary tree with the required parentheses instead.
     */
    boolean expressionHasParenthesizedNestedBinary(Expression expression) {
        return expression.findAll(BinaryExpr.class).stream().anyMatch(binary ->
            (binary.getLeft() instanceof BinaryExpr leftBinary
                && (shouldParenthesizeLeftBinary(binary.getOperator(), leftBinary.getOperator())
                    || shouldParenthesizeNestedBinary(binary.getOperator(), leftBinary.getOperator())))
                || (binary.getRight() instanceof BinaryExpr rightBinary
                    && shouldParenthesizeNestedBinary(binary.getOperator(), rightBinary.getOperator()))
        );
    }
}
