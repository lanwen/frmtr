package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import dev.lanwen.frmtr.doc.Doc;

/**
 * Recovers source-position-sensitive comment docs for formatter helpers that already know what they are printing.
 *
 * <p>This helper owns the narrow rendered-comment paths that depend on source positions: attached block comments that
 * source placed before a node on the same line, and unattached trailing block comments that JavaParser leaves inside a
 * parent. It uses {@link JavaCommentPlacementPolicy} for comment placement queries so caller printers share one
 * source-position vocabulary.
 *
 * <p>Callers still decide which comments belong to a leading, trailing, orphan, or syntax-specific path, how returned
 * comment docs are spaced, and whether a source-position fork should affect the surrounding layout.
 */
final class CommentPlacement {

    private final CommentTracker comments;

    private final JavaCommentPlacementPolicy commentPlacement;

    CommentPlacement(CommentTracker comments, JavaCommentPlacementPolicy commentPlacement) {
        this.comments = comments;
        this.commentPlacement = commentPlacement;
    }

    /**
     * Returns a block comment attached to {@code node} only when source placed it before the node on that same line.
     *
     * <p>JavaParser exposes this as the node's own comment, but layout callers need to know whether the comment belongs
     * inline before syntax such as {@code switch} or a statement expression rather than on its own leading line. The
     * range comparison keeps that decision tied to the original source position.
     */
    Doc ownSameLineBlockCommentBeforeNode(Node node) {
        return commentPlacement.ownSameLineBlockCommentBeforeNode(node)
                .map(comments::comment)
                .orElse(Doc.EMPTY);
    }

    /**
     * Finds an unattached block comment that source placed after {@code node} on the same line.
     *
     * <p>Some trailing block comments are not exposed as the node's own comment. Walking parents lets parameter and
     * signature callers recover the nearest contained unattached block comment without making those callers responsible
     * for JavaParser's comment attachment gaps.
     */
    Doc unattachedTrailingBlockComment(Node node) {
        return commentPlacement.unattachedTrailingBlockComment(node).map(comments::comment).orElse(Doc.EMPTY);
    }
}
