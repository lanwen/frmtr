package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.comments.Comment;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Hosts opt-in formatter pipeline checks for mistakes that should be noisy during formatter development.
 *
 * <p>This helper owns debug-only invariant activation, shared failure messages, and the small bits of bookkeeping needed
 * to turn silent formatter accounting misses into actionable failures. The boundary exists so printers and trackers can
 * keep their normal output behavior while still having one place for development guardrails.
 *
 * <p>Callers still decide which pipeline events are meaningful enough to guard, what recovery or fallback behavior is
 * appropriate when guardrails are disabled, and how rendered output should be assembled.
 */
final class FormatterGuardrails {
    static final String ENABLED_PROPERTY = "dev.lanwen.frmtr.debug.guardrails";
    private static final int COMMENT_SNIPPET_LENGTH = 80;

    private FormatterGuardrails() {}

    /**
     * Records that a comment has been claimed for rendering and rejects duplicate claims when debug guardrails are on.
     *
     * <p>Normal formatter runs keep the existing best-effort behavior: a duplicate claim simply returns {@code false} so
     * callers can skip rendering a second copy. Enabling {@value #ENABLED_PROPERTY} makes the same duplicate claim fail
     * fast, which exposes comment-accounting bugs without changing default formatting output.
     */
    static boolean claimComment(JavaCommentTrivia trivia, Set<Comment> claimedComments) {
        boolean claimed = trivia.claim(claimedComments);
        if (!claimed && enabled()) {
            throw new AssertionError("Formatter comment guardrail failed: duplicate claim for "
                    + describe(trivia.comment()));
        }
        return claimed;
    }

    /**
     * Records comments that reached the output through an explicit raw-source preservation path.
     */
    static void accountRawComments(Node node, Set<Comment> rawRenderedComments) {
        rawRenderedComments.addAll(node.getAllContainedComments());
    }

    /**
     * Records raw-preserved comments while excluding the node's own attached comment.
     */
    static void accountRawCommentsWithoutOwnComment(Node node, Set<Comment> rawRenderedComments) {
        Optional<Comment> ownComment = node.getComment();
        node.getAllContainedComments().stream()
                .filter(comment -> ownComment.stream().noneMatch(own -> own == comment))
                .forEach(rawRenderedComments::add);
    }

    /**
     * Asserts that every JavaParser-exposed comment reached either structured rendering or raw preservation.
     */
    static void assertAllCommentsAccounted(
            Node root,
            Set<Comment> claimedComments,
            Set<Comment> rawRenderedComments) {
        if (!enabled()) {
            return;
        }
        List<Comment> missedComments = root.getAllContainedComments().stream()
                .filter(comment -> !claimedComments.contains(comment))
                .filter(comment -> !rawRenderedComments.contains(comment))
                .sorted(CommentIndex.sourceOrderComparator())
                .toList();
        if (!missedComments.isEmpty()) {
            throw new AssertionError("Formatter comment guardrail failed: unclaimed comment "
                    + describe(missedComments.getFirst())
                    + " was exposed by JavaParser but was not printed or raw-accounted before formatting completed");
        }
    }

    static boolean enabled() {
        return Boolean.getBoolean(ENABLED_PROPERTY);
    }

    private static String describe(Comment comment) {
        String range = comment.getRange().map(Object::toString).orElse("unknown range");
        String text = snippet(comment.toString());
        return comment.getClass().getSimpleName() + " at " + range + " [" + text + "]";
    }

    private static String snippet(String text) {
        String singleLine = text.strip().replaceAll("\\R", "\\\\n");
        if (singleLine.length() <= COMMENT_SNIPPET_LENGTH) {
            return singleLine;
        }
        return singleLine.substring(0, COMMENT_SNIPPET_LENGTH) + "...";
    }
}
