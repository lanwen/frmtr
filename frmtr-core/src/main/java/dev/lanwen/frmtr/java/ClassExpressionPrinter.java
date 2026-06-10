package dev.lanwen.frmtr.java;

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
 * <p>This helper owns the class-literal-specific split between compact type text and source-multiline dotted type
 * names. The boundary exists because class literals are expressions, but the part before {@code .class} follows type
 * syntax rather than field-access syntax; preserving that distinction avoids rewriting source line breaks into spaces
 * before dotted type segments.
 *
 * <p>{@link JavaPrinter} still owns broad expression dispatch and compact source text. This helper receives compact
 * type text as a callback and only decides how a selected {@link ClassExpr} is assembled.
 */
final class ClassExpressionPrinter {
    private final Function<Type, String> compactTypeLike;

    ClassExpressionPrinter(Function<Type, String> compactTypeLike) {
        this.compactTypeLike = compactTypeLike;
    }

    /**
     * Prints {@code Type.class}, preserving source multiline breaks inside qualified type names.
     *
     * <p>When JavaParser gives us a {@link ClassOrInterfaceType} whose scoped segments were split in the source, the
     * formatter keeps legal dotted continuations such as {@code Outer.Inner} followed by a line that starts with
     * {@code .Nested.class}. Other class literals stay on compact type text.
     */
    Doc classExpression(ClassExpr expression) {
        Type type = expression.getType();
        if (type instanceof ClassOrInterfaceType classOrInterfaceType && sourceMultiline(type)) {
            return Doc.concat(sourceMultilineType(classOrInterfaceType), Doc.text(".class"));
        }
        return Doc.text(compactTypeLike.apply(type) + ".class");
    }

    private Doc sourceMultilineType(ClassOrInterfaceType type) {
        List<TypeSegment> segments = new ArrayList<>();
        collectSegments(type, segments);
        List<Doc> docs = new ArrayList<>();
        for (int index = 0; index < segments.size(); index++) {
            TypeSegment segment = segments.get(index);
            if (index == 0) {
                docs.add(Doc.text(segment.text()));
            } else if (segment.breakBefore()) {
                docs.add(Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.text("." + segment.text()))));
            } else {
                docs.add(Doc.text("." + segment.text()));
            }
        }
        return Doc.concat(docs);
    }

    private void collectSegments(ClassOrInterfaceType type, List<TypeSegment> segments) {
        type.getScope().ifPresent(scope -> collectSegments(scope, segments));
        boolean breakBefore = type.getScope().filter(scope -> startsOnLaterLine(scope, type)).isPresent();
        segments.add(new TypeSegment(typeSegmentText(type), breakBefore));
    }

    private String typeSegmentText(ClassOrInterfaceType type) {
        return type.getNameAsString()
                + type.getTypeArguments()
                        .map(arguments -> "<" + arguments.stream()
                                .map(compactTypeLike)
                                .reduce((left, right) -> left + ", " + right)
                                .orElse("") + ">")
                        .orElse("");
    }

    private boolean startsOnLaterLine(ClassOrInterfaceType scope, ClassOrInterfaceType type) {
        return scope.getRange()
                .flatMap(scopeRange -> type.getName().getRange()
                        .map(nameRange -> scopeRange.end.line < nameRange.begin.line))
                .orElse(false);
    }

    private boolean sourceMultiline(Type type) {
        return type.getRange()
                .map(range -> range.begin.line < range.end.line)
                .orElse(false);
    }

    private record TypeSegment(String text, boolean breakBefore) {}
}
