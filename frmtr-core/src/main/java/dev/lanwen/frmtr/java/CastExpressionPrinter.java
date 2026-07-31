package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.type.IntersectionType;
import com.github.javaparser.ast.type.Type;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Renders cast expressions after broad expression dispatch has selected cast syntax.
 *
 * <p>This helper owns the cast-specific layout decisions: parenthesized type rendering, the intersection type break
 * ranked against its flat form, operand rendering after the cast type, and the nested-cast depth probe used by
 * parenthesized expressions. The boundary exists because fields, local variables, and normal expression dispatch all
 * need the same cast-type shape, but the broader expression and enclosed-expression decision trees still belong to
 * {@link JavaPrinter}.
 *
 * <p>{@link JavaPrinter} still owns broad expression dispatch, compact type text, and the enclosing parentheses policy.
 * This helper receives those decisions as callbacks and only decides how a selected {@link CastExpr} and its cast type
 * are assembled.
 */
final class CastExpressionPrinter {

    private final ExpressionRendering rendering;

    private final Function<Node, String> compactTypeLike;

    private final Function<Type, Doc> typeBody;

    CastExpressionPrinter(
            ExpressionRendering rendering,
            Function<Node, String> compactTypeLike,
            Function<Type, Doc> typeBody
    ) {
        this.rendering = rendering;
        this.compactTypeLike = compactTypeLike;
        this.typeBody = typeBody;
    }

    /**
     * Prints the cast type and then delegates the operand to the shared expression renderer, reserving the operand's
     * own flat width so the cast type's flat-versus-broken ranking sees the same same-line tail a build-time estimate
     * used to measure explicitly.
     */
    Doc castExpression(CastExpr expression) {
        Doc operand = rendering.render(expression.getExpression());
        return Doc.followedBy(
            castType(expression.getType(), expression.getExpression()),
            Doc.concat(Doc.text(" "), operand)
        );
    }

    /**
     * Renders the parenthesized cast type, ranking a wide intersection cast's one-type-per-line break against its flat
     * form ({@code A & B & C}) at the true rendered column. A lambda operand keeps the intersection flat regardless of
     * width. Generic casts reuse the shared type-body renderer so long type arguments can break inside the cast.
     */
    Doc castType(Type type) {
        return castType(type, null);
    }

    private Doc castType(Type type, Expression operand) {
        Doc flat = Doc.group(Doc.concat(Doc.text("("), typeBody.apply(type), Doc.text(")")));
        if (!(type instanceof IntersectionType intersectionType) || operand instanceof LambdaExpr) {
            return flat;
        }
        return Doc.conditionalGroup(List.of(flat, intersectionCastOnePerLine(intersectionType)));
    }

    private Doc intersectionCastOnePerLine(IntersectionType intersectionType) {
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
            Doc.indent(Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.join(Doc.HARD_LINE, tail)))),
            Doc.HARD_LINE,
            Doc.text(")")
        );
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
