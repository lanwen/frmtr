package dev.lanwen.frmtr.java;

import com.github.javaparser.Position;
import com.github.javaparser.Range;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.comments.Comment;
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
 * surrounding layout, and when a comment is rendered or marked as consumed by {@link CommentTracker}.
 */
final class CommentIndex {

    private static final Comparator<Comment> SOURCE_ORDER = Comparator.comparing(CommentIndex::beginPosition);

    private CommentIndex() {}

    /**
     * Reports whether JavaParser found any contained line comments under {@code node}.
     */
    static boolean hasContainedLineComments(Node node) {
        return node.getAllContainedComments()
                .stream()
                .map(JavaCommentTrivia::from)
                .anyMatch(JavaCommentTrivia::isLine);
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
        return container.getAllContainedComments()
                .stream()
                .map(JavaCommentTrivia::from)
                .filter(JavaCommentTrivia::isLine)
                .filter(comment -> comment.comment()
                            .getRange()
                            .map(range -> range.begin.line >= previousLine && range.begin.line < nextLine)
                            .orElse(false)
                )
                .map(JavaCommentTrivia::comment)
                .sorted(sourceOrderComparator())
                .toList();
    }

    /**
     * Returns the source start line for a node, using the caller's explicit fallback when JavaParser has no range.
     *
     * <p>Callers choose the fallback because missing ranges can mean "sort after source-backed content" for begin-line
     * ordering, but "do not extend a source-backed maximum" for end-line aggregation.
     */
    static int beginLine(Node node, int fallback) {
        return node.getRange().map(range -> range.begin.line).orElse(fallback);
    }

    /**
     * Returns the source end line for a node, using the caller's explicit fallback when JavaParser has no range.
     *
     * <p>The fallback is intentionally visible at call sites so sequencing rules keep their missing-range policy local
     * to the caller.
     */
    static int endLine(Node node, int fallback) {
        return node.getRange().map(range -> range.end.line).orElse(fallback);
    }

    /**
     * Returns the source start line for a comment, using the caller's explicit fallback when JavaParser has no range.
     *
     * <p>Comment sequencing uses this to keep synthetic or incomplete comments in the caller-selected source-order slot.
     */
    static int beginLine(Comment comment, int fallback) {
        return comment.getRange().map(range -> range.begin.line).orElse(fallback);
    }

    /**
     * Returns the source end line for a comment, using the caller's explicit fallback when JavaParser has no range.
     *
     * <p>The fallback remains caller-owned because end-line gaps can either preserve previous source state or move
     * range-less comments after source-backed content depending on the surrounding sequence.
     */
    static int endLine(Comment comment, int fallback) {
        return comment.getRange().map(range -> range.end.line).orElse(fallback);
    }

    /**
     * Orders comments by the source position where JavaParser says each comment begins.
     *
     * <p>Comments without ranges fall back to {@link Position#HOME}, matching the formatter's existing local ordering
     * logic for incomplete or synthetic parser nodes.
     */
    static Comparator<Comment> sourceOrderComparator() {
        return SOURCE_ORDER;
    }

    /**
     * Selects comments that begin on the same line where {@code node} ends.
     *
     * <p>For broken binary expressions with end-position operators, this preserves source comments that trailed an operand
     * on that operand's printed line while leaving later gap comments to become standalone continuation lines.
     */
    static List<Comment> commentsStartingOnEndLine(Node node, List<Comment> comments) {
        return comments.stream().filter(comment -> startsOnEndLine(node, comment)).toList();
    }

    /**
     * Reports whether {@code comment} begins on the same source line where {@code node} ends.
     */
    static boolean startsOnEndLine(Node node, Comment comment) {
        return node.getRange()
                .flatMap(nodeRange -> comment.getRange().map(
                        commentRange -> commentRange.begin.line == nodeRange.end.line
                ))
                .orElse(false);
    }

    /**
     * Reports whether {@code comment} begins inside the source line range covered by {@code node}.
     *
     * <p>This intentionally ignores columns because callers use it for coarse containment when JavaParser left a
     * comment attached to an ancestor even though its starting line still belongs to a child node.
     */
    static boolean startsInsideLineRange(Comment comment, Node node) {
        return comment.getRange()
                .flatMap(commentRange -> node.getRange().map(
                        nodeRange -> commentRange.begin.line >= nodeRange.begin.line
                                && commentRange.begin.line <= nodeRange.end.line
                ))
                .orElse(false);
    }

    /**
     * Reports whether two nodes begin on the same source line.
     *
     * <p>The comparison is line-only so callers can preserve inline source shapes without making column distance part
     * of the ownership decision.
     */
    static boolean sameBeginLine(Node left, Node right) {
        return left.getRange()
                .flatMap(leftRange -> right.getRange().map(
                        rightRange -> leftRange.begin.line == rightRange.begin.line
                ))
                .orElse(false);
    }

    /**
     * Reports whether {@code comment} begins on the same source line where {@code node} begins.
     */
    static boolean startsOnSameLine(Comment comment, Node node) {
        return startsOnBeginLine(comment, node);
    }

    /**
     * Reports whether {@code comment} begins on the source line where {@code node} begins.
     *
     * <p>This is intentionally a line-only predicate: callers use it when same-line attachment is significant but the
     * comment's column must not affect ownership or layout decisions.
     */
    static boolean startsOnBeginLine(Comment comment, Node node) {
        return comment.getRange()
                .flatMap(
                    commentRange -> node.getRange().map(nodeRange -> commentRange.begin.line == nodeRange.begin.line)
                )
                .orElse(false);
    }

    /**
     * Reports whether {@code comment} begins on a source line before the line where {@code node} begins.
     *
     * <p>This intentionally ignores columns, preserving callers that classify own-line comments only by line position.
     */
    static boolean startsBeforeBeginLine(Comment comment, Node node) {
        return comment.getRange()
                .flatMap(
                    commentRange -> node.getRange().map(nodeRange -> commentRange.begin.line < nodeRange.begin.line)
                )
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
     * Reports whether {@code comment} begins before the source end of {@code node}.
     *
     * <p>Callers use this when a same-line comment may either belong inside an unterminated syntax gap, such as
     * {@code call( // note )}, or trail the completed node, such as {@code call() // note}. Line comparison keeps comments
     * on earlier lines inside multiline nodes, while same-line comments must begin no later than the node end column.
     */
    static boolean startsBeforeEnd(Comment comment, Node node) {
        return comment.getRange()
                .flatMap(commentRange -> node.getRange().map(nodeRange -> {
                        if (commentRange.begin.line != nodeRange.end.line) {
                            return commentRange.begin.line < nodeRange.end.line;
                        }
                        return commentRange.begin.column <= nodeRange.end.column;
                }))
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
                .flatMap(nodeRange -> comment.getRange().map(
                        commentRange -> commentRange.begin.line == nodeRange.end.line
                                && commentRange.begin.column > nodeRange.end.column
                ))
                .orElse(false);
    }

    /**
     * Reports whether {@code comment} begins immediately after {@code node} with no horizontal source gap.
     */
    static boolean startsImmediatelyAfterNodeOnSameLine(Node node, Comment comment) {
        return node.getRange()
                .flatMap(nodeRange -> comment.getRange().map(
                        commentRange -> commentRange.begin.line == nodeRange.end.line
                                && commentRange.begin.column == nodeRange.end.column + 1
                ))
                .orElse(false);
    }

    private static Position beginPosition(Comment comment) {
        return comment.getRange().map(range -> range.begin).orElse(Position.HOME);
    }
}
