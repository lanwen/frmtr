package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.comments.Comment;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Answers comment placement queries from the formatter's per-run Java comment map.
 *
 * <p>This helper owns read-only classification decisions over {@link JavaCommentMap}: leading attachment, trailing line
 * comments, orphan comments, contained comments, between-neighbor line comments, and same-line block-comment placement.
 * The boundary exists so printers ask one policy object how JavaParser comment associations map back to source
 * positions, while {@link CommentIndex} remains the low-level range predicate layer.
 *
 * <p>Callers still own rendering, spacing, indentation, syntax-specific grouping, and comment claim state. This policy
 * never creates {@link dev.lanwen.frmtr.doc.Doc} values and never mutates {@link CommentTracker}'s printed-comment
 * accounting.
 */
final class JavaCommentPlacementPolicy {
    private JavaCommentMap commentMap;

    /**
     * Initializes the policy once for a single {@link JavaPrinter#print(CompilationUnit)} run.
     *
     * <p>{@link JavaFormatContext} exists before the compilation unit is known, so this explicit start hook builds the
     * map at the print boundary instead of lazily rebuilding during individual queries.
     */
    void startRun(CompilationUnit unit) {
        if (commentMap != null) {
            throw new IllegalStateException("Java comment placement policy is already initialized for this print run");
        }
        commentMap = JavaCommentMap.from(unit);
    }

    /**
     * Returns the comment JavaParser attached directly to {@code node}.
     */
    Optional<JavaCommentTrivia> ownComment(Node node) {
        return map().ownComment(node);
    }

    /**
     * Returns the own comment only when it matches the caller's source-placement predicate.
     */
    Optional<JavaCommentTrivia> ownComment(Node node, Predicate<JavaCommentTrivia> predicate) {
        return ownComment(node).filter(predicate);
    }

    /**
     * Returns the ordinary leading comment candidate for {@code node}.
     *
     * <p>This is intentionally JavaParser's own-comment association without trailing-line exclusion. Existing dispatch
     * gates decide whether a trailing-line comment was already consumed and should suppress the leading slot.
     */
    Optional<JavaCommentTrivia> leadingComment(Node node) {
        return ownComment(node);
    }

    /**
     * Returns an own line comment that starts on the source line where {@code node} ends.
     */
    Optional<JavaCommentTrivia> trailingLineComment(Node node) {
        return ownComment(node, JavaCommentTrivia::isLine).filter(comment -> comment.startsOnEndLine(node));
    }

    /**
     * Returns JavaParser orphan comments associated directly with {@code node}.
     */
    List<JavaCommentTrivia> orphanComments(Node node) {
        return map().orphanComments(node);
    }

    /**
     * Returns orphan comments that match the caller's placement predicate.
     */
    List<JavaCommentTrivia> orphanComments(Node node, Predicate<JavaCommentTrivia> predicate) {
        return orphanComments(node).stream().filter(predicate).toList();
    }

    /**
     * Returns orphan comments before a source line boundary.
     */
    List<JavaCommentTrivia> orphanCommentsBeforeLine(Node node, int line) {
        return orphanComments(node, comment -> comment.beginLine(Integer.MAX_VALUE) < line);
    }

    /**
     * Returns orphan comments after a source line boundary.
     */
    List<JavaCommentTrivia> orphanCommentsAfterLine(Node node, int line) {
        return orphanComments(node, comment -> comment.beginLine(Integer.MAX_VALUE) > line);
    }

    /**
     * Returns source-ordered orphan comments associated directly with {@code node}.
     */
    List<JavaCommentTrivia> orphanCommentsInSourceOrder(Node node) {
        return orphanComments(node).stream()
                .sorted(Comparator.comparingInt(comment -> comment.beginLine(Integer.MAX_VALUE)))
                .toList();
    }

    /**
     * Returns orphan comments that do not start inside one of the supplied child node ranges.
     *
     * <p>JavaParser may leave comments as parent orphans even when their source line belongs inside a child range. This
     * query lets block-like printers keep those comments with the child renderer instead of hoisting them to the parent
     * sequence.
     */
    List<JavaCommentTrivia> orphanCommentsOutsideChildRanges(Node node, Collection<? extends Node> children) {
        return orphanComments(node, comment -> children.stream().noneMatch(comment::startsInsideLineRange));
    }

    /**
     * Reports whether {@code node} has any direct orphan comments in the run map.
     */
    boolean hasOrphanComments(Node node) {
        return !orphanComments(node).isEmpty();
    }

    /**
     * Returns JavaParser's recursive contained comments for {@code node}.
     */
    List<JavaCommentTrivia> containedComments(Node node) {
        return map().containedComments(node);
    }

    /**
     * Reports whether {@code node} contains any line comments.
     */
    boolean hasContainedLineComments(Node node) {
        return containedComments(node).stream().anyMatch(JavaCommentTrivia::isLine);
    }

    /**
     * Finds line comments whose source lines sit between two neighboring nodes inside {@code container}.
     *
     * <p>The comparison is intentionally line-based, matching the previous binary-expression behavior that preserved
     * comments in the gap between adjacent operands without making column ownership part of the rule.
     */
    List<JavaCommentTrivia> lineCommentsBetween(Node container, Node previous, Node next) {
        int previousLine = CommentIndex.endLine(previous, Integer.MIN_VALUE);
        int nextLine = CommentIndex.beginLine(next, Integer.MAX_VALUE);
        return lineCommentsInRange(container, previousLine, nextLine);
    }

    /**
     * Finds line comments after an opening delimiter and before the first child node in {@code container}.
     */
    List<JavaCommentTrivia> lineCommentsBeforeFirst(Node container, Node first) {
        int containerLine = CommentIndex.beginLine(container, Integer.MIN_VALUE);
        int firstLine = CommentIndex.beginLine(first, Integer.MAX_VALUE);
        return lineCommentsInRange(container, containerLine, firstLine);
    }

    /**
     * Finds line comments after the last child node and before {@code container}'s closing delimiter.
     */
    List<JavaCommentTrivia> lineCommentsAfterLast(Node container, Node last) {
        int lastLine = CommentIndex.endLine(last, Integer.MIN_VALUE);
        int containerEndLine = CommentIndex.endLine(container, Integer.MAX_VALUE);
        return lineCommentsInRange(container, lastLine, containerEndLine);
    }

    /**
     * Returns the contiguous line-comment cluster immediately before {@code node}.
     *
     * <p>JavaParser exposes only one own comment per node, and may leave the earlier comments in an adjacent leading
     * cluster as parent-contained trivia instead of block orphans. This query lets statement and resource printers
     * recover those preceding lines without taking comments that trail a previous sibling on the same line or comments
     * that start inside another direct child range.
     */
    List<JavaCommentTrivia> adjacentLeadingLineComments(Node node) {
        return node.getParentNode()
                .map(parent -> adjacentLeadingLineComments(parent, node))
                .orElse(List.of());
    }

    private List<JavaCommentTrivia> adjacentLeadingLineComments(Node parent, Node node) {
        int nodeBeginLine = CommentIndex.beginLine(node, Integer.MAX_VALUE);
        if (nodeBeginLine == Integer.MAX_VALUE) {
            return List.of();
        }
        List<JavaCommentTrivia> candidates = containedComments(parent).stream()
                .filter(JavaCommentTrivia::isLine)
                .filter(comment -> comment.beginLine(Integer.MAX_VALUE) < nodeBeginLine)
                .filter(comment -> !startsInsideOtherDirectChild(parent, node, comment))
                .filter(comment -> !startsAfterOtherDirectChildOnSameLine(parent, node, comment))
                .sorted(Comparator.comparing(JavaCommentTrivia::comment, CommentIndex.sourceOrderComparator()))
                .toList();
        List<JavaCommentTrivia> cluster = new ArrayList<>();
        int expectedLine = nodeBeginLine - 1;
        for (int index = candidates.size() - 1; index >= 0; index--) {
            JavaCommentTrivia comment = candidates.get(index);
            int commentEndLine = comment.endLine(comment.beginLine(Integer.MIN_VALUE));
            if (commentEndLine != expectedLine) {
                break;
            }
            cluster.add(comment);
            expectedLine = comment.beginLine(commentEndLine) - 1;
        }
        return cluster.reversed();
    }

    private boolean startsInsideOtherDirectChild(Node parent, Node node, JavaCommentTrivia comment) {
        return parent.getChildNodes().stream()
                .filter(child -> !(child instanceof Comment))
                .filter(child -> child != node)
                .anyMatch(comment::startsInsideLineRange);
    }

    private boolean startsAfterOtherDirectChildOnSameLine(Node parent, Node node, JavaCommentTrivia comment) {
        return parent.getChildNodes().stream()
                .filter(child -> !(child instanceof Comment))
                .filter(child -> child != node)
                .anyMatch(comment::startsAfterNodeOnSameLine);
    }

    private List<JavaCommentTrivia> lineCommentsInRange(
            Node container,
            int beginLineInclusive,
            int endLineExclusive) {
        return containedComments(container).stream()
                .filter(JavaCommentTrivia::isLine)
                .filter(comment -> comment.comment()
                        .getRange()
                        .map(range -> range.begin.line >= beginLineInclusive && range.begin.line < endLineExclusive)
                        .orElse(false))
                .sorted(Comparator.comparing(comment -> comment.comment(), CommentIndex.sourceOrderComparator()))
                .toList();
    }

    /**
     * Selects comments that begin on the same line where {@code node} ends.
     */
    List<JavaCommentTrivia> commentsStartingOnEndLine(Node node, List<JavaCommentTrivia> comments) {
        return comments.stream().filter(comment -> comment.startsOnEndLine(node)).toList();
    }

    /**
     * Returns a block comment attached to {@code node} when source placed it before the node on the same line.
     */
    Optional<JavaCommentTrivia> ownSameLineBlockCommentBeforeNode(Node node) {
        return ownComment(node, comment -> comment.isBlock()
                && comment.startsOnBeginLine(node)
                && comment.startsBefore(node));
    }

    /**
     * Finds the nearest unattached block comment that source placed after {@code node} on the same line.
     *
     * <p>The parent walk follows JavaParser's containment hierarchy from nearest to farthest owner, preserving the old
     * "first recoverable same-line block comment" behavior while moving the raw contained-comment scan behind the
     * central policy.
     */
    Optional<JavaCommentTrivia> unattachedTrailingBlockComment(Node node) {
        Optional<Node> parent = node.getParentNode();
        while (parent.isPresent()) {
            Optional<JavaCommentTrivia> trailing = containedComments(parent.orElseThrow()).stream()
                    .filter(JavaCommentTrivia::isBlock)
                    .filter(comment -> comment.comment().getCommentedNode().isEmpty())
                    .filter(comment -> comment.startsAfterNodeOnSameLine(node))
                    .findFirst();
            if (trailing.isPresent()) {
                return trailing;
            }
            parent = parent.orElseThrow().getParentNode();
        }
        return Optional.empty();
    }

    private JavaCommentMap map() {
        if (commentMap == null) {
            throw new IllegalStateException("Java comment placement policy has not been initialized for a print run");
        }
        return commentMap;
    }
}
