package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.comments.BlockComment;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.comments.JavadocComment;
import com.github.javaparser.ast.comments.LineComment;
import java.util.Set;

/**
 * Wraps a JavaParser comment with formatter-owned trivia classification and source-position queries.
 *
 * <p>This helper owns reusable comment facts: parser kind, line-position lookups through {@link CommentIndex}, and the
 * identity-based claim checks used by {@link CommentTracker}. The boundary exists so printers can combine
 * comment facts without repeating raw JavaParser subclass, range, and printed-state decisions.
 *
 * <p>Callers still decide syntax-specific placement, surrounding whitespace, and whether a comment is leading, trailing,
 * orphan, or part of a construct-specific layout rule.
 */
record JavaCommentTrivia(Comment comment) {
    JavaCommentTrivia {
        if (comment == null) {
            throw new IllegalArgumentException("comment must not be null");
        }
    }

    /**
     * Classifies a JavaParser comment into the formatter's comment-kind vocabulary.
     */
    static JavaCommentTrivia from(Comment comment) {
        return new JavaCommentTrivia(comment);
    }

    JavaCommentKind kind() {
        return classify(comment);
    }

    private static JavaCommentKind classify(Comment comment) {
        if (comment instanceof LineComment) {
            return JavaCommentKind.LINE;
        }
        if (comment instanceof JavadocComment) {
            return JavaCommentKind.JAVADOC;
        }
        if (comment instanceof BlockComment) {
            return JavaCommentKind.BLOCK;
        }
        return JavaCommentKind.UNKNOWN;
    }

    /**
     * Reports whether this trivia is a {@code //} line comment.
     */
    boolean isLine() {
        return kind() == JavaCommentKind.LINE;
    }

    /**
     * Reports whether this trivia is a non-Javadoc {@code /* ... *&#47;} block comment.
     */
    boolean isBlock() {
        return kind() == JavaCommentKind.BLOCK;
    }

    /**
     * Reports whether this trivia is a Javadoc comment.
     */
    boolean isJavadoc() {
        return kind() == JavaCommentKind.JAVADOC;
    }

    /**
     * Returns the comment's source start line with the caller-selected fallback for range-less parser nodes.
     */
    int beginLine(int fallback) {
        return CommentIndex.beginLine(comment, fallback);
    }

    /**
     * Returns the comment's source end line with the caller-selected fallback for range-less parser nodes.
     */
    int endLine(int fallback) {
        return CommentIndex.endLine(comment, fallback);
    }

    /**
     * Reports whether the comment begins on the same source line where {@code node} ends.
     */
    boolean startsOnEndLine(Node node) {
        return CommentIndex.startsOnEndLine(node, comment);
    }

    /**
     * Reports whether the comment begins on the source line where {@code node} begins.
     */
    boolean startsOnBeginLine(Node node) {
        return CommentIndex.startsOnBeginLine(comment, node);
    }

    /**
     * Reports whether the comment begins on the same source line where {@code node} begins.
     */
    boolean startsOnSameLine(Node node) {
        return CommentIndex.startsOnSameLine(comment, node);
    }

    /**
     * Reports whether the comment begins before the source line where {@code node} begins.
     */
    boolean startsBeforeBeginLine(Node node) {
        return CommentIndex.startsBeforeBeginLine(comment, node);
    }

    /**
     * Reports whether the comment begins inside {@code node}'s source line range.
     */
    boolean startsInsideLineRange(Node node) {
        return CommentIndex.startsInsideLineRange(comment, node);
    }

    /**
     * Reports whether the comment begins before {@code node} in source order.
     */
    boolean startsBefore(Node node) {
        return CommentIndex.startsBefore(comment, node);
    }

    /**
     * Reports whether the comment begins later on the same source line where {@code node} ends.
     */
    boolean startsAfterNodeOnSameLine(Node node) {
        return CommentIndex.startsAfterNodeOnSameLine(node, comment);
    }

    /**
     * Reports whether the comment begins immediately after {@code node} on the same source line.
     */
    boolean startsImmediatelyAfterNodeOnSameLine(Node node) {
        return CommentIndex.startsImmediatelyAfterNodeOnSameLine(node, comment);
    }

    /**
     * Reports whether the comment is owned by {@code child} rather than merely trailing it on a shared line.
     *
     * <p>The line-only {@link #startsInsideLineRange(Node)} test alone treats any comment sharing a child's line as that
     * child's. When whitespace is collapsed, a trailing comment can land on the same line as an earlier sibling (e.g.
     * {@code alpha(), beta() // after last arg}), so the coarse test would hand the trailing comment to {@code alpha()}.
     * A comment that begins after the child's last token is trailing it, not inside it, so it is not the child's to claim.
     */
    boolean isInsideNotTrailing(Node child) {
        return startsInsideLineRange(child) && !startsAfterNodeOnSameLine(child);
    }

    /**
     * Reports whether the supplied identity set has already claimed this exact JavaParser comment instance.
     */
    boolean isClaimedBy(Set<Comment> claimedComments) {
        return claimedComments.contains(comment);
    }

    /**
     * Claims this exact JavaParser comment instance in the supplied identity set.
     *
     * <p>{@link CommentTracker} uses this to preserve its "print once" behavior while allowing callers to
     * work with classified trivia instead of raw comments.
     */
    boolean claim(Set<Comment> claimedComments) {
        return claimedComments.add(comment);
    }
}
