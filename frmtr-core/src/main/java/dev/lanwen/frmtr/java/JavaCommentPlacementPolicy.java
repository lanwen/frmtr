package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.expr.LambdaExpr;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Answers comment placement queries from the formatter's per-run Java comment map.
 *
 * <p>This helper owns read-only classification decisions over {@link JavaCommentMap}: leading attachment, trailing line
 * comments, orphan comments, contained comments, between-neighbor line comments, and same-line block-comment placement.
 * The boundary exists so printers ask one policy object how JavaParser comment associations map back to source
 * positions, while {@link CommentIndex} remains the low-level range predicate layer. Content-gap queries also own the
 * distinction between a node's parser range and the first non-comment source line inside that node, because JavaParser
 * can widen first children to include comments that visually precede the code token.
 *
 * <p>Callers still own rendering, spacing, indentation, syntax-specific grouping, and comment claim state. This policy
 * never creates {@link dev.lanwen.frmtr.doc.Doc} values and never mutates {@link CommentTracker}'s printed-comment
 * accounting.
 */
final class JavaCommentPlacementPolicy {

    private JavaCommentMap commentMap;

    private final Map<Node, Map<Integer, List<JavaCommentTrivia>>> containedCommentsByBeginLine =
        new IdentityHashMap<>();

    private final Map<Node, Integer> contentBeginLines = new IdentityHashMap<>();

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
        containedCommentsByBeginLine.clear();
        contentBeginLines.clear();
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
        return orphanComments(node)
                .stream()
                .sorted(Comparator.comparingInt(comment -> comment.beginLine(Integer.MAX_VALUE)))
                .toList();
    }

    /**
     * Returns orphan comments that do not start inside one of the supplied child node ranges.
     *
     * <p>JavaParser may leave comments as parent orphans even when their source line belongs inside a child range. This
     * query lets block-like printers keep those comments with the child renderer instead of hoisting them to the parent
     * sequence.
     *
     * <p>A comment that merely <em>trails</em> a child — it begins on the child's end line but after the child's last
     * token, e.g. {@code return; /* dead code *}{@code /} — is deliberately kept as a parent orphan rather than handed to
     * the child. Statement printers recover only the trailing block comment that lives inside their own token range; a
     * trailing comment JavaParser parked as a block orphan is in no statement's range, so excluding it here on the
     * coarse line-range test alone would drop it entirely. Keeping it lets the block sequence render it after the
     * statement, independent of whether source put it on the same line or the next one.
     */
    List<JavaCommentTrivia> orphanCommentsOutsideChildRanges(Node node, Collection<? extends Node> children) {
        return orphanComments(node, comment -> children.stream().noneMatch(comment::isInsideNotTrailing));
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
     * Reports whether {@code node} contains any comments, the run-indexed answer to the cheap
     * {@code getAllContainedComments().isEmpty()} safety gate.
     *
     * <p>This is the indexed gate that source-shape and compact-layout decisions ask before assuming a node can be
     * reconstructed or kept on one line without losing comment content. The answer comes from the per-run
     * {@link JavaCommentMap}, so it is only meaningful for original nodes from the current formatting run: an unknown
     * detached or cloned node reports {@code false} because the run snapshot has no record of it (see
     * {@link JavaCommentMap#containedComments(Node)}). Callers that may hold clones must keep their own JavaParser scan
     * rather than route through this query.
     */
    boolean hasContainedComments(Node node) {
        return !containedComments(node).isEmpty();
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
        return sourceOrderedDistinct(
            java.util.stream.Stream.concat(
                lineCommentsInRange(container, previousLine, nextLine).stream(),
                java.util.stream.Stream.concat(
                    ownComment(next, JavaCommentTrivia::isLine)
                            .filter(comment -> comment.beginLine(Integer.MAX_VALUE) >= previousLine)
                            .filter(comment -> comment.beginLine(Integer.MAX_VALUE) < nextLine)
                            .stream(),
                    leadingContentCluster(container, previousLine, next)
                            .stream()
                            .filter(comment -> comment.beginLine(Integer.MAX_VALUE) >= previousLine)
                )
            ).toList()
        );
    }

    /**
     * Finds standalone line comments between two neighboring nodes, excluding comments trailing {@code previous}.
     */
    List<JavaCommentTrivia> standaloneLineCommentsBetween(Node container, Node previous, Node next) {
        return lineCommentsBetween(container, previous, next)
                .stream()
                .filter(comment -> !comment.startsOnEndLine(previous))
                .toList();
    }

    /**
     * Finds line comments after an opening delimiter and before the first child node in {@code container}.
     */
    List<JavaCommentTrivia> lineCommentsBeforeFirst(Node container, Node first) {
        int containerLine = CommentIndex.beginLine(container, Integer.MIN_VALUE);
        int firstLine = CommentIndex.beginLine(first, Integer.MAX_VALUE);
        return sourceOrderedDistinct(
            java.util.stream.Stream.concat(
                lineCommentsInRange(container, containerLine, firstLine).stream(),
                leadingContentCluster(container, containerLine, first).stream()
            ).toList()
        );
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
        int nodeBeginLine = CommentIndex.beginLine(node, Integer.MAX_VALUE);
        if (nodeBeginLine == Integer.MAX_VALUE) {
            return List.of();
        }
        return adjacentLeadingLineComments(node, nodeBeginLine);
    }

    /**
     * Returns a range-starting line-comment cluster that JavaParser folded into {@code node}'s own range.
     *
     * <p>This is intentionally narrower than "all comments before first content." A parent gap may recover comments
     * only when the node range itself starts on the comment cluster. Comments inside nested delimiters, such as an
     * enclosed operand's own opening parenthesis followed by a comment, stay with that nested construct.
     */
    private List<JavaCommentTrivia> leadingContentCluster(Node container, int lowerLineInclusive, Node node) {
        int nodeRangeLine = CommentIndex.beginLine(node, Integer.MAX_VALUE);
        int contentLine = contentBeginLine(node, nodeRangeLine);
        if (
            nodeRangeLine == Integer.MAX_VALUE
            || contentLine <= nodeRangeLine
            || node instanceof LambdaExpr
        ) {
            return List.of();
        }
        List<JavaCommentTrivia> cluster = adjacentLeadingClusterInContainingPath(
            container,
            contentLine
        )
                .stream()
                .filter(comment -> comment.beginLine(Integer.MAX_VALUE) >= lowerLineInclusive)
                .toList();
        return !cluster.isEmpty() && cluster.getLast().beginLine(Integer.MAX_VALUE) == nodeRangeLine
            ? cluster
            : List.of();
    }

    private List<JavaCommentTrivia> adjacentLeadingClusterInContainingPath(Node node, int nodeBeginLine) {
        List<JavaCommentTrivia> cluster = new ArrayList<>();
        int expectedLine = nodeBeginLine - 1;
        while (expectedLine >= 1) {
            List<JavaCommentTrivia> lineComments = commentsOwnedByOrContainingPathStartingOnLine(node, expectedLine)
                    .stream()
                    .filter(JavaCommentTrivia::isLine)
                    .toList();
            if (lineComments.isEmpty()) {
                break;
            }
            cluster.addAll(lineComments);
            expectedLine--;
        }
        return sourceOrderedDistinct(cluster);
    }

    private List<JavaCommentTrivia> adjacentLeadingLineComments(Node node, int nodeBeginLine) {
        List<JavaCommentTrivia> cluster = new ArrayList<>();
        int expectedLine = nodeBeginLine - 1;
        while (expectedLine >= 1) {
            List<JavaCommentTrivia> lineComments = adjacentLeadingCandidatesOnLine(
                node,
                nodeBeginLine,
                expectedLine
            );
            if (lineComments.isEmpty()) {
                break;
            }
            cluster.addAll(lineComments);
            expectedLine--;
        }
        return sourceOrderedDistinct(cluster);
    }

    private List<JavaCommentTrivia> adjacentLeadingCandidatesOnLine(
            Node node,
            int nodeBeginLine,
            int line
    ) {
        List<JavaCommentTrivia> candidates = new ArrayList<>();
        Node pathChild = node;
        Optional<Node> parent = node.getParentNode();
        while (parent.isPresent()) {
            Node owner = parent.orElseThrow();
            candidates.addAll(adjacentLeadingCandidates(owner, pathChild, node, nodeBeginLine, line));
            pathChild = owner;
            parent = owner.getParentNode();
        }
        return sourceOrderedDistinct(candidates);
    }

    private List<JavaCommentTrivia> adjacentLeadingCandidates(
            Node parent,
            Node pathChild,
            Node node,
            int nodeBeginLine,
            int line
    ) {
        int nodeRangeBeginLine = CommentIndex.beginLine(node, nodeBeginLine);
        return commentsOwnedByOrContainedInStartingOnLine(parent, line)
                .stream()
                .filter(JavaCommentTrivia::isLine)
                .filter(comment -> comment.beginLine(Integer.MAX_VALUE) <= nodeRangeBeginLine)
                .filter(comment -> comment.beginLine(Integer.MAX_VALUE) < nodeBeginLine)
                .filter(comment -> !startsAfterNodeBeginOnSameLine(node, comment))
                .filter(comment -> pathChild == node || !comment.startsInsideLineRange(pathChild))
                .filter(comment -> !comment.startsOnBeginLine(parent))
                .filter(comment -> !comment.startsOnEndLine(pathChild))
                .filter(comment -> !startsInsideOtherDirectChild(parent, pathChild, comment))
                .filter(comment -> !startsAfterOtherDirectChildOnSameLine(parent, pathChild, comment))
                .toList();
    }

    private List<JavaCommentTrivia> commentsOwnedByOrContainingPathStartingOnLine(Node node, int line) {
        List<JavaCommentTrivia> comments = new ArrayList<>();
        Optional<Node> owner = Optional.of(node);
        while (owner.isPresent()) {
            Node current = owner.orElseThrow();
            comments.addAll(commentsOwnedByOrContainedInStartingOnLine(current, line));
            owner = current.getParentNode();
        }
        return sourceOrderedDistinct(comments);
    }

    private List<JavaCommentTrivia> commentsOwnedByOrContainedInStartingOnLine(Node node, int line) {
        List<JavaCommentTrivia> comments = new ArrayList<>();
        ownComment(node, comment -> comment.beginLine(Integer.MIN_VALUE) == line).ifPresent(comments::add);
        comments.addAll(containedCommentsStartingOnLine(node, line));
        return sourceOrderedDistinct(comments);
    }

    /**
     * Returns comments that JavaParser attached directly to {@code node} or recursively contained below it.
     *
     * <p>Leading-cluster recovery and declaration-prefix printing both need a single ownership view across own-comment
     * and contained-comment associations, because JavaParser can split adjacent source comments between those two
     * buckets.
     */
    List<JavaCommentTrivia> commentsOwnedByOrContainedIn(Node node) {
        return java.util.stream.Stream.concat(ownComment(node).stream(), containedComments(node).stream())
                .distinct()
                .toList();
    }

    /**
     * Finds a line comment source placed after {@code node} on the same line, even when JavaParser attached that comment
     * to a parent on the containment path.
     *
     * <p>Parser recovery can keep a selector or expression parsed while associating its trailing line comment with the
     * surrounding construct. This query keeps the decision source-position based so callers do not need to know which
     * parser comment bucket happened to receive the trivia.
     */
    Optional<JavaCommentTrivia> sameLineTrailingLineComment(Node node) {
        int nodeEndLine = CommentIndex.endLine(node, Integer.MIN_VALUE);
        if (nodeEndLine == Integer.MIN_VALUE) {
            return Optional.empty();
        }
        return commentsOwnedByOrContainingPathStartingOnLine(node, nodeEndLine)
                .stream()
                .filter(JavaCommentTrivia::isLine)
                .filter(comment -> comment.startsAfterNodeOnSameLine(node))
                .findFirst();
    }

    private List<JavaCommentTrivia> sourceOrderedDistinct(List<JavaCommentTrivia> comments) {
        return comments.stream()
                .distinct()
                .sorted(Comparator.comparing(JavaCommentTrivia::comment, CommentIndex.sourceOrderComparator()))
                .toList();
    }

    /**
     * Returns the first source line that belongs to non-comment content inside {@code node}.
     *
     * <p>JavaParser sometimes starts the range of the first statement, operand, or declaration child on a leading
     * {@code //} line. Cluster recovery needs the first real code line instead, otherwise the leading comments appear to
     * be inside the node rather than adjacent to it.
     */
    private int contentBeginLine(Node node, int fallback) {
        if (node.getRange().isEmpty()) {
            return uncachedContentBeginLine(node, fallback);
        }
        return contentBeginLines.computeIfAbsent(
            node,
            ignored -> uncachedContentBeginLine(node, fallback)
        );
    }

    private int uncachedContentBeginLine(Node node, int fallback) {
        int nodeBegin = CommentIndex.beginLine(node, fallback);
        return node.getChildNodes()
                .stream()
                .filter(child -> !(child instanceof Comment))
                .mapToInt(child -> contentBeginLine(child, CommentIndex.beginLine(child, nodeBegin)))
                .min()
                .orElse(nodeBegin);
    }

    private boolean startsInsideOtherDirectChild(Node parent, Node node, JavaCommentTrivia comment) {
        return parent.getChildNodes()
                .stream()
                .filter(child -> !(child instanceof Comment))
                .filter(child -> child != node)
                .anyMatch(comment::startsInsideLineRange);
    }

    private boolean startsAfterOtherDirectChildOnSameLine(Node parent, Node node, JavaCommentTrivia comment) {
        return parent.getChildNodes()
                .stream()
                .filter(child -> !(child instanceof Comment))
                .filter(child -> child != node)
                .anyMatch(comment::startsAfterNodeOnSameLine);
    }

    private boolean startsAfterNodeBeginOnSameLine(Node node, JavaCommentTrivia comment) {
        return node.getRange()
                .flatMap(nodeRange -> comment.comment()
                            .getRange()
                            .map(commentRange -> commentRange.begin.line == nodeRange.begin.line
                                    && commentRange.begin.column > nodeRange.begin.column
                            )
                )
                .orElse(false);
    }

    private List<JavaCommentTrivia> lineCommentsInRange(
            Node container,
            int beginLineInclusive,
            int endLineExclusive
    ) {
        return containedComments(container)
                .stream()
                .filter(JavaCommentTrivia::isLine)
                .filter(comment -> comment.comment()
                            .getRange()
                            .map(range -> range.begin.line >= beginLineInclusive && range.begin.line < endLineExclusive)
                            .orElse(false)
                )
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
                && comment.startsBefore(node)
        );
    }

    /**
     * Finds the nearest unattached line comment that source placed after {@code node} on the same line.
     *
     * <p>The parent walk follows JavaParser's containment hierarchy from nearest to farthest owner, preserving the old
     * "first recoverable same-line comment" behavior while using the run's cached contained-comment map.
     */
    Optional<JavaCommentTrivia> unattachedTrailingLineComment(Node node) {
        return unattachedTrailingComment(node, JavaCommentTrivia::isLine);
    }

    /**
     * Finds the nearest unattached block comment that source placed after {@code node} on the same line.
     *
     * <p>The parent walk follows JavaParser's containment hierarchy from nearest to farthest owner, preserving the old
     * "first recoverable same-line block comment" behavior while moving the raw contained-comment scan behind the
     * central policy.
     */
    Optional<JavaCommentTrivia> unattachedTrailingBlockComment(Node node) {
        return unattachedTrailingComment(node, JavaCommentTrivia::isBlock);
    }

    private Optional<JavaCommentTrivia> unattachedTrailingComment(
            Node node,
            Predicate<JavaCommentTrivia> commentKind
    ) {
        Optional<Node> parent = node.getParentNode();
        int nodeEndLine = CommentIndex.endLine(node, Integer.MIN_VALUE);
        while (parent.isPresent()) {
            Optional<JavaCommentTrivia> trailing = containedCommentsStartingOnLine(parent.orElseThrow(), nodeEndLine)
                    .stream()
                    .filter(commentKind)
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

    private List<JavaCommentTrivia> containedCommentsStartingOnLine(Node node, int line) {
        if (line == Integer.MIN_VALUE) {
            return List.of();
        }
        return containedCommentsByBeginLine.computeIfAbsent(node, this::containedCommentsByLine)
                .getOrDefault(line, List.of());
    }

    private Map<Integer, List<JavaCommentTrivia>> containedCommentsByLine(Node node) {
        Map<Integer, List<JavaCommentTrivia>> byLine = new HashMap<>();
        for (JavaCommentTrivia comment : containedComments(node)) {
            int beginLine = comment.beginLine(Integer.MIN_VALUE);
            if (beginLine != Integer.MIN_VALUE) {
                byLine.computeIfAbsent(beginLine, ignored -> new ArrayList<>()).add(comment);
            }
        }
        byLine.replaceAll((ignored, comments) -> List.copyOf(comments));
        return byLine.isEmpty() ? Map.of() : Collections.unmodifiableMap(byLine);
    }

    /**
     * Finds the nearest block comment that source placed after {@code node} on the same line.
     *
     * <p>Unlike {@link #unattachedTrailingBlockComment(Node)}, this includes comments JavaParser attached to another
     * nearby node. Record components use this when a same-line block comment visually belongs to the component name even
     * if the parser associated it with the following component.
     */
    Optional<JavaCommentTrivia> trailingBlockCommentAfterNode(Node node) {
        return trailingBlockCommentsAfterNode(node).stream().findFirst();
    }

    /**
     * Finds source-ordered block comments that source placed after {@code node} on the same line.
     */
    List<JavaCommentTrivia> trailingBlockCommentsAfterNode(Node node) {
        Optional<Node> parent = node.getParentNode();
        while (parent.isPresent()) {
            List<JavaCommentTrivia> trailing = containedComments(parent.orElseThrow())
                    .stream()
                    .filter(JavaCommentTrivia::isBlock)
                    .filter(comment -> comment.startsAfterNodeOnSameLine(node))
                    .sorted(Comparator.comparing(JavaCommentTrivia::comment, CommentIndex.sourceOrderComparator()))
                    .toList();
            if (!trailing.isEmpty()) {
                return trailing;
            }
            parent = parent.orElseThrow().getParentNode();
        }
        return List.of();
    }

    private JavaCommentMap map() {
        if (commentMap == null) {
            throw new IllegalStateException("Java comment placement policy has not been initialized for a print run");
        }
        return commentMap;
    }
}
