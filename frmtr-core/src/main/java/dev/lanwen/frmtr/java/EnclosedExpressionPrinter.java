package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Renders parenthesized expressions after broad expression dispatch has selected {@link EnclosedExpr}.
 *
 * <p>This helper owns the enclosed-expression decision tree: compact parenthesized expressions, cast chains that need an
 * inner break, conditionals that break inside parentheses, expression-statement lambdas, and the broken parenthesized
 * scope used before array, method-call, and method-reference suffixes. The boundary exists because several expression
 * helpers need the same parenthesized-scope shape, but the syntax inside the parentheses still belongs to its own
 * expression printer.
 *
 * <p>{@link JavaPrinter} still owns broad expression dispatch, compact source text, width calculations, and the
 * expression-specific policies for casts, lambdas, conditionals, and binary continuations. This helper receives those
 * decisions as callbacks and only chooses which parenthesized layout applies to a selected enclosed expression.
 */
final class EnclosedExpressionPrinter {
    private final FormatterOptions options;
    private final Function<Expression, Doc> expression;
    private final BiFunction<Expression, Boolean, Doc> binaryLines;
    private final Predicate<BinaryExpr> binaryExpressionHasLineComments;
    private final Function<BinaryExpr, Doc> binaryLinesWithComments;
    private final Function<Node, String> compact;
    private final ToIntFunction<String> currentIndentedWidth;
    private final ToIntFunction<String> continuationStatementWidth;
    private final ToIntFunction<Expression> nestedCastDepth;
    private final Function<LambdaExpr, Doc> parenthesizedLambdaBreak;
    private final BiFunction<ConditionalExpr, Boolean, Doc> conditionalExpression;

    EnclosedExpressionPrinter(
            FormatterOptions options,
            Function<Expression, Doc> expression,
            BiFunction<Expression, Boolean, Doc> binaryLines,
            Predicate<BinaryExpr> binaryExpressionHasLineComments,
            Function<BinaryExpr, Doc> binaryLinesWithComments,
            Function<Node, String> compact,
            ToIntFunction<String> currentIndentedWidth,
            ToIntFunction<String> continuationStatementWidth,
            ToIntFunction<Expression> nestedCastDepth,
            Function<LambdaExpr, Doc> parenthesizedLambdaBreak,
            BiFunction<ConditionalExpr, Boolean, Doc> conditionalExpression) {
        this.options = options;
        this.expression = expression;
        this.binaryLines = binaryLines;
        this.binaryExpressionHasLineComments = binaryExpressionHasLineComments;
        this.binaryLinesWithComments = binaryLinesWithComments;
        this.compact = compact;
        this.currentIndentedWidth = currentIndentedWidth;
        this.continuationStatementWidth = continuationStatementWidth;
        this.nestedCastDepth = nestedCastDepth;
        this.parenthesizedLambdaBreak = parenthesizedLambdaBreak;
        this.conditionalExpression = conditionalExpression;
    }

    /**
     * Chooses the shape of a normal parenthesized expression.
     *
     * <p>The forks mirror the old inline printer order. Cast chains stay inline up to depth two, but deeper chains break
     * inside the parentheses because repeated cast-plus-scope nesting becomes hard to scan. Parenthesized conditionals
     * use the assignment-style conditional break only when the compact parenthesized text overflows continuation width.
     * A lambda used as a whole expression statement needs the lambda-specific parenthesized break so the body does not
     * collapse into a compact parenthesized expression statement. If none of those cases applies, compact parentheses win
     * when they fit at the current indentation.
     */
    Doc enclosedExpression(EnclosedExpr expression) {
        if (expression.getInner() instanceof CastExpr) {
            if (nestedCastDepth.applyAsInt(expression.getInner()) <= 2) {
                return Doc.concat(Doc.text("("), this.expression.apply(expression.getInner()), Doc.text(")"));
            }
            return Doc.concat(
                    Doc.text("("),
                    Doc.indent(Doc.concat(Doc.HARD_LINE, this.expression.apply(expression.getInner()))),
                    Doc.HARD_LINE,
                    Doc.text(")"));
        }
        if (expression.getInner() instanceof ConditionalExpr conditionalExpr
                && continuationStatementWidth.applyAsInt(compact.apply(expression)) >= options.lineWidth()) {
            return Doc.concat(
                    Doc.text("("),
                    conditionalExpression.apply(conditionalExpr, true),
                    Doc.text(")"));
        }
        if (expression.getInner() instanceof LambdaExpr lambdaExpr
                && expression.getParentNode().filter(ExpressionStmt.class::isInstance).isPresent()) {
            return parenthesizedLambdaBreak.apply(lambdaExpr);
        }
        if (currentIndentedWidth.applyAsInt(compact.apply(expression)) <= options.lineWidth()) {
            return Doc.text(compact.apply(expression));
        }
        return Doc.concat(Doc.text("("), this.expression.apply(expression.getInner()), Doc.text(")"));
    }

    /**
     * Breaks an expression inside parentheses using the normal binary-continuation choice.
     *
     * <p>Return expressions use this entry point when the parenthesized value itself needs to move below {@code return},
     * but no suffix token forces binary content to break more aggressively.
     */
    Doc parenthesizedBreak(Expression expression) {
        return parenthesizedBreak(expression, false);
    }

    /**
     * Breaks an expression inside parentheses using the shared binary-continuation renderer.
     *
     * <p>The force flag is used by suffix callers that already know the enclosed scope is followed by another token, so
     * binary content should break even if the binary printer could otherwise keep it flat.
     */
    Doc parenthesizedBreak(Expression expression, boolean forceBinaryBreak) {
        return Doc.concat(
                Doc.text("("),
                Doc.indent(Doc.concat(Doc.HARD_LINE, parenthesizedBreakContent(expression, forceBinaryBreak))),
                Doc.HARD_LINE,
                Doc.text(")"));
    }

    private Doc parenthesizedBreakContent(Expression expression, boolean forceBinaryBreak) {
        if (expression instanceof BinaryExpr binaryExpr && binaryExpressionHasLineComments.test(binaryExpr)) {
            return binaryLinesWithComments.apply(binaryExpr);
        }
        return binaryLines.apply(expression, forceBinaryBreak);
    }

    /**
     * Renders a parenthesized receiver that must keep an array, call, or reference suffix attached after {@code )}.
     *
     * <p>Lambda scopes reuse the lambda-specific parenthesized break. Conditional scopes use a trailing conditional break
     * when there is no leading break yet, or when a leading-break caller would otherwise hide a nested binary condition.
     * All other scopes use the normal parenthesized break, forcing binary content to break whenever the suffix already
     * forced a leading break or the inner expression itself is binary.
     */
    Doc brokenEnclosedForSuffix(EnclosedExpr expression, boolean leadingBreak) {
        Expression inner = expression.getInner();
        if (inner instanceof LambdaExpr lambdaExpr) {
            return parenthesizedLambdaBreak.apply(lambdaExpr);
        }
        if (inner instanceof ConditionalExpr conditionalExpr
                && (!leadingBreak || conditionalConditionHasNestedBinary(conditionalExpr))) {
            return parenthesizedConditionalTrailingBreak(conditionalExpr);
        }
        return parenthesizedBreak(inner, leadingBreak || inner instanceof BinaryExpr);
    }

    /**
     * Detects the conditional condition shape that needs a trailing parenthesized break before a suffix.
     *
     * <p>A nested binary condition already contains its own continuation pressure, so suffix callers keep the condition
     * line intact and move the {@code ?} and {@code :} branches below it instead of asking the binary renderer to pick a
     * different nested layout.
     */
    private boolean conditionalConditionHasNestedBinary(ConditionalExpr expression) {
        return expression.getCondition() instanceof BinaryExpr binaryExpr
                && (binaryExpr.getLeft() instanceof BinaryExpr || binaryExpr.getRight() instanceof BinaryExpr);
    }

    /**
     * Keeps the conditional condition beside {@code (} and breaks only the trailing branches before a suffix.
     *
     * <p>This shape exists for parenthesized receivers such as {@code (condition ? a : b).call()}: the suffix reads more
     * naturally when it follows the closing parenthesis after the conditional branches, not after a fully nested binary
     * condition break.
     */
    private Doc parenthesizedConditionalTrailingBreak(ConditionalExpr expression) {
        return Doc.concat(
                Doc.text("("),
                this.expression.apply(expression.getCondition()),
                Doc.indent(Doc.concat(
                        Doc.HARD_LINE,
                        Doc.text("? "),
                        this.expression.apply(expression.getThenExpr()),
                        Doc.HARD_LINE,
                        Doc.text(": "),
                        this.expression.apply(expression.getElseExpr()))),
                Doc.HARD_LINE,
                Doc.text(")"));
    }
}
