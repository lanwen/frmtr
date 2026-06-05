package dev.lanwen.frmtr.java;

import com.github.javaparser.Range;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import dev.lanwen.frmtr.doc.Doc;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Prints declaration-prefix annotations and modifiers before declaration-specific headers are assembled.
 *
 * <p>This helper owns the shared sequencing between declaration annotations, inline annotations, and canonical modifier
 * text. The boundary exists because classes, fields, methods, records, modules, and local variables all need the same
 * prefix policy, while their broader declaration printers still own keyword, type, parameter, throws, and body layout.
 *
 * <p>Annotation expression layout stays with {@link AnnotationExpressionPrinter}; callers provide both rendered
 * annotation docs and compact annotation text as callbacks. This helper intentionally leaves declaration header
 * assembly, member sequencing, expression dispatch, and source-comment attachment to the caller.
 */
final class DeclarationPrefixPrinter {
    private final Function<AnnotationExpr, Doc> annotationRenderer;
    private final Function<AnnotationExpr, String> annotationFlatText;

    DeclarationPrefixPrinter(
            Function<AnnotationExpr, Doc> annotationRenderer,
            Function<AnnotationExpr, String> annotationFlatText) {
        this.annotationRenderer = annotationRenderer;
        this.annotationFlatText = annotationFlatText;
    }

    Doc annotations(NodeWithAnnotations<?> node) {
        return annotations(node.getAnnotations());
    }

    Doc declarationAnnotations(NodeWithAnnotations<?> node) {
        if (!(node instanceof NodeWithModifiers<?> nodeWithModifiers)) {
            return annotations(node);
        }
        return annotations(node.getAnnotations().stream()
                .filter(annotation -> !afterAllModifiers(annotation, nodeWithModifiers))
                .toList());
    }

    boolean hasDeclarationAnnotations(NodeWithAnnotations<?> node) {
        if (!(node instanceof NodeWithModifiers<?> nodeWithModifiers)) {
            return !node.getAnnotations().isEmpty();
        }
        return node.getAnnotations().stream()
                .anyMatch(annotation -> !afterAllModifiers(annotation, nodeWithModifiers));
    }

    private Doc annotations(List<AnnotationExpr> annotations) {
        if (annotations.isEmpty()) {
            return Doc.EMPTY;
        }
        return Doc.concat(annotations.stream()
                .map(annotation -> Doc.concat(annotationRenderer.apply(annotation), Doc.HARD_LINE))
                .toList());
    }

    String inlineAnnotations(NodeWithAnnotations<?> node) {
        if (!(node instanceof NodeWithModifiers<?> nodeWithModifiers)) {
            return "";
        }
        String annotations = node.getAnnotations().stream()
                .filter(annotation -> afterAllModifiers(annotation, nodeWithModifiers))
                .map(annotation -> annotationFlatText.apply(annotation) + " ")
                .reduce("", String::concat);
        return annotations;
    }

    /**
     * Treats annotations written after every modifier as inline/type annotations instead of declaration-leading ones.
     *
     * <p>This preserves source shapes like {@code public @Nullable String value;} or
     * {@code public @Readonly String name()} where the annotation appears between the modifier list and the type or
     * member name. An annotation before a modifier remains a leading declaration annotation and is printed on its own
     * line above the declaration.
     */
    private boolean afterAllModifiers(AnnotationExpr annotation, NodeWithModifiers<?> node) {
        if (node.getModifiers().isEmpty()) {
            return false;
        }
        return annotation.getRange()
                .flatMap(annotationRange -> node.getModifiers().stream()
                        .map(Modifier::getRange)
                        .flatMap(Optional::stream)
                        .max(this::compareRangeEnds)
                        .map(modifierRange -> startsAfter(annotationRange, modifierRange)))
                .orElse(false);
    }

    private int compareRangeEnds(Range left, Range right) {
        int line = Integer.compare(left.end.line, right.end.line);
        if (line != 0) {
            return line;
        }
        return Integer.compare(left.end.column, right.end.column);
    }

    private boolean startsAfter(Range annotationRange, Range modifierRange) {
        if (annotationRange.begin.line != modifierRange.end.line) {
            return annotationRange.begin.line > modifierRange.end.line;
        }
        return annotationRange.begin.column > modifierRange.end.column;
    }

    String modifiers(NodeWithModifiers<?> node) {
        if (node.getModifiers().isEmpty()) {
            return "";
        }
        return String.join(" ", node.getModifiers().stream()
                        .sorted(Comparator.comparingInt(this::modifierRank))
                        .map(this::modifier)
                        .toList())
                + " ";
    }

    String modifier(Modifier modifier) {
        return modifier.getKeyword().asString();
    }

    private int modifierRank(Modifier modifier) {
        return switch (modifier.getKeyword()) {
            case PUBLIC -> 0;
            case PROTECTED -> 1;
            case PRIVATE -> 2;
            case ABSTRACT -> 3;
            case STATIC -> 4;
            case FINAL -> 5;
            case SEALED -> 6;
            case NON_SEALED -> 7;
            case SYNCHRONIZED -> 8;
            case NATIVE -> 9;
            case STRICTFP -> 10;
            case TRANSIENT -> 11;
            case VOLATILE -> 12;
            case TRANSITIVE -> 13;
            default -> 100;
        };
    }
}
