package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Renders annotation expressions after broad expression dispatch has selected annotation syntax.
 *
 * <p>This helper owns annotation-expression layout: marker, normal, and single-member annotation shapes, trailing line
 * comments attached to annotation expressions, annotation member pairs, annotation array member values, compact
 * annotation text, raw string-literal tokens inside compact annotation values, and binary annotation-value
 * continuations. The boundary exists because declaration printers decide where annotations belong, while expression and
 * record-component callers need the same annotation-value formatting once that placement decision has already been made.
 *
 * <p>{@link JavaPrinter} still owns broad expression dispatch, declaration annotation grouping, modifier-sensitive
 * inline annotation placement, raw-source compact text for non-annotation nodes, and width calculations.
 * {@link BinaryExpressionPrinter} still owns binary continuation policy; this helper only asks for that shape when an
 * annotation value is a binary expression that must break.
 */
final class AnnotationExpressionPrinter {

    private final CommentTracker comments;

    private final JavaCommentPlacementPolicy commentPlacement;

    private final FormatterOptions options;

    private final ExpressionRendering rendering;

    private final BiFunction<Expression, Boolean, Doc> nestedBinaryLines;

    private final Function<Node, String> compact;

    private final ToIntFunction<String> currentIndentedWidth;

    AnnotationExpressionPrinter(
            CommentTracker comments,
            JavaCommentPlacementPolicy commentPlacement,
            FormatterOptions options,
            ExpressionRendering rendering,
            BiFunction<Expression, Boolean, Doc> nestedBinaryLines,
            Function<Node, String> compact,
            ToIntFunction<String> currentIndentedWidth
    ) {
        this.comments = comments;
        this.commentPlacement = commentPlacement;
        this.options = options;
        this.rendering = rendering;
        this.nestedBinaryLines = nestedBinaryLines;
        this.compact = compact;
        this.currentIndentedWidth = currentIndentedWidth;
    }

    /**
     * Chooses the annotation-expression branch while preserving comments attached to the annotation node itself.
     *
     * <p>Normal and single-member annotations have different width/breaking rules; marker annotations are only the name.
     * A trailing line comment is attached after the branch-specific doc is built.
     *
     * <p>It is <em>not</em> attached here for an annotation-array element: that slot
     * ({@link #annotationArrayValueLine(Expression, List)}) owns the separator/comment order and must emit
     * {@code @Elem(...), // note}, so baking the comment in first would produce the non-compiling
     * {@code @Elem(...) // note,}.
     */
    Doc annotation(AnnotationExpr annotation) {
        Doc formatted = switch (annotation) {
            case NormalAnnotationExpr normalAnnotation -> normalAnnotation(normalAnnotation);
            case SingleMemberAnnotationExpr singleMemberAnnotation -> singleMemberAnnotation(singleMemberAnnotation);
            default -> Doc.text("@" + compact.apply(annotation.getName()));
        };
        if (isAnnotationArrayElement(annotation)) {
            return formatted;
        }
        Doc trailing = comments.ownTriviaComment(
            annotation,
            comment -> comment.isLine()
                    && comment.startsOnBeginLine(annotation.getName())
                    && comment.startsAfterNodeOnSameLine(annotation.getName())
        );
        if (trailing != Doc.EMPTY) {
            return Doc.concat(formatted, Doc.text(" "), trailing);
        }
        return formatted;
    }

    private static boolean isAnnotationArrayElement(AnnotationExpr annotation) {
        return annotation.getParentNode().filter(ArrayInitializerExpr.class::isInstance).isPresent();
    }

    /**
     * Renders a parameter or record-component annotation, breaking it structured when it cannot stay flat at its true
     * rendered column.
     *
     * <p>The break is width-driven at the real column: {@link LayoutContext#leftEdgePrefix()} carries the annotation's
     * first-line text ahead of it and {@link LayoutContext#trailingContent()} the {@code " Type name"} the caller emits
     * after it. When the flat annotation plus that context overflows, it renders structured so its {@code )} carries the
     * type/name onto a relieved line; otherwise it stays flat.
     */
    Doc annotationPreservingSourceBreaks(AnnotationExpr annotation, LayoutContext layout) {
        if (!annotationOverflowsAtColumn(annotation, layout)) {
            return annotation(annotation);
        }
        return switch (annotation) {
            case NormalAnnotationExpr normalAnnotation -> brokenNormalAnnotation(normalAnnotation);
            case SingleMemberAnnotationExpr singleMemberAnnotation -> brokenSingleMemberAnnotation(
                "@" + compact.apply(singleMemberAnnotation.getName()),
                annotationValue(singleMemberAnnotation.getMemberValue())
            );
            default -> annotation(annotation);
        };
    }

    /**
     * Whether the flat annotation, rendered at its true column and followed by the caller's same-line trailing content,
     * exceeds the line width. A marker annotation ({@code @Name}) is never broken because it has no parenthesized body to
     * break; only normal and single-member annotations carry a group the structured shape can open.
     */
    private boolean annotationOverflowsAtColumn(AnnotationExpr annotation, LayoutContext layout) {
        if (!(annotation instanceof NormalAnnotationExpr) && !(annotation instanceof SingleMemberAnnotationExpr)) {
            return false;
        }
        String firstLine = layout.leftEdgePrefix() + annotationFlatText(annotation) + layout.trailingContent();
        return currentIndentedWidth.applyAsInt(firstLine) > options.lineWidth();
    }

    /**
     * Renders {@code @Name(...)} member pairs, keeping empty normal-annotation parentheses explicit.
     *
     * <p>An empty normal annotation remains {@code @Name()} instead of collapsing to a marker annotation because the
     * source chose the normal-annotation form. A pair whose value forces a break (comments, a text block) routes straight
     * to the broken shape; otherwise the renderer ranks the flat line against the broken shape by true fit.
     */
    private Doc normalAnnotation(NormalAnnotationExpr annotation) {
        String prefix = "@" + compact.apply(annotation.getName());
        if (annotation.getPairs().isEmpty()) {
            return Doc.text(prefix + "()");
        }
        Doc broken = brokenNormalAnnotation(annotation);
        boolean forcesBreak = annotation.getPairs()
                .stream()
                .map(MemberValuePair::getValue)
                .anyMatch(value -> annotationValueHasLineComments(value) || annotationValueMustBreak(value));
        if (forcesBreak) {
            return broken;
        }
        String flat = prefix + "(" + compactJoinAnnotationPairs(annotation.getPairs()) + ")";
        return Doc.bestFittingFirstLine(List.of(Doc.text(flat), broken));
    }

    /**
     * Breaks {@code @Name(...)} onto one indented line per pair, except that a lone array-valued pair hugs: the
     * annotation keeps {@code @Name(pair = {} on its own line and closes with {@code })}, so only the array elements
     * break. Two indent levels of pure punctuation buy nothing when there is a single pair to place.
     */
    private Doc brokenNormalAnnotation(NormalAnnotationExpr annotation) {
        String prefix = "@" + compact.apply(annotation.getName());
        Optional<Doc> hugged = loneArrayPairHug(annotation, prefix);
        if (hugged.isPresent()) {
            return hugged.orElseThrow();
        }
        return Doc.concat(
            Doc.text(prefix + "("),
            Doc.indent(
                Doc.concat(
                    Doc.HARD_LINE,
                    Doc.join(
                        Doc.concat(Doc.text(","), Doc.HARD_LINE),
                        annotation.getPairs().stream().map(this::annotationPair).toList()
                    )
                )
            ),
            Doc.HARD_LINE,
            Doc.text(")")
        );
    }

    /**
     * The hugged shape for an annotation whose single pair is an array value: {@code @Name(pair = {}, the elements one
     * per indented line, then {@code })}. Empty for anything else, so multi-pair annotations keep the exploded form.
     *
     * <p>An empty array is left out too: {@code {}} carries no elements to break onto lines, so hugging it would only
     * move the braces without relieving the width that asked for a break.
     */
    private Optional<Doc> loneArrayPairHug(NormalAnnotationExpr annotation, String prefix) {
        if (annotation.getPairs().size() != 1) {
            return Optional.empty();
        }
        MemberValuePair pair = annotation.getPairs().get(0);
        if (
            !(pair.getValue() instanceof ArrayInitializerExpr arrayInitializerExpr)
            || arrayInitializerExpr.getValues().isEmpty()
        ) {
            return Optional.empty();
        }
        return Optional.of(Doc.concat(
            Doc.text(prefix + "(" + pair.getNameAsString() + " = "),
            annotationArrayInitializer(arrayInitializerExpr),
            Doc.text(")")
        ));
    }

    /**
     * Renders {@code @Name(value)}, ranking the flat line against the annotation's broken shapes by true fit.
     *
     * <p>An array value offers three candidates flattest-first: the whole annotation flat, the array kept flat but moved
     * onto its own indented line, and the fully broken array. A binary value only offers flat-vs-broken because its
     * operator lines need the same indented slot the broken shape already gives it. Any other value falls through to
     * {@link #annotationValue}, which decides its own breaking, so only the enclosing parens are ranked.
     */
    private Doc singleMemberAnnotation(SingleMemberAnnotationExpr annotation) {
        String prefix = "@" + compact.apply(annotation.getName());
        Expression memberValue = annotation.getMemberValue();
        if (annotationValueHasLineComments(memberValue) || annotationValueMustBreak(memberValue)) {
            return brokenSingleMemberAnnotation(prefix, annotationValue(memberValue));
        }
        String flatValue = compactAnnotationValue(memberValue);
        Doc flat = Doc.text(prefix + "(" + flatValue + ")");
        if (memberValue instanceof ArrayInitializerExpr) {
            return Doc.bestFittingFirstLine(List.of(
                flat,
                brokenSingleMemberAnnotation(prefix, Doc.text(flatValue)),
                Doc.concat(Doc.text(prefix + "("), annotationValue(memberValue), Doc.text(")"))
            ));
        }
        if (memberValue instanceof BinaryExpr) {
            return Doc.bestFittingFirstLine(
                List.of(flat, brokenSingleMemberAnnotation(prefix, annotationValue(memberValue)))
            );
        }
        return Doc.bestFittingFirstLine(
            List.of(flat, Doc.concat(Doc.text(prefix + "("), annotationValue(memberValue), Doc.text(")")))
        );
    }

    private Doc brokenSingleMemberAnnotation(String prefix, Doc value) {
        return Doc.concat(
            Doc.text(prefix + "("),
            Doc.indent(Doc.concat(Doc.HARD_LINE, value)),
            Doc.HARD_LINE,
            Doc.text(")")
        );
    }

    /**
     * Renders one {@code name = value} pair. The value's own renderer owns its flat-versus-broken ranking, so an array
     * value keeps its comment guard: a compacted flat array text cannot carry a {@code //}, and only that renderer knows.
     */
    private Doc annotationPair(MemberValuePair pair) {
        return Doc.concat(Doc.text(pair.getNameAsString() + " = "), annotationValue(pair.getValue()));
    }

    /**
     * Builds compact annotation text for width checks and inline annotation placement.
     *
     * <p>The text mirrors the structured annotation branches: normal annotations keep pair names, single-member
     * annotations omit {@code value =}, and marker annotations are only the name. Annotation values use the
     * annotation-specific compact path so string literal tokens are not normalized by the broader compact fallback.
     */
    String annotationFlatText(AnnotationExpr annotation) {
        return switch (annotation) {
            case NormalAnnotationExpr normalAnnotation -> "@"
                + compact.apply(normalAnnotation.getName())
                + "("
                + compactJoinAnnotationPairs(normalAnnotation.getPairs())
                + ")";
            case SingleMemberAnnotationExpr singleMemberAnnotation -> "@"
                + compact.apply(singleMemberAnnotation.getName())
                + "("
                + compactAnnotationValue(singleMemberAnnotation.getMemberValue())
                + ")";
            default -> "@" + compact.apply(annotation.getName());
        };
    }

    /**
     * Renders annotation member values with annotation-only forks for arrays and binary expressions.
     *
     * <p>Array initializers first try the annotation compact form and break only when that text exceeds the current
     * width. Binary values delegate to the shared nested binary continuation so operator placement remains consistent
     * with the rest of the formatter.
     */
    private Doc annotationValue(Expression value) {
        return switch (value) {
            case ArrayInitializerExpr arrayInitializerExpr -> {
                if (annotationValueHasLineComments(arrayInitializerExpr)) {
                    yield annotationArrayInitializer(arrayInitializerExpr);
                }
                String flat = compactAnnotationArrayInitializer(arrayInitializerExpr);
                yield Doc.bestFittingFirstLine(
                    List.of(Doc.text(flat), annotationArrayInitializer(arrayInitializerExpr))
                );
            }
            case BinaryExpr binaryExpr -> nestedBinaryLines.apply(value, true);
            default -> rendering.render(value);
        };
    }

    private boolean annotationValueMustBreak(Expression value) {
        return value instanceof TextBlockLiteralExpr;
    }

    /**
     * Breaks an annotation array initializer with a trailing comma after every rendered value.
     *
     * <p>Annotation arrays use this simpler member-value shape instead of the general array initializer printer so
     * annotation-specific compact values, including raw string literal tokens, decide the flat form before the break.
     */
    private Doc annotationArrayInitializer(ArrayInitializerExpr expression) {
        if (expression.getValues().isEmpty()) {
            return Doc.text("{}");
        }
        List<Doc> lines = new ArrayList<>();
        addCommentDocs(lines, commentPlacement.lineCommentsBeforeFirst(expression, expression.getValues().get(0)));
        for (int index = 0; index < expression.getValues().size(); index++) {
            Expression value = expression.getValues().get(index);
            List<JavaCommentTrivia> trailingComments = index + 1 < expression.getValues().size()
                ? commentPlacement.lineCommentsBetween(expression, value, expression.getValues().get(index + 1))
                : commentPlacement.lineCommentsAfterLast(expression, value);
            lines.add(annotationArrayValueLine(value, trailingComments));
        }
        return Doc.concat(
            Doc.text("{"),
            Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.join(Doc.HARD_LINE, lines))),
            Doc.HARD_LINE,
            Doc.text("}")
        );
    }

    private Doc annotationArrayValueLine(Expression value, List<JavaCommentTrivia> trailingComments) {
        Doc valueLine = annotationArrayValueLine(value);
        boolean commaAppended = false;
        List<Doc> followingLines = new ArrayList<>();
        for (JavaCommentTrivia comment : trailingComments) {
            Doc commentDoc = comments.comment(comment);
            if (commentDoc == Doc.EMPTY) {
                continue;
            }
            if (comment.startsOnEndLine(value)) {
                if (!commaAppended) {
                    valueLine = Doc.concat(valueLine, Doc.text(","));
                    commaAppended = true;
                }
                valueLine = Doc.concat(valueLine, Doc.text(" "), commentDoc);
            } else {
                followingLines.add(commentDoc);
            }
        }
        if (!commaAppended) {
            valueLine = Doc.concat(valueLine, Doc.text(","));
        }
        followingLines.addFirst(valueLine);
        return Doc.join(Doc.HARD_LINE, followingLines);
    }

    private Doc annotationArrayValueLine(Expression value) {
        return switch (value) {
            case AnnotationExpr annotation when annotationArrayAnnotationLineOverflows(annotation) ->
                brokenAnnotationArrayValue(annotation);
            case BinaryExpr binaryExpr -> Doc.bestFittingFirstLine(
                List.of(Doc.text(compactAnnotationValue(value)), nestedBinaryLines.apply(binaryExpr, true))
            );
            default -> rendering.render(value);
        };
    }

    private boolean annotationArrayAnnotationLineOverflows(AnnotationExpr annotation) {
        return currentIndentedWidth.applyAsInt(
            options.indentUnit() + compactAnnotationValue(annotation) + ","
        ) > options.lineWidth();
    }

    private Doc brokenAnnotationArrayValue(AnnotationExpr annotation) {
        return switch (annotation) {
            case NormalAnnotationExpr normalAnnotation -> brokenNormalAnnotation(normalAnnotation);
            case SingleMemberAnnotationExpr singleMemberAnnotation -> brokenSingleMemberAnnotation(
                "@" + compact.apply(singleMemberAnnotation.getName()),
                annotationValue(singleMemberAnnotation.getMemberValue())
            );
            default -> rendering.render(annotation);
        };
    }

    private void addCommentDocs(List<Doc> lines, List<JavaCommentTrivia> sourceComments) {
        sourceComments.stream()
                .map(comments::comment)
                .filter(doc -> doc != Doc.EMPTY)
                .forEach(lines::add);
    }

    private String compactJoinAnnotationPairs(List<MemberValuePair> pairs) {
        return pairs.stream()
                .map(pair -> pair.getNameAsString() + " = " + compactAnnotationValue(pair.getValue()))
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
    }

    /**
     * Compacts one annotation value without normalizing string-literal token text.
     *
     * <p>String literals read their original token range directly so escapes and source spelling remain exactly as the
     * parser stored them. Array values recurse through the annotation compact path; all other values use the caller's
     * compact source text.
     */
    private String compactAnnotationValue(Expression value) {
        return switch (value) {
            case StringLiteralExpr stringLiteral ->
                value.getTokenRange().map(Object::toString).orElseGet(value::toString);
            case TextBlockLiteralExpr textBlockLiteral ->
                value.getTokenRange().map(Object::toString).orElseGet(value::toString);
            case ArrayInitializerExpr arrayInitializerExpr -> compactAnnotationArrayInitializer(arrayInitializerExpr);
            case AnnotationExpr annotationExpr -> annotationFlatText(annotationExpr);
            default -> compact.apply(value);
        };
    }

    private String compactAnnotationArrayInitializer(ArrayInitializerExpr expression) {
        String values = expression.getValues()
                .stream()
                .map(this::compactAnnotationValue)
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
        if (values.isEmpty()) {
            return "{}";
        }
        return "{ " + values + " }";
    }

    private boolean annotationValueHasLineComments(Expression value) {
        return commentPlacement.hasContainedLineComments(
            value
        ) || commentPlacement.leadingComment(value).filter(JavaCommentTrivia::isLine).isPresent();
    }

}
