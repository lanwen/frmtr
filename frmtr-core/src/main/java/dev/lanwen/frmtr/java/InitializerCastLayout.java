package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.IntersectionType;
import com.github.javaparser.ast.type.Type;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.function.Function;

/**
 * Owns the {@code (Type) value} cast-type break for an over-width initializer whose cast type itself absorbs the
 * overflow after {@code =}, reached once {@link #castTypeNeedsBreak} confirms the type both can and needs to break.
 *
 * <p>This helper hosts the family that keeps the assignment and cast opener together while the type breaks
 * ({@link #variableWithCastTypeBreak}): whether the type shape can break at all (an intersection type or a generic
 * class/interface type), whether the {@code NAME = (Type<} opener still fits the assignment line, and the opener text
 * itself. It claims no ownership of casts whose value is a method call (that shape stays with the caller's
 * {@code CAST_METHOD_CALL_BREAK} arm) or of the type's own broken rendering, which the caller's {@code expression}
 * renderer already produces.
 */
final class InitializerCastLayout {

    private final FormatterOptions options;

    private final LayoutWidth layoutWidth;

    private final Function<Expression, Doc> expression;

    private final Function<Type, String> compactTypeLike;

    private final Function<ClassOrInterfaceType, String> typeNameWithoutArguments;

    InitializerCastLayout(
            FormatterOptions options,
            LayoutWidth layoutWidth,
            Function<Expression, Doc> expression,
            Function<Type, String> compactTypeLike,
            Function<ClassOrInterfaceType, String> typeNameWithoutArguments
    ) {
        this.options = options;
        this.layoutWidth = layoutWidth;
        this.expression = expression;
        this.compactTypeLike = compactTypeLike;
        this.typeNameWithoutArguments = typeNameWithoutArguments;
    }

    /**
     * Keeps assignment and cast opener together when the cast type itself owns the first useful break.
     *
     * <p>Simple casts still use the ordinary wide-initializer fallback because they do not provide an internal type break
     * that can absorb the overflow after {@code =}.
     */
    Doc variableWithCastTypeBreak(String name, String flatName, CastExpr castExpr) {
        Doc initializer = expression.apply(castExpr);
        if (castTypeOpenerFitsOnEqualsLine(flatName, castExpr.getType())) {
            return Doc.group(Doc.concat(Doc.text(name + " = "), initializer));
        }
        return Doc.group(Doc.concat(Doc.text(name + " ="), Doc.indent(Doc.concat(Doc.LINE, initializer))));
    }

    boolean castTypeNeedsBreak(String flatName, Type type) {
        // Measure the cast opener at the type's true rendered block/type depth rather than a fixed current-column
        // baseline. The cast type sits directly under the declarator (no intervening block/type), so it shares the
        // declarator's rendered depth.
        return castTypeCanBreak(type)
            && layoutWidth.nodeLine(type, flatName + " = (" + compactTypeLike.apply(type) + ")") > options.lineWidth();
    }

    private boolean castTypeOpenerFitsOnEqualsLine(String flatName, Type type) {
        // Measure the {@code NAME = (Type)} opener at the type's true rendered block/type depth, not the current column.
        return layoutWidth.nodeLine(type, flatName + " = " + castTypeOpener(type)) <= options.lineWidth();
    }

    private String castTypeOpener(Type type) {
        if (
            type instanceof ClassOrInterfaceType classOrInterfaceType
            && classOrInterfaceType.getTypeArguments().isPresent()
        ) {
            return "(" + typeNameWithoutArguments.apply(classOrInterfaceType) + "<";
        }
        return "(";
    }

    private boolean castTypeCanBreak(Type type) {
        return (
            type instanceof IntersectionType
            || (type instanceof ClassOrInterfaceType classOrInterfaceType
                && classOrInterfaceType.getTypeArguments().isPresent())
        );
    }
}
