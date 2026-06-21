package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Renders parenthesized expression lists that contain line comments between elements.
 *
 * <p>This helper owns the reusable argument-list shape for calls and constructors when JavaParser exposes comments as
 * source trivia around arguments rather than as ordinary expression content. The boundary exists so method-call and
 * object-creation printers can keep their syntax-specific branching while comma placement and source-position comment
 * gaps stay in one implementation.
 *
 * <p>Callers still decide when specialized lambda, text-block, binary, or chain layouts should run before this fallback,
 * and they provide the prefix text plus the expression renderer for individual arguments.
 */
final class CommentedExpressionListPrinter {

    private final CommentTracker comments;

    private final JavaCommentPlacementPolicy commentPlacement;

    private final CompactSourceText compactSource;

    private final Function<Expression, Doc> expressionRenderer;

    CommentedExpressionListPrinter(JavaFormatContext context, Function<Expression, Doc> expressionRenderer) {
        this.comments = context.comments;
        this.commentPlacement = context.commentPlacementPolicy;
        this.compactSource = context.compactSource;
        this.expressionRenderer = expressionRenderer;
    }

    /**
     * Returns a broken parenthesized list only when unclaimed line comments sit directly in its argument gaps.
     */
    Optional<Doc> parenthesized(String prefix, Node container, NodeList<Expression> arguments) {
        if (arguments.isEmpty()) {
            return Optional.empty();
        }
        List<List<JavaCommentTrivia>> commentGaps = argumentCommentGaps(container, arguments);
        if (!hasUnprintedComments(commentGaps)) {
            return Optional.empty();
        }

        List<Doc> lines = new ArrayList<>();
        addCommentDocs(lines, commentGaps.getFirst());
        for (int index = 0; index < arguments.size(); index++) {
            Expression argument = arguments.get(index);
            boolean hasNext = index + 1 < arguments.size();
            boolean commaAppended = false;
            List<JavaCommentTrivia> trailingComments = commentGaps.get(index + 1);
            Doc argumentLine = argumentLine(argument, trailingComments);
            List<Doc> trailingCommentLines = new ArrayList<>();
            for (JavaCommentTrivia comment : trailingComments) {
                Doc commentDoc = comments.comment(comment);
                if (commentDoc == Doc.EMPTY) {
                    continue;
                }
                if (
                    comment.startsOnEndLine(argument)
                    || comment.startsAfterNodeOnSameLine(argument)
                    || argumentContainsComment(argument, comment)
                ) {
                    if (hasNext && !commaAppended) {
                        argumentLine = Doc.concat(argumentLine, Doc.text(","));
                        commaAppended = true;
                    }
                    argumentLine = Doc.concat(argumentLine, Doc.text(" "), commentDoc);
                } else {
                    trailingCommentLines.add(commentDoc);
                }
            }
            if (hasNext && !commaAppended) {
                argumentLine = Doc.concat(argumentLine, Doc.text(","));
            }
            lines.add(argumentLine);
            lines.addAll(trailingCommentLines);
        }

        if (lines.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
            Doc.concat(
                Doc.text(prefix + "("),
                Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.join(Doc.HARD_LINE, lines))),
                Doc.HARD_LINE,
                Doc.text(")")
            )
        );
    }

    private Doc argumentLine(Expression argument, List<JavaCommentTrivia> trailingComments) {
        if (argument instanceof MethodCallExpr methodCall && hasInlineTrailingComment(argument, trailingComments)) {
            if (hasContainedCommentOutsideTrailingComments(argument, trailingComments)) {
                return expressionRenderer.apply(argument);
            }
            return Doc.text(compactSource.commentFree(methodCall));
        }
        return expressionRenderer.apply(argument);
    }

    private boolean hasContainedCommentOutsideTrailingComments(
            Expression argument,
            List<JavaCommentTrivia> trailingComments
    ) {
        return argument.getAllContainedComments()
                .stream()
                .anyMatch(contained -> trailingComments.stream().noneMatch(comment -> comment.comment() == contained));
    }

    private boolean hasInlineTrailingComment(Expression argument, List<JavaCommentTrivia> trailingComments) {
        return trailingComments.stream()
                .anyMatch(comment -> comment.startsOnEndLine(argument)
                        || comment.startsAfterNodeOnSameLine(argument)
                        || argumentContainsComment(argument, comment)
                );
    }

    private boolean argumentContainsComment(Expression argument, JavaCommentTrivia comment) {
        return argument.getAllContainedComments()
                .stream()
                .anyMatch(contained -> contained == comment.comment());
    }

    /**
     * Reports whether a parenthesized expression list has unclaimed line comments in one of its argument gaps.
     */
    boolean hasUnprintedLineComments(Node container, NodeList<Expression> arguments) {
        return !arguments.isEmpty() && hasUnprintedComments(argumentCommentGaps(container, arguments));
    }

    /**
     * Reports source-line comments in argument gaps without consulting printed-comment state.
     */
    boolean hasLineComments(Node container, NodeList<Expression> arguments) {
        return !arguments.isEmpty()
            && argumentCommentGaps(container, arguments).stream().flatMap(List::stream).findAny().isPresent();
    }

    private List<List<JavaCommentTrivia>> argumentCommentGaps(Node container, NodeList<Expression> arguments) {
        List<List<JavaCommentTrivia>> gaps = new ArrayList<>();
        gaps.add(
            commentPlacement.lineCommentsBeforeFirst(container, arguments.get(0))
                    .stream()
                    .filter(comment -> !comment.startsBeforeBeginLine(argumentListAnchor(container)))
                    .toList()
        );
        for (int index = 0; index < arguments.size(); index++) {
            Expression argument = arguments.get(index);
            if (index + 1 < arguments.size()) {
                gaps.add(commentPlacement.lineCommentsBetween(container, argument, arguments.get(index + 1)));
            } else {
                gaps.add(trailingArgumentComments(container, argument));
            }
        }
        return gaps;
    }

    /**
     * Keeps comments after the completed call or constructor out of the final argument gap.
     *
     * <p>A line comment in {@code call(arg) // note} belongs to the enclosing syntax, not to {@code arg}; chain and
     * statement printers need to keep that comment at the completed-call boundary.
     */
    private List<JavaCommentTrivia> trailingArgumentComments(Node container, Expression argument) {
        List<JavaCommentTrivia> sourceComments = new ArrayList<>(
            commentPlacement.lineCommentsAfterLast(
                container,
                argument
            )
        );
        commentPlacement.trailingLineComment(argument)
                .filter(
                    comment -> sourceComments.stream().noneMatch(existing -> existing.comment() == comment.comment())
                )
                .ifPresent(sourceComments::add);
        commentPlacement.containedComments(argument)
                .stream()
                .filter(JavaCommentTrivia::isLine)
                .filter(comment -> comment.startsOnEndLine(argument) || comment.startsAfterNodeOnSameLine(argument))
                .filter(
                    comment -> sourceComments.stream().noneMatch(existing -> existing.comment() == comment.comment())
                )
                .forEach(sourceComments::add);
        int argumentEndLine = CommentIndex.endLine(argument, Integer.MIN_VALUE);
        argument.getAllContainedComments()
                .stream()
                .filter(LineComment.class::isInstance)
                .map(JavaCommentTrivia::from)
                .filter(comment -> comment.beginLine(Integer.MAX_VALUE) == argumentEndLine)
                .filter(
                    comment -> sourceComments.stream().noneMatch(existing -> existing.comment() == comment.comment())
                )
                .forEach(sourceComments::add);
        return sourceComments
                .stream()
                .filter(comment -> !startsInsideOtherDirectChild(container, argument, comment))
                .filter(comment -> !comment.startsAfterNodeOnSameLine(container))
                .toList();
    }

    private boolean startsInsideOtherDirectChild(Node container, Expression argument, JavaCommentTrivia comment) {
        return container.getChildNodes()
                .stream()
                .filter(child -> !(child instanceof Comment))
                .filter(child -> child != argument)
                .anyMatch(child -> commentBelongsToChild(comment, child));
    }

    /**
     * Reports whether {@code comment} is owned by {@code child} rather than merely trailing it on a shared line.
     *
     * <p>The line-only {@link JavaCommentTrivia#startsInsideLineRange} test alone treats any comment sharing a child's
     * line as that child's. When whitespace is collapsed, the last argument's trailing comment lands on the same line as
     * earlier arguments (e.g. {@code alpha(), beta() // after last arg}), so that coarse test would hand the trailing
     * comment to {@code alpha()} and then drop it (no printer emits it there). A comment that begins after the child's
     * last token is trailing it, not inside it, so it is not the child's to claim.
     */
    private boolean commentBelongsToChild(JavaCommentTrivia comment, Node child) {
        return comment.startsInsideLineRange(child)
                && !CommentIndex.startsAfterNodeOnSameLine(child, comment.comment());
    }

    private Node argumentListAnchor(Node container) {
        return container instanceof MethodCallExpr methodCall ? methodCall.getName() : container;
    }

    private boolean hasUnprintedComments(List<List<JavaCommentTrivia>> commentGaps) {
        return commentGaps.stream().flatMap(List::stream).anyMatch(comment -> !comments.isPrinted(comment));
    }

    private void addCommentDocs(List<Doc> lines, List<JavaCommentTrivia> sourceComments) {
        sourceComments.stream()
                .map(comments::comment)
                .filter(doc -> doc != Doc.EMPTY)
                .forEach(lines::add);
    }
}
