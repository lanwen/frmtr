package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.comments.Comment;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Captures JavaParser's comment associations for one Java formatting run.
 *
 * <p>This helper owns the per-run snapshot of JavaParser's own, orphan, and contained comment views, preserving
 * JavaParser object identity for node lookups and wrapping every comment in {@link JavaCommentTrivia}. The boundary
 * exists because printers need a shared comment vocabulary, but repeatedly walking raw JavaParser comment APIs makes it
 * easy for placement rules and claim state to drift apart.
 *
 * <p>Callers still decide whether a comment should be treated as leading, trailing, orphan, contained, or
 * syntax-specific, how matching comments are ordered in rendered output, and when a matched comment is claimed by
 * {@link CommentTracker}. This map does not render comments, mutate claim state, or choose formatter layout.
 */
final class JavaCommentMap {

    private final Map<Node, Optional<JavaCommentTrivia>> ownComments;

    private final Map<Node, List<JavaCommentTrivia>> orphanComments;

    private final Map<Node, List<JavaCommentTrivia>> containedComments;

    private JavaCommentMap(
            Map<Node, Optional<JavaCommentTrivia>> ownComments,
            Map<Node, List<JavaCommentTrivia>> orphanComments,
            Map<Node, List<JavaCommentTrivia>> containedComments
    ) {
        this.ownComments = ownComments;
        this.orphanComments = orphanComments;
        this.containedComments = containedComments;
    }

    /**
     * Builds the one-run map from JavaParser's current tree associations.
     *
     * <p>The walk computes contained comments bottom-up instead of asking JavaParser to recursively rebuild the same
     * list for every node. JavaParser's views can overlap by comment identity, so the map stores association lists
     * independently and leaves duplicate rendering prevention to {@link CommentTracker}.
     */
    static JavaCommentMap from(CompilationUnit unit) {
        Map<Node, Optional<JavaCommentTrivia>> ownComments = new IdentityHashMap<>();
        Map<Node, List<JavaCommentTrivia>> orphanComments = new IdentityHashMap<>();
        Map<Node, List<JavaCommentTrivia>> containedComments = new IdentityHashMap<>();
        Map<Comment, JavaCommentTrivia> trivia = new IdentityHashMap<>();
        recordNode(unit, ownComments, orphanComments, containedComments, trivia);
        return new JavaCommentMap(
            Collections.unmodifiableMap(ownComments),
            Collections.unmodifiableMap(orphanComments),
            Collections.unmodifiableMap(containedComments)
        );
    }

    /**
     * Records JavaParser's direct comment views for a node and derives recursive containment from its children.
     *
     * <p>Own comments model JavaParser's nearest-node attachment, orphan comments model trivia held by a parent, and
     * contained comments model the recursive comment span under a node. The contained-comment order intentionally
     * mirrors JavaParser: this node's orphans first, then each child's own comment, then that child's contained
     * comments. The formatter needs all three because no single JavaParser association answers every source-placement
     * question.
     */
    private static List<JavaCommentTrivia> recordNode(
            Node node,
            Map<Node, Optional<JavaCommentTrivia>> ownComments,
            Map<Node, List<JavaCommentTrivia>> orphanComments,
            Map<Node, List<JavaCommentTrivia>> containedComments,
            Map<Comment, JavaCommentTrivia> trivia
    ) {
        Optional<JavaCommentTrivia> ownComment = node.getComment().map(comment -> trivia(trivia, comment));
        List<JavaCommentTrivia> ownOrphans = triviaList(node.getOrphanComments(), trivia);

        ownComments.put(node, ownComment);
        orphanComments.put(node, ownOrphans);

        List<JavaCommentTrivia> contained = null;
        if (!ownOrphans.isEmpty()) {
            contained = new ArrayList<>(ownOrphans);
        }
        for (Node child : node.getChildNodes()) {
            List<JavaCommentTrivia> childContained = recordNode(
                child,
                ownComments,
                orphanComments,
                containedComments,
                trivia
            );
            Optional<JavaCommentTrivia> childOwnComment = ownComments.get(child);
            if (childOwnComment.isPresent()) {
                contained = addTo(contained, childOwnComment.orElseThrow());
            }
            if (!childContained.isEmpty()) {
                contained = addAllTo(contained, childContained);
            }
        }

        List<JavaCommentTrivia> containedView = immutableList(contained);
        containedComments.put(node, containedView);
        return containedView;
    }

    private static List<JavaCommentTrivia> triviaList(
            List<? extends Comment> comments,
            Map<Comment, JavaCommentTrivia> trivia
    ) {
        if (comments.isEmpty()) {
            return List.of();
        }
        return comments.stream().map(comment -> trivia(trivia, comment)).toList();
    }

    private static JavaCommentTrivia trivia(Map<Comment, JavaCommentTrivia> trivia, Comment comment) {
        return trivia.computeIfAbsent(comment, JavaCommentTrivia::from);
    }

    private static List<JavaCommentTrivia> addTo(List<JavaCommentTrivia> comments, JavaCommentTrivia comment) {
        List<JavaCommentTrivia> present = comments == null ? new ArrayList<>() : comments;
        present.add(comment);
        return present;
    }

    private static List<JavaCommentTrivia> addAllTo(
            List<JavaCommentTrivia> comments,
            List<JavaCommentTrivia> moreComments
    ) {
        List<JavaCommentTrivia> present = comments == null ? new ArrayList<>(moreComments.size()) : comments;
        present.addAll(moreComments);
        return present;
    }

    private static List<JavaCommentTrivia> immutableList(List<JavaCommentTrivia> comments) {
        if (comments == null || comments.isEmpty()) {
            return List.of();
        }
        return List.copyOf(comments);
    }

    /**
     * Returns JavaParser's own-comment association for {@code node}, if the run snapshot contains one.
     *
     * <p>Unknown detached or cloned nodes keep the historical empty result because this map is queried by existing
     * formatter surfaces that do not yet distinguish detached inputs. New callers should treat that result as "not in
     * this run snapshot", not as proof that an original run node was comment-free.
     */
    Optional<JavaCommentTrivia> ownComment(Node node) {
        return ownComments.getOrDefault(node, Optional.empty());
    }

    /**
     * Returns JavaParser orphan comments associated directly with {@code node}.
     *
     * <p>Unknown detached or cloned nodes keep the historical empty result; see {@link #ownComment(Node)}.
     */
    List<JavaCommentTrivia> orphanComments(Node node) {
        return orphanComments.getOrDefault(node, List.of());
    }

    /**
     * Returns JavaParser's recursive contained comments for {@code node}.
     *
     * <p>Unknown detached or cloned nodes keep the historical empty result; see {@link #ownComment(Node)}.
     */
    List<JavaCommentTrivia> containedComments(Node node) {
        return containedComments.getOrDefault(node, List.of());
    }

    /**
     * Reports whether this run snapshot contains {@code node}.
     */
    boolean contains(Node node) {
        return containedComments.containsKey(node);
    }
}
