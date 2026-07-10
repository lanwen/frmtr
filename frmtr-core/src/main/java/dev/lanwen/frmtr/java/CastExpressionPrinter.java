package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.type.IntersectionType;
import com.github.javaparser.ast.type.Type;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Renders cast expressions after broad expression dispatch has selected cast syntax.
 *
 * <p>This helper owns the cast-specific layout decisions: parenthesized type rendering, line-width-aware intersection
 * type breaks, operand rendering after the cast type, and the nested-cast depth probe used by parenthesized expressions.
 * The boundary exists because fields, local variables, and normal expression dispatch all need the same cast-type shape,
 * but the broader expression and enclosed-expression decision trees still belong to {@link JavaPrinter}.
 *
 * <p>{@link JavaPrinter} still owns broad expression dispatch, compact type text, width calculation, and the enclosing
 * parentheses policy. This helper receives those decisions as callbacks and only decides how a selected {@link CastExpr}
 * and its cast type are assembled.
 */
final class CastExpressionPrinter {

    private final FormatterOptions options;

    private final JavaFormatRule<Expression> expression;

    private final Function<Node, String> compactTypeLike;

    private final Function<Node, String> compact;

    private final Function<Type, Doc> typeBody;

    private final ToIntFunction<String> currentIndentedWidth;

    private final ToIntFunction<String> continuationStatementWidth;

    CastExpressionPrinter(
            FormatterOptions options,
            JavaFormatRule<Expression> expression,
            Function<Node, String> compactTypeLike,
            Function<Node, String> compact,
            Function<Type, Doc> typeBody,
            ToIntFunction<String> currentIndentedWidth,
            ToIntFunction<String> continuationStatementWidth
    ) {
        this.options = options;
        this.expression = expression;
        this.compactTypeLike = compactTypeLike;
        this.compact = compact;
        this.typeBody = typeBody;
        this.currentIndentedWidth = currentIndentedWidth;
        this.continuationStatementWidth = continuationStatementWidth;
    }

    /**
     * Prints the cast type and then delegates the operand to the shared expression renderer.
     *
     * <p>The operand remains callback-owned because a cast can wrap any expression, including method calls, lambdas,
     * arrays, and conditionals whose layout decisions live in their own expression helpers.
     */
    Doc castExpression(CastExpr expression) {
        return Doc.concat(
            castType(expression.getType(), expression.getExpression()),
            Doc.text(" "),
            this.expression.format(expression.getExpression(), LayoutContext.root())
        );
    }

    /**
     * Renders the parenthesized cast type, breaking wide intersection casts or generic type bodies.
     *
     * <p>Ordinary casts stay as {@code (Type)}. Intersection casts such as {@code (A & B & C)} get one type per line
     * only when the whole parenthesized type would overflow; that keeps the rare multi-bound cast readable without
     * changing short casts. Generic casts reuse the shared type-body renderer so long type arguments can break inside the
     * cast instead of forcing the surrounding assignment to keep an over-wide atomic type string.
     */
    Doc castType(Type type) {
        return castType(type, null);
    }

    private Doc castType(Type type, Expression operand) {
        if (
            type instanceof IntersectionType intersectionType
            && intersectionCastShouldBreak(type, operand)
        ) {
            List<Doc> elements = new ArrayList<>();
            for (int i = 0; i < intersectionType.getElements().size(); i++) {
                Type element = intersectionType.getElements().get(i);
                elements.add(Doc.text((i == 0 ? "" : "& ") + compactTypeLike.apply(element)));
            }
            Doc first = elements.getFirst();
            List<Doc> tail = elements.subList(1, elements.size());
            return Doc.concat(
                Doc.text("("),
                first,
                Doc.indent(
                    Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.join(Doc.HARD_LINE, tail)))
                ),
                Doc.HARD_LINE,
                Doc.text(")")
            );
        }
        return Doc.group(Doc.concat(Doc.text("("), typeBody.apply(type), Doc.text(")")));
    }

    private boolean intersectionCastShouldBreak(Type type, Expression operand) {
        if (operand instanceof LambdaExpr) {
            return false;
        }
        String suffix = operand == null ? "" : " " + compact.apply(operand);
        String cast = "(" + compactTypeLike.apply(type) + ")" + suffix;
        int width = operand == null
            ? currentIndentedWidth.applyAsInt(cast)
            : Math.max(currentIndentedWidth.applyAsInt(cast), continuationStatementWidth.applyAsInt(cast));
        return width > options.lineWidth();
    }

    /**
     * Counts the source pattern of casts nested through parenthesized method-call scopes.
     *
     * <p>The enclosed-expression caller uses this as a small readability fork: up to two nested cast scopes can stay
     * inline, while deeper cast chains break inside the surrounding parentheses. Only the method-call scope shape is
     * followed because that is the pattern that produces hard-to-scan nested cast expressions.
     */
    int nestedCastDepth(Expression expression) {
        if (!(expression instanceof CastExpr castExpr)) {
            return 0;
        }
        return (
            1
            + castExpr.getExpression()
                    .toMethodCallExpr()
                    .flatMap(MethodCallExpr::getScope)
                    .filter(EnclosedExpr.class::isInstance)
                    .map(EnclosedExpr.class::cast)
                    .map(EnclosedExpr::getInner)
                    .map(this::nestedCastDepth)
                    .orElse(0)
        );
    }
}
