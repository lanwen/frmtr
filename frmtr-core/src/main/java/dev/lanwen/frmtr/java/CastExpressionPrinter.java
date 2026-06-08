package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
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
    private final ToIntFunction<String> currentIndentedWidth;

    CastExpressionPrinter(
            FormatterOptions options,
            JavaFormatRule<Expression> expression,
            Function<Node, String> compactTypeLike,
            ToIntFunction<String> currentIndentedWidth) {
        this.options = options;
        this.expression = expression;
        this.compactTypeLike = compactTypeLike;
        this.currentIndentedWidth = currentIndentedWidth;
    }

    /**
     * Prints the cast type and then delegates the operand to the shared expression renderer.
     *
     * <p>The operand remains callback-owned because a cast can wrap any expression, including method calls, lambdas,
     * arrays, and conditionals whose layout decisions live in their own expression helpers.
     */
    Doc castExpression(CastExpr expression) {
        return Doc.concat(
                castType(expression.getType()),
                Doc.text(" "),
                this.expression.format(expression.getExpression()));
    }

    /**
     * Renders the parenthesized cast type, breaking only wide intersection casts.
     *
     * <p>Ordinary casts stay as {@code (Type)}. Intersection casts such as {@code (A & B & C)} get one type per line
     * only when the whole parenthesized type would overflow; that keeps the rare multi-bound cast readable without
     * changing short casts or handing compact type spelling to this helper.
     */
    Doc castType(Type type) {
        if (type instanceof IntersectionType intersectionType
                && currentIndentedWidth.applyAsInt("(" + compactTypeLike.apply(type) + ")") > options.lineWidth()) {
            List<Doc> elements = new ArrayList<>();
            for (int i = 0; i < intersectionType.getElements().size(); i++) {
                Type element = intersectionType.getElements().get(i);
                elements.add(Doc.text((i == 0 ? "" : "& ") + compactTypeLike.apply(element)));
            }
            return Doc.concat(
                    Doc.text("("),
                    Doc.indent(Doc.concat(
                            Doc.HARD_LINE,
                            Doc.join(Doc.HARD_LINE, elements))),
                    Doc.HARD_LINE,
                    Doc.text(")"));
        }
        return Doc.text("(" + compactTypeLike.apply(type) + ")");
    }

    /**
     * Counts the source pattern of casts nested through parenthesized method-call scopes.
     *
     * <p>The enclosed-expression caller uses this as a small readability fork: up to two nested cast scopes can stay
     * inline, while deeper cast chains break inside the surrounding parentheses. Only the method-call scope shape is
     * followed because that is the legacy pattern that produced hard-to-scan nested cast expressions.
     */
    int nestedCastDepth(Expression expression) {
        if (!(expression instanceof CastExpr castExpr)) {
            return 0;
        }
        return 1 + castExpr.getExpression()
                .toMethodCallExpr()
                .flatMap(MethodCallExpr::getScope)
                .filter(EnclosedExpr.class::isInstance)
                .map(EnclosedExpr.class::cast)
                .map(EnclosedExpr::getInner)
                .map(this::nestedCastDepth)
                .orElse(0);
    }
}
