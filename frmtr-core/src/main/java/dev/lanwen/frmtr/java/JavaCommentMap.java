package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.comments.Comment;
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
            Map<Node, List<JavaCommentTrivia>> containedComments) {
        this.ownComments = ownComments;
        this.orphanComments = orphanComments;
        this.containedComments = containedComments;
    }

    /**
     * Builds the one-run map from JavaParser's current tree associations.
     *
     * <p>The walk records each node view exactly once instead of deriving placement candidates during later rendering
     * queries. JavaParser's views can overlap by comment identity, so the map stores association lists independently
     * and leaves duplicate rendering prevention to {@link CommentTracker}.
     */
    static JavaCommentMap from(CompilationUnit unit) {
        Map<Node, Optional<JavaCommentTrivia>> ownComments = new IdentityHashMap<>();
        Map<Node, List<JavaCommentTrivia>> orphanComments = new IdentityHashMap<>();
        Map<Node, List<JavaCommentTrivia>> containedComments = new IdentityHashMap<>();
        unit.stream().forEach(node -> recordNode(node, ownComments, orphanComments, containedComments));
        return new JavaCommentMap(
                Collections.unmodifiableMap(ownComments),
                Collections.unmodifiableMap(orphanComments),
                Collections.unmodifiableMap(containedComments));
    }

    /**
     * Records JavaParser's three comment views for a node while preserving their independent meanings.
     *
     * <p>Own comments model JavaParser's nearest-node attachment, orphan comments model trivia held by a parent, and
     * contained comments model the recursive comment span under a node. The formatter needs all three because no single
     * JavaParser association answers every source-placement question.
     */
    private static void recordNode(
            Node node,
            Map<Node, Optional<JavaCommentTrivia>> ownComments,
            Map<Node, List<JavaCommentTrivia>> orphanComments,
            Map<Node, List<JavaCommentTrivia>> containedComments) {
        ownComments.put(node, node.getComment().map(JavaCommentTrivia::from));
        orphanComments.put(node, triviaList(node.getOrphanComments()));
        containedComments.put(node, triviaList(node.getAllContainedComments()));
    }

    private static List<JavaCommentTrivia> triviaList(List<? extends Comment> comments) {
        return comments.stream().map(JavaCommentTrivia::from).toList();
    }

    /**
     * Returns JavaParser's own-comment association for {@code node}, if the run snapshot contains one.
     */
    Optional<JavaCommentTrivia> ownComment(Node node) {
        return ownComments.getOrDefault(node, Optional.empty());
    }

    /**
     * Returns JavaParser orphan comments associated directly with {@code node}.
     */
    List<JavaCommentTrivia> orphanComments(Node node) {
        return orphanComments.getOrDefault(node, List.of());
    }

    /**
     * Returns JavaParser's recursive contained comments for {@code node}.
     */
    List<JavaCommentTrivia> containedComments(Node node) {
        return containedComments.getOrDefault(node, List.of());
    }
}
