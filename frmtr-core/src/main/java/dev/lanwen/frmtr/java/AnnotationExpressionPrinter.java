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

    private final JavaFormatRule<Expression> expressionRenderer;

    private final BiFunction<Expression, Boolean, Doc> nestedBinaryLines;

    private final Function<Node, String> compact;

    private final ToIntFunction<String> currentIndentedWidth;

    AnnotationExpressionPrinter(
            CommentTracker comments,
            JavaCommentPlacementPolicy commentPlacement,
            FormatterOptions options,
            JavaFormatRule<Expression> expressionRenderer,
            BiFunction<Expression, Boolean, Doc> nestedBinaryLines,
            Function<Node, String> compact,
            ToIntFunction<String> currentIndentedWidth
    ) {
        this.comments = comments;
        this.commentPlacement = commentPlacement;
        this.options = options;
        this.expressionRenderer = expressionRenderer;
        this.nestedBinaryLines = nestedBinaryLines;
        this.compact = compact;
        this.currentIndentedWidth = currentIndentedWidth;
    }

    /**
     * Chooses the annotation-expression branch while preserving comments attached to the annotation node itself.
     *
     * <p>Normal annotations and single-member annotations have different width and breaking rules, while marker
     * annotations are only the compact name. A trailing line comment belongs after the complete annotation text, so it is
     * attached after the branch-specific doc has been built.
     */
    Doc annotation(AnnotationExpr annotation) {
        Doc formatted;
        if (annotation instanceof NormalAnnotationExpr normalAnnotation) {
            formatted = normalAnnotation(normalAnnotation);
        } else if (annotation instanceof SingleMemberAnnotationExpr singleMemberAnnotation) {
            formatted = singleMemberAnnotation(singleMemberAnnotation);
        } else {
            formatted = Doc.text("@" + compact.apply(annotation.getName()));
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

    Doc annotationPreservingSourceBreaks(AnnotationExpr annotation) {
        if (!sourceMultilineAnnotation(annotation)) {
            return annotation(annotation);
        }
        if (annotation instanceof NormalAnnotationExpr normalAnnotation) {
            return brokenNormalAnnotation(normalAnnotation);
        }
        if (annotation instanceof SingleMemberAnnotationExpr singleMemberAnnotation) {
            return brokenSingleMemberAnnotation(
                "@" + compact.apply(singleMemberAnnotation.getName()),
                annotationValue(singleMemberAnnotation.getMemberValue())
            );
        }
        return annotation(annotation);
    }

    /**
     * Renders {@code @Name(...)} member pairs, keeping empty normal-annotation parentheses explicit.
     *
     * <p>An empty normal annotation remains {@code @Name()} instead of collapsing to a marker annotation because the
     * source chose the normal-annotation form. Non-empty pairs first try one compact line; when the full annotation no
     * longer fits, each pair breaks onto its own indented line.
     */
    private Doc normalAnnotation(NormalAnnotationExpr annotation) {
        String prefix = "@" + compact.apply(annotation.getName());
        if (annotation.getPairs().isEmpty()) {
            return Doc.text(prefix + "()");
        }
        if (
            !annotation.getPairs()
                    .stream()
                    .map(MemberValuePair::getValue)
                    .anyMatch(value -> annotationValueHasLineComments(value) || annotationValueMustBreak(value))
        ) {
            String flat = prefix + "(" + compactJoinAnnotationPairs(annotation.getPairs()) + ")";
            if (currentIndentedWidth.applyAsInt(flat) <= options.lineWidth()) {
                return Doc.text(flat);
            }
        }
        return brokenNormalAnnotation(annotation);
    }

    private Doc brokenNormalAnnotation(NormalAnnotationExpr annotation) {
        String prefix = "@" + compact.apply(annotation.getName());
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
     * Renders {@code @Name(value)} while keeping array-value overflow decisions scoped to the enclosing annotation.
     *
     * <p>Array values that are too wide by themselves keep the legacy {@code @Name({ ... })} broken-array shape. When
     * the array value still fits alone but {@code @Name(...)} does not, the value moves to an indented continuation so
     * the annotation prefix and suffix cannot make the final line overflow. Binary values use the same indented slot
     * because their operator lines otherwise collide visually with the annotation prefix.
     */
    private Doc singleMemberAnnotation(SingleMemberAnnotationExpr annotation) {
        String prefix = "@" + compact.apply(annotation.getName());
        Expression memberValue = annotation.getMemberValue();
        if (annotationValueHasLineComments(memberValue) || annotationValueMustBreak(memberValue)) {
            return brokenSingleMemberAnnotation(prefix, annotationValue(memberValue));
        }
        String flatValue = compactAnnotationValue(memberValue);
        String flat = prefix + "(" + flatValue + ")";
        if (currentIndentedWidth.applyAsInt(flat) <= options.lineWidth()) {
            return Doc.text(flat);
        }
        if (
            memberValue instanceof ArrayInitializerExpr
            && currentIndentedWidth.applyAsInt(flatValue) <= options.lineWidth()
        ) {
            return brokenSingleMemberAnnotation(prefix, Doc.text(flatValue));
        }
        if (!(memberValue instanceof BinaryExpr)) {
            return Doc.concat(Doc.text(prefix + "("), annotationValue(memberValue), Doc.text(")"));
        }
        return brokenSingleMemberAnnotation(prefix, annotationValue(memberValue));
    }

    private Doc brokenSingleMemberAnnotation(String prefix, Doc value) {
        return Doc.concat(
            Doc.text(prefix + "("),
            Doc.indent(Doc.concat(Doc.HARD_LINE, value)),
            Doc.HARD_LINE,
            Doc.text(")")
        );
    }

    private Doc annotationPair(MemberValuePair pair) {
        if (
            pair.getValue() instanceof ArrayInitializerExpr arrayInitializerExpr
            && currentIndentedWidth.applyAsInt(
                pair.getNameAsString()
                    + " = "
                    + compactAnnotationArrayInitializer(arrayInitializerExpr)
            ) > options.lineWidth()
        ) {
            return Doc.concat(
                Doc.text(pair.getNameAsString() + " = "),
                annotationArrayInitializer(arrayInitializerExpr)
            );
        }
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
        if (annotation instanceof NormalAnnotationExpr normalAnnotation) {
            return "@"
                + compact.apply(normalAnnotation.getName())
                + "("
                + compactJoinAnnotationPairs(normalAnnotation.getPairs())
                + ")";
        }
        if (annotation instanceof SingleMemberAnnotationExpr singleMemberAnnotation) {
            return "@"
                + compact.apply(singleMemberAnnotation.getName())
                + "("
                + compactAnnotationValue(singleMemberAnnotation.getMemberValue())
                + ")";
        }
        return "@" + compact.apply(annotation.getName());
    }

    /**
     * Renders annotation member values with annotation-only forks for arrays and binary expressions.
     *
     * <p>Array initializers first try the annotation compact form and break only when that text exceeds the current
     * width. Binary values delegate to the shared nested binary continuation so operator placement remains consistent
     * with the rest of the formatter.
     */
    private Doc annotationValue(Expression value) {
        if (value instanceof ArrayInitializerExpr arrayInitializerExpr) {
            if (annotationValueHasLineComments(arrayInitializerExpr)) {
                return annotationArrayInitializer(arrayInitializerExpr);
            }
            String flat = compactAnnotationArrayInitializer(arrayInitializerExpr);
            if (currentIndentedWidth.applyAsInt(flat) <= options.lineWidth()) {
                return Doc.text(flat);
            }
            return annotationArrayInitializer(arrayInitializerExpr);
        }
        if (value instanceof BinaryExpr) {
            return nestedBinaryLines.apply(value, true);
        }
        return expressionRenderer.format(value);
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
        if (value instanceof AnnotationExpr annotation && annotationArrayAnnotationLineOverflows(annotation)) {
            return brokenAnnotationArrayValue(annotation);
        }
        if (
            value instanceof BinaryExpr binaryExpr
            && (sourceSpansMultipleLines(value)
                || currentIndentedWidth.applyAsInt(compactAnnotationValue(value)) > options.lineWidth())
        ) {
            return nestedBinaryLines.apply(binaryExpr, true);
        }
        return expressionRenderer.format(value);
    }

    private boolean annotationArrayAnnotationLineOverflows(AnnotationExpr annotation) {
        return currentIndentedWidth.applyAsInt(
            options.indentUnit() + compactAnnotationValue(annotation) + ","
        ) > options.lineWidth();
    }

    private Doc brokenAnnotationArrayValue(AnnotationExpr annotation) {
        if (annotation instanceof NormalAnnotationExpr normalAnnotation) {
            return brokenNormalAnnotation(normalAnnotation);
        }
        if (annotation instanceof SingleMemberAnnotationExpr singleMemberAnnotation) {
            return brokenSingleMemberAnnotation(
                "@" + compact.apply(singleMemberAnnotation.getName()),
                annotationValue(singleMemberAnnotation.getMemberValue())
            );
        }
        return expressionRenderer.format(annotation);
    }

    private boolean sourceSpansMultipleLines(Node node) {
        return node.getRange()
                .map(range -> range.begin.line < range.end.line)
                .orElse(false);
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
        if (value instanceof StringLiteralExpr || value instanceof TextBlockLiteralExpr) {
            return value.getTokenRange().map(Object::toString).orElseGet(value::toString);
        }
        if (value instanceof ArrayInitializerExpr arrayInitializerExpr) {
            return compactAnnotationArrayInitializer(arrayInitializerExpr);
        }
        if (value instanceof AnnotationExpr annotationExpr) {
            return annotationFlatText(annotationExpr);
        }
        return compact.apply(value);
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

    private boolean sourceMultilineAnnotation(AnnotationExpr annotation) {
        return annotation.getRange()
                .map(range -> range.begin.line < range.end.line)
                .orElse(false);
    }
}
