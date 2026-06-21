package dev.lanwen.frmtr.java;

import com.github.javaparser.Range;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.comments.BlockComment;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Prints declaration-prefix annotations and modifiers before declaration-specific headers are assembled.
 *
 * <p>This helper owns the shared sequencing between declaration annotations, inline annotations, and canonical modifier
 * text. The boundary exists because classes, fields, methods, records, modules, and local variables all need the same
 * prefix policy, while their broader declaration printers still own keyword, type, parameter, throws, and body layout.
 *
 * <p>Annotation expression layout stays with {@link AnnotationExpressionPrinter}; callers provide both rendered
 * annotation docs and compact annotation text as callbacks. Comments that sit between annotation nodes, or between the
 * final leading annotation and the declaration header, are kept here because this is the shared source-order boundary
 * for declaration-leading annotation stacks. This helper intentionally leaves declaration header assembly, member
 * sequencing, expression dispatch, and non-prefix source-comment attachment to the caller.
 */
final class DeclarationPrefixPrinter {

    private final CommentTracker comments;

    private final JavaCommentPlacementPolicy commentPlacement;

    private final JavaFormatRule<AnnotationExpr> annotationRenderer;

    private final Function<AnnotationExpr, String> annotationFlatText;

    DeclarationPrefixPrinter(
            CommentTracker comments,
            JavaCommentPlacementPolicy commentPlacement,
            JavaFormatRule<AnnotationExpr> annotationRenderer,
            Function<AnnotationExpr, String> annotationFlatText
    ) {
        this.comments = comments;
        this.commentPlacement = commentPlacement;
        this.annotationRenderer = annotationRenderer;
        this.annotationFlatText = annotationFlatText;
    }

    Doc annotations(NodeWithAnnotations<?> node) {
        return annotations(node, node.getAnnotations());
    }

    Doc declarationAnnotations(NodeWithAnnotations<?> node) {
        if (!(node instanceof NodeWithModifiers<?> nodeWithModifiers)) {
            return annotations(node);
        }
        return annotations(
            node,
            node.getAnnotations()
                    .stream()
                    .filter(annotation -> !afterAllModifiers(annotation, nodeWithModifiers))
                    .toList()
        );
    }

    boolean hasDeclarationAnnotations(NodeWithAnnotations<?> node) {
        if (!(node instanceof NodeWithModifiers<?> nodeWithModifiers)) {
            return !node.getAnnotations().isEmpty();
        }
        return node.getAnnotations()
                .stream()
                .anyMatch(annotation -> !afterAllModifiers(annotation, nodeWithModifiers));
    }

    private Doc annotations(NodeWithAnnotations<?> node, List<AnnotationExpr> annotations) {
        if (annotations.isEmpty()) {
            return Doc.EMPTY;
        }
        List<Doc> docs = new ArrayList<>();
        for (int index = 0; index < annotations.size(); index++) {
            AnnotationExpr annotation = annotations.get(index);
            docs.add(annotationRenderer.format(annotation));
            docs.add(Doc.HARD_LINE);
            if (index + 1 < annotations.size() && node instanceof Node owner) {
                docs.add(interAnnotationComments(owner, annotation, annotations.get(index + 1)));
            } else if (node instanceof Node owner) {
                docs.add(postAnnotationComments(owner, annotation));
            }
        }
        return Doc.concat(docs);
    }

    private Doc interAnnotationComments(Node owner, AnnotationExpr previous, AnnotationExpr next) {
        Optional<Range> previousRange = previous.getRange();
        Optional<Range> nextRange = next.getRange();
        if (previousRange.isEmpty() || nextRange.isEmpty()) {
            return Doc.EMPTY;
        }
        return commentsBetween(owner, previousRange.orElseThrow(), nextRange.orElseThrow());
    }

    /**
     * Keeps standalone notes that source placed after the last declaration-leading annotation but before the annotated
     * header token, such as a local variable type or method modifier.
     *
     * <p>A note on a line after the annotation is always kept. A note that shares the annotation's own line — e.g.
     * {@code @Deprecated /* ... *}{@code / public int port()} written inline — is kept only when it is a block comment:
     * a same-line <em>line</em> comment after an annotation is the annotation's own trailing comment, claimed by
     * {@link AnnotationExpressionPrinter}, so excluding it here avoids competing with that path. Without this, an inline
     * block comment between an annotation and its member was dropped entirely because the only printer that could emit it
     * required the comment to begin on a strictly later line than the annotation.
     */
    private Doc postAnnotationComments(Node owner, AnnotationExpr previous) {
        Optional<Range> previousRange = previous.getRange();
        Optional<Range> nextRange = firstNonAnnotationChildAfter(owner, previous);
        if (previousRange.isEmpty() || nextRange.isEmpty()) {
            return Doc.EMPTY;
        }
        Range annotationRange = previousRange.orElseThrow();
        return commentsBetween(
            owner,
            annotationRange,
            nextRange.orElseThrow(),
            comment -> comment.beginLine(Integer.MIN_VALUE) > annotationRange.end.line
                || comment.comment() instanceof BlockComment
        );
    }

    private Optional<Range> firstNonAnnotationChildAfter(Node owner, AnnotationExpr previous) {
        Optional<Range> previousRange = previous.getRange();
        if (previousRange.isEmpty()) {
            return Optional.empty();
        }
        return owner.getChildNodes()
                .stream()
                .filter(child -> !(child instanceof AnnotationExpr))
                .filter(child -> !(child instanceof Comment))
                .map(Node::getRange)
                .flatMap(Optional::stream)
                .filter(range -> startsAfter(range, previousRange.orElseThrow()))
                .min(this::compareRangeBegins);
    }

    private Doc commentsBetween(Node owner, Range previousRange, Range nextRange) {
        return commentsBetween(owner, previousRange, nextRange, ignored -> true);
    }

    private Doc commentsBetween(
            Node owner,
            Range previousRange,
            Range nextRange,
            Predicate<JavaCommentTrivia> predicate
    ) {
        return Doc.concat(
            commentPlacement.commentsOwnedByOrContainedIn(owner)
                    .stream()
                    .filter(predicate)
                    .filter(comment -> comment.comment()
                                .getRange()
                                .filter(range -> startsAfter(range, previousRange) && endsBefore(range, nextRange))
                                .isPresent()
                    )
                    .sorted(Comparator.comparing(JavaCommentTrivia::comment, CommentIndex.sourceOrderComparator()))
                    .map(comments::comment)
                    .filter(comment -> comment != Doc.EMPTY)
                    .map(comment -> Doc.concat(comment, Doc.HARD_LINE))
                    .toList()
        );
    }

    private boolean endsBefore(Range commentRange, Range annotationRange) {
        if (commentRange.end.line != annotationRange.begin.line) {
            return commentRange.end.line < annotationRange.begin.line;
        }
        return commentRange.end.column < annotationRange.begin.column;
    }

    private int compareRangeBegins(Range left, Range right) {
        int line = Integer.compare(left.begin.line, right.begin.line);
        if (line != 0) {
            return line;
        }
        return Integer.compare(left.begin.column, right.begin.column);
    }

    String inlineAnnotations(NodeWithAnnotations<?> node) {
        if (!(node instanceof NodeWithModifiers<?> nodeWithModifiers)) {
            return "";
        }
        String annotations = node.getAnnotations()
                .stream()
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
                .flatMap(annotationRange -> node.getModifiers()
                            .stream()
                            .map(Modifier::getRange)
                            .flatMap(Optional::stream)
                            .max(this::compareRangeEnds)
                            .map(modifierRange -> startsAfter(annotationRange, modifierRange))
                )
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
        return String.join(
            " ",
            node.getModifiers()
                    .stream()
                    .sorted(Comparator.comparingInt(this::modifierRank))
                    .map(this::modifier)
                    .toList()
        ) + " ";
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
