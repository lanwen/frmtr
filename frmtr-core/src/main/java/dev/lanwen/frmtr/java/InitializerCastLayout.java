package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.IntersectionType;
import com.github.javaparser.ast.type.Type;
import dev.lanwen.frmtr.doc.Doc;
import java.util.List;
import java.util.function.Function;

/**
 * Owns the {@code (Type) value} cast-type break for an over-width initializer whose cast type itself absorbs the
 * overflow after {@code =}, reached once {@link #castTypeNeedsBreak} confirms the type shape can break at all.
 *
 * <p>This helper hosts the family that keeps the assignment and cast opener together while the type breaks
 * ({@link #variableWithCastTypeBreak}): whether the type shape can break at all (an intersection type or a generic
 * class/interface type) and the attach-versus-break-after-{@code =} ranking of the shared cast Doc, both ranked at
 * the true rendered column so no build-time width estimate is needed. It claims no ownership of casts whose value is
 * a method call (that shape stays with the caller's {@code CAST_METHOD_CALL_BREAK} arm) or of the type's own broken
 * rendering, which the caller's {@code expression} renderer already produces.
 */
final class InitializerCastLayout {

    private final Function<Expression, Doc> expression;

    InitializerCastLayout(Function<Expression, Doc> expression) {
        this.expression = expression;
    }

    /**
     * Keeps assignment and cast opener together when the cast type itself owns the first useful break, ranking that
     * attach against break-after-{@code =} on the true rendered first line over one shared cast Doc.
     *
     * <p>Simple casts still use the ordinary wide-initializer fallback because they do not provide an internal type break
     * that can absorb the overflow after {@code =}.
     */
    Doc variableWithCastTypeBreak(String name, CastExpr castExpr) {
        Doc initializer = expression.apply(castExpr);
        Doc attached = Doc.group(Doc.concat(Doc.text(name + " = "), initializer));
        Doc brokenAfterEquals = Doc.group(
            Doc.concat(Doc.text(name + " ="), Doc.indent(Doc.concat(Doc.LINE, initializer)))
        );
        return Doc.bestFittingFirstLine(List.of(attached, brokenAfterEquals), new int[] { 1, 0 });
    }

    boolean castTypeNeedsBreak(Type type) {
        return (
            type instanceof IntersectionType
            || (type instanceof ClassOrInterfaceType classOrInterfaceType
                && classOrInterfaceType.getTypeArguments().isPresent())
        );
    }
}
