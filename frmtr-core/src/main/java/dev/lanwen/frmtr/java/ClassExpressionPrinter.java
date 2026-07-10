package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.ClassExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Renders class literal expressions after broad expression dispatch has selected {@code Type.class} syntax.
 *
 * <p>This helper owns the class-literal-specific split between compact type text and a width-driven wrap of a long
 * qualified type name. The boundary exists because class literals are expressions, but the part before {@code .class}
 * follows type syntax rather than field-access syntax; preserving that distinction lets the formatter break a qualified
 * {@code Outer.Inner.class} at its dots when it overflows the line without treating it like a method-call chain.
 *
 * <p>{@link JavaPrinter} still owns broad expression dispatch and compact source text. This helper receives compact
 * type text as a callback and only decides how a selected {@link ClassExpr} is assembled and wrapped.
 */
final class ClassExpressionPrinter {

    private final Function<Type, String> compactTypeLike;

    ClassExpressionPrinter(Function<Type, String> compactTypeLike) {
        this.compactTypeLike = compactTypeLike;
    }

    /**
     * Prints {@code Type.class}, keeping the compact form while it fits and breaking a long qualified type name by width.
     *
     * <p>A {@link ClassOrInterfaceType} literal is emitted as a conditional group: the compact {@code A.B.C.class} text
     * is preferred whenever it fits the space left on the line, and only when it overflows does the qualified name wrap.
     * The wrap breaks at the fewest dots needed, indenting each continued segment one level, and breaks any generic
     * argument list on an overflowing segment. Non class/interface literals (primitives, arrays, {@code void}) have no
     * qualified name to break and stay compact.
     */
    Doc classExpression(ClassExpr expression) {
        Type type = expression.getType();
        Doc compact = Doc.text(compactTypeLike.apply(type) + ".class");
        if (type instanceof ClassOrInterfaceType classOrInterfaceType) {
            return Doc.conditionalGroup(List.of(compact, qualifiedClassLiteral(classOrInterfaceType)));
        }
        return compact;
    }

    /**
     * Builds the width-driven fallback for a qualified class literal: the dotted segments packed so only the dots the
     * line width forces are broken, with the {@code .class} suffix riding on the last segment.
     */
    private Doc qualifiedClassLiteral(ClassOrInterfaceType type) {
        List<ClassOrInterfaceType> segments = new ArrayList<>();
        collectSegments(type, segments);
        List<Doc> pieces = new ArrayList<>();
        for (int index = 0; index < segments.size(); index++) {
            Doc segment = typeSegment(segments.get(index));
            pieces.add(index == 0 ? segment : Doc.concat(Doc.text("."), segment));
        }
        // The class-literal suffix rides on the last segment so the width probe that decides the final dot break
        // measures ".Segment.class" as one unit and never leaves ".class" to spill onto its own over-width line.
        int last = pieces.size() - 1;
        pieces.set(last, Doc.concat(pieces.get(last), Doc.text(".class")));
        return dottedName(pieces);
    }

    /**
     * Packs qualified segments with a break opportunity before each dotted continuation, breaking only the dots the line
     * width forces (a {@link Doc#fill(List)}). Each continuation that breaks indents one level under the class literal,
     * matching the continuation shape used for other dotted names; a single-segment name has no dot to break.
     */
    private Doc dottedName(List<Doc> pieces) {
        if (pieces.size() == 1) {
            return pieces.getFirst();
        }
        List<Doc> parts = new ArrayList<>();
        for (int index = 0; index < pieces.size(); index++) {
            if (index > 0) {
                parts.add(Doc.indent(Doc.SOFT_LINE));
            }
            parts.add(pieces.get(index));
        }
        return Doc.fill(parts);
    }

    private void collectSegments(ClassOrInterfaceType type, List<ClassOrInterfaceType> segments) {
        type.getScope().ifPresent(scope -> collectSegments(scope, segments));
        segments.add(type);
    }

    private Doc typeSegment(ClassOrInterfaceType type) {
        return Doc.concat(Doc.text(type.getNameAsString()), typeArguments(type));
    }

    private Doc typeArguments(ClassOrInterfaceType type) {
        return type.getTypeArguments().map(this::typeArgumentList).orElse(Doc.EMPTY);
    }

    /**
     * Renders a segment's generic argument list so it stays inline while it fits and breaks to one argument per line
     * when it overflows, so a class literal whose qualified name carries type arguments wraps by width rather than by
     * whatever line breaks the author happened to leave in the source.
     */
    private Doc typeArgumentList(NodeList<Type> arguments) {
        if (arguments.isEmpty()) {
            return Doc.text("<>");
        }
        return Doc.group(
            Doc.concat(
                Doc.text("<"),
                Doc.indent(Doc.concat(Doc.SOFT_LINE, Doc.joinComma(argumentDocs(arguments)))),
                Doc.SOFT_LINE,
                Doc.text(">")
            )
        );
    }

    private List<Doc> argumentDocs(NodeList<Type> arguments) {
        List<Doc> docs = new ArrayList<>();
        for (Type argument : arguments) {
            docs.add(Doc.text(compactTypeLike.apply(argument)));
        }
        return docs;
    }
}
