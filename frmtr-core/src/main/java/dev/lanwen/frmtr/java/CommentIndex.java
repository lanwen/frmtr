package dev.lanwen.frmtr.java;

import com.github.javaparser.Position;
import com.github.javaparser.Range;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.comments.LineComment;
import java.util.Comparator;
import java.util.List;

/**
 * Indexes JavaParser comment ranges for formatter helpers that need source-position decisions before rendering.
 *
 * <p>This helper owns read-only source positioning only: line and column comparisons, contained line-comment selection,
 * source-order sorting, and comments that sit between neighboring nodes. The boundary exists because JavaParser exposes
 * comments through own, orphan, and contained associations that do not always match the token relationship formatter
 * callers are trying to preserve.
 *
 * <p>Callers still decide whether a comment is leading, trailing, orphan, or syntax-specific, how it is spaced in the
 * surrounding layout, and when a comment is rendered or marked as consumed by {@link JavaFormatter.CommentTracker}.
 */
final class CommentIndex {
    private CommentIndex() {}

    /**
     * Reports whether JavaParser found any contained line comments under {@code node}.
     */
    static boolean hasContainedLineComments(Node node) {
        return node.getAllContainedComments().stream().anyMatch(LineComment.class::isInstance);
    }

    /**
     * Finds line comments that sit between two neighboring nodes in source order.
     *
     * <p>JavaParser attaches comments to nearby nodes instead of to operator or separator tokens. Callers use this range
     * query when their syntax tree already knows two adjacent operands or elements and needs the comments whose source
     * positions belong to the gap between them.
     */
    static List<Comment> lineCommentsBetween(Node container, Node previous, Node next) {
        int previousLine = previous.getRange().map(range -> range.end.line).orElse(Integer.MIN_VALUE);
        int nextLine = next.getRange().map(range -> range.begin.line).orElse(Integer.MAX_VALUE);
        return container.getAllContainedComments().stream()
                .filter(LineComment.class::isInstance)
                .filter(comment -> comment.getRange()
                        .map(range -> range.begin.line >= previousLine && range.begin.line < nextLine)
                        .orElse(false))
                .sorted(Comparator.comparing(comment -> comment.getRange()
                        .map(range -> range.begin)
                        .orElse(Position.HOME)))
                .toList();
    }

    /**
     * Selects comments that begin on the same line where {@code node} ends.
     *
     * <p>For broken binary expressions with end-position operators, this preserves source comments that trailed an operand
     * on that operand's printed line while leaving later gap comments to become standalone continuation lines.
     */
    static List<Comment> commentsStartingOnEndLine(Node node, List<Comment> comments) {
        int nodeEndLine = node.getRange().map(range -> range.end.line).orElse(Integer.MIN_VALUE);
        return comments.stream()
                .filter(comment -> comment.getRange()
                        .map(range -> range.begin.line == nodeEndLine)
                        .orElse(false))
                .toList();
    }

    /**
     * Reports whether {@code comment} begins on the same source line where {@code node} begins.
     */
    static boolean startsOnSameLine(Comment comment, Node node) {
        return comment.getRange()
                .flatMap(commentRange -> node.getRange().map(nodeRange -> commentRange.begin.line == nodeRange.begin.line))
                .orElse(false);
    }

    /**
     * Reports whether {@code comment} begins before {@code node} in source order.
     */
    static boolean startsBefore(Comment comment, Node node) {
        return comment.getRange()
                .flatMap(commentRange -> node.getRange().map(nodeRange -> startsBefore(commentRange, nodeRange)))
                .orElse(false);
    }

    /**
     * Compares two JavaParser ranges by their starting source position.
     */
    static boolean startsBefore(Range left, Range right) {
        if (left.begin.line != right.begin.line) {
            return left.begin.line < right.begin.line;
        }
        return left.begin.column < right.begin.column;
    }

    /**
     * Reports whether {@code comment} begins later on the same source line where {@code node} ends.
     */
    static boolean startsAfterNodeOnSameLine(Node node, Comment comment) {
        return node.getRange()
                .flatMap(nodeRange -> comment.getRange()
                        .map(commentRange -> commentRange.begin.line == nodeRange.end.line
                                && commentRange.begin.column > nodeRange.end.column))
                .orElse(false);
    }

    /**
     * Reports whether {@code comment} begins immediately after {@code node} with no horizontal source gap.
     */
    static boolean startsImmediatelyAfterNodeOnSameLine(Node node, Comment comment) {
        return node.getRange()
                .flatMap(nodeRange -> comment.getRange()
                        .map(commentRange -> commentRange.begin.line == nodeRange.end.line
                                && commentRange.begin.column == nodeRange.end.column + 1))
                .orElse(false);
    }
}
