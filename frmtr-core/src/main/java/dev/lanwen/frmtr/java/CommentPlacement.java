package dev.lanwen.frmtr.java;

import com.github.javaparser.Range;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.comments.BlockComment;
import com.github.javaparser.ast.comments.Comment;
import dev.lanwen.frmtr.doc.Doc;
import java.util.Optional;

/**
 * Answers source-range comment-placement questions for formatter helpers that already know what they are printing.
 *
 * <p>This helper owns the shared predicates that compare JavaParser node and comment ranges, plus the narrow recovery
 * path for block comments that JavaParser leaves unattached but source places on the same line as a node. The boundary
 * keeps layout printers focused on syntax-specific {@code Doc} assembly while still giving them one consistent source
 * position vocabulary for comment placement.
 *
 * <p>Callers still decide which comments belong to a leading, trailing, orphan, or syntax-specific path, how returned
 * comment docs are spaced, and whether a source-position fork should affect the surrounding layout.
 */
final class CommentPlacement {
    private final JavaFormatter.CommentTracker comments;

    CommentPlacement(JavaFormatter.CommentTracker comments) {
        this.comments = comments;
    }

    /**
     * Returns a block comment attached to {@code node} only when source placed it before the node on that same line.
     *
     * <p>JavaParser exposes this as the node's own comment, but layout callers need to know whether the comment belongs
     * inline before syntax such as {@code switch} or a statement expression rather than on its own leading line. The
     * range comparison keeps that decision tied to the original source position.
     */
    Doc ownSameLineBlockCommentBeforeNode(Node node) {
        return comments.ownComment(node, comment -> comment instanceof BlockComment
                && comment.getRange()
                        .flatMap(commentRange -> node.getRange()
                                .map(nodeRange -> commentRange.begin.line == nodeRange.begin.line
                                        && startsBefore(commentRange, nodeRange)))
                        .orElse(false));
    }

    boolean startsOnSameLine(Comment comment, Node node) {
        return comment.getRange()
                .flatMap(commentRange -> node.getRange().map(nodeRange -> commentRange.begin.line == nodeRange.begin.line))
                .orElse(false);
    }

    boolean startsBefore(Comment comment, Node node) {
        return comment.getRange()
                .flatMap(commentRange -> node.getRange().map(nodeRange -> startsBefore(commentRange, nodeRange)))
                .orElse(false);
    }

    boolean startsBefore(Range left, Range right) {
        if (left.begin.line != right.begin.line) {
            return left.begin.line < right.begin.line;
        }
        return left.begin.column < right.begin.column;
    }

    boolean startsAfterNodeOnSameLine(Node node, Comment comment) {
        return node.getRange()
                .flatMap(nodeRange -> comment.getRange()
                        .map(commentRange -> commentRange.begin.line == nodeRange.end.line
                                && commentRange.begin.column > nodeRange.end.column))
                .orElse(false);
    }

    /**
     * Finds an unattached block comment that source placed after {@code node} on the same line.
     *
     * <p>Some trailing block comments are not exposed as the node's own comment. Walking parents lets parameter and
     * signature callers recover the nearest contained unattached block comment without making those callers responsible
     * for JavaParser's comment attachment gaps.
     */
    Doc unattachedTrailingBlockComment(Node node) {
        Optional<Node> parent = node.getParentNode();
        while (parent.isPresent()) {
            Optional<Doc> trailing = parent.orElseThrow().getAllContainedComments().stream()
                    .filter(BlockComment.class::isInstance)
                    .filter(comment -> comment.getCommentedNode().isEmpty())
                    .filter(comment -> startsAfterNodeOnSameLine(node, comment))
                    .findFirst()
                    .map(comments::comment);
            if (trailing.isPresent()) {
                return trailing.orElseThrow();
            }
            parent = parent.orElseThrow().getParentNode();
        }
        return Doc.EMPTY;
    }
}
