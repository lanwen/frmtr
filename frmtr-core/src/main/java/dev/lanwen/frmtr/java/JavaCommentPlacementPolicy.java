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
     * Returns an own line comment that trails {@code node} in source order.
     *
     * <p>The trailing-comment <em>ownership</em> question, deliberately source-order based (see
     * {@link CommentIndex#startsAfterEndOf(Node, Comment)}) rather than line-equality: an end-line test drops a
     * {@code } // note} comment a whitespace perturbation moved onto the next line. Source order is a strict superset at
     * {@code @default} (an end-line comment begins past the end column), so both select the same owner. Callers still
     * decide inline-{@code lineSuffix}-versus-own-line rendering.
     */
    Optional<JavaCommentTrivia> trailingLineComment(Node node) {
        return ownComment(node, JavaCommentTrivia::isLine).filter(comment -> comment.startsAfterEndOf(node));
    }

    /**
     * Recovers the line comment that trails {@code body} but that JavaParser parked as an orphan of {@code owner} rather
     * than as {@code body}'s own trivia.
     *
     * <p>The orphan-bucket sibling of {@link #trailingLineComment(Node)}: when a whitespace perturbation moves a clause
     * body's {@code } // note} comment onto the line below the brace, JavaParser leaves it as an orphan of the enclosing
     * construct (e.g. the {@code try}). This selects the {@code owner} orphan line comments after {@code body} ends and
     * before {@code nextStructural} begins, so each clause handoff claims exactly its own slice without swallowing later
     * clauses' trailing comments.
     */
    List<JavaCommentTrivia> trailingLineCommentsAfter(Node owner, Node body, Optional<? extends Node> nextStructural) {
        return orphanComments(owner)
                .stream()
                .filter(JavaCommentTrivia::isLine)
                .filter(comment -> comment.startsAfterEndOf(body))
                .filter(comment -> nextStructural.map(comment::startsBefore).orElse(true))
                .sorted(Comparator.comparing(JavaCommentTrivia::comment, CommentIndex.sourceOrderComparator()))
                .toList();
    }

    /**
     * Recovers the line and block comments that trail a variable/field {@code initializer} after its last token but
     * before the closing {@code ;}, which neither the initializer's own renderer nor the declarator's post-{@code ;}
     * trailing slot prints.
     *
     * <p>Both kinds are recovered — the slot also holds an inline block trailing a wrapped binary value's final operand
     * ({@code return a == 1 /* x *}{@code / || a == 2 /* y *}{@code /;}, the issue #93 final-operand comment). A
     * {@code //} after the last operand and before the {@code ;} is dropped both ways otherwise: JavaParser parks it as an
     * orphan of the {@code semicolonOwner} ({@link com.github.javaparser.ast.body.FieldDeclaration} /
     * {@link com.github.javaparser.ast.stmt.ExpressionStmt}) or as the initializer's contained trivia on the last operand,
     * while the between-operand renderer only reaches inter-operand comments and the declarator's own slot only the
     * post-{@code ;} comment.
     *
     * <p>Unioning both buckets and selecting only comments after {@code initializer} ends
     * ({@link CommentIndex#startsAfterEndOf(Node, Comment)}) makes the union safe: a leading/inter-operand comment begins
     * inside the initializer range and is excluded, so this can only add the genuinely-trailing comment both slots drop,
     * claimed once.
     */
    List<JavaCommentTrivia> trailingInitializerCommentsBeforeSemicolon(Node semicolonOwner, Node initializer) {
        return java.util.stream.Stream.concat(
                orphanComments(semicolonOwner).stream(),
                containedComments(initializer).stream()
            )
                .filter(comment -> comment.isLine() || comment.isBlock())
                .filter(comment -> comment.startsAfterEndOf(initializer))
                .distinct()
                .sorted(Comparator.comparing(JavaCommentTrivia::comment, CommentIndex.sourceOrderComparator()))
                .toList();
    }

    /**
     * Recovers the line comments that lead a control-statement {@code condition} but that JavaParser parked as orphans of
     * the enclosing {@code controlStmt} ({@code while}/{@code if}/{@code switch} statement or {@code switch} expression)
     * rather than as the condition's own contained trivia.
     *
     * <p>At {@code @default} a {@code while ( // note value )} comment is the condition's contained trivia, so the renderer
     * sees it and this adds nothing; a whitespace perturbation re-buckets it onto the control statement as an orphan,
     * where this recovers it by selecting the {@code controlStmt} orphan line comments after the statement begins (never a
     * comment leading the whole statement) and before {@code condition} begins.
     */
    List<JavaCommentTrivia> leadingConditionComments(Node controlStmt, Node condition) {
        return orphanComments(controlStmt)
                .stream()
                .filter(JavaCommentTrivia::isLine)
                .filter(comment -> startsAfterBeginOf(controlStmt, comment))
                .filter(comment -> comment.startsBefore(condition))
                .sorted(Comparator.comparing(JavaCommentTrivia::comment, CommentIndex.sourceOrderComparator()))
                .toList();
    }

    /**
     * Recovers the line comments that trail a control-statement {@code condition} after its closing parenthesis but that
     * JavaParser parked as orphans of the enclosing {@code controlStmt} rather than as the condition's own trivia.
     *
     * <p>The trailing counterpart to {@link #leadingConditionComments(Node, Node)}. At {@code @default} an
     * {@code if (cond) // note} comment is the condition's own trivia; a whitespace perturbation onto its own line
     * re-buckets it onto the enclosing statement, where this recovers it by selecting the {@code controlStmt} orphan line
     * comments after {@code condition} ends and before {@code body} begins (the {@code body} bound stops it swallowing the
     * body/then/else leading comments).
     */
    List<JavaCommentTrivia> trailingConditionComments(Node controlStmt, Node condition, Node body) {
        return orphanComments(controlStmt)
                .stream()
                .filter(JavaCommentTrivia::isLine)
                .filter(comment -> comment.startsAfterEndOf(condition))
                .filter(comment -> comment.startsBefore(body))
                .sorted(Comparator.comparing(JavaCommentTrivia::comment, CommentIndex.sourceOrderComparator()))
                .toList();
    }

    private boolean startsAfterBeginOf(Node node, JavaCommentTrivia comment) {
        return node.getRange()
                .map(range -> CommentIndex.startsAfter(comment.comment(), range.begin))
                .orElse(false);
    }

    /**
     * Recovers the line comment that sits between {@code afterNode} and {@code body} but that JavaParser parked on one of
     * the supplied {@code attachmentBuckets} instead of as {@code body}'s own leading trivia.
     *
     * <p>The gap-ownership counterpart to {@link #adjacentLeadingLineComments(Node)} for the {@code case label ->}/{@code body}
     * arm of a switch rule. At {@code @default} a {@code case x -> // note body} comment is {@code body}'s own trivia; a
     * whitespace perturbation re-buckets it onto the case label (collapse) or the switch entry orphan pool (expand). This
     * selects the {@code attachmentBuckets} line comments strictly between {@code afterNode} and {@code body}
     * ({@link CommentIndex#liesBetween(Comment, Node, Node)}), excluding {@code body}'s own comment (the caller renders
     * that). Source-order, so {@code @default} output is unchanged.
     */
    List<JavaCommentTrivia> gapLineCommentsBefore(
            Node afterNode,
            Node body,
            Collection<? extends Node> attachmentBuckets
    ) {
        Optional<Comment> bodyOwn = ownComment(body).map(JavaCommentTrivia::comment);
        return attachmentBuckets.stream()
                .flatMap(bucket -> ownAndOrphanComments(bucket).stream())
                .filter(JavaCommentTrivia::isLine)
                .filter(comment -> comment.liesBetween(afterNode, body))
                .filter(comment -> bodyOwn.map(own -> own != comment.comment()).orElse(true))
                .distinct()
                .sorted(Comparator.comparing(JavaCommentTrivia::comment, CommentIndex.sourceOrderComparator()))
                .toList();
    }

    /**
     * Recovers the full contiguous {@code //} comment block that sits between {@code afterNode} and {@code body}, no
     * matter how JavaParser split that block across attachment buckets.
     *
     * <p>The {@code else}/{@code else if} counterpart of {@link #gapLineCommentsBefore(Node, Node, Collection)} (which
     * excludes the body's own comment). JavaParser splits a multi-line block before {@code else} across the enclosing
     * {@code if}'s orphans and the {@code else if} node's own leading trivia, which renders from two slots — mangling
     * {@code else if} into {@code else //\n if} and rotating lines across passes.
     *
     * <p>Unioning every gap line comment across all buckets JavaParser may use (the body's own comment, {@code afterNode}'s
     * own/orphan trivia, and {@code attachmentBuckets}), deduped by identity in source order, lets the caller render and
     * claim the whole block in one deterministic slot above {@code else} so the nested {@code else if} cannot reclaim a
     * line; the {@code @default} contiguous run and every collapsed split recover the same lines.
     */
    List<JavaCommentTrivia> gapLeadingLineCommentBlock(
            Node afterNode,
            Node body,
            Collection<? extends Node> attachmentBuckets
    ) {
        List<JavaCommentTrivia> block = new ArrayList<>();
        ownComment(body)
                .filter(JavaCommentTrivia::isLine)
                .filter(comment -> comment.liesBetween(afterNode, body))
                .ifPresent(block::add);
        java.util.stream.Stream.concat(
                ownAndOrphanComments(afterNode).stream(),
                attachmentBuckets.stream().flatMap(bucket -> ownAndOrphanComments(bucket).stream())
            )
                .filter(JavaCommentTrivia::isLine)
                .filter(comment -> comment.liesBetween(afterNode, body))
                .forEach(block::add);
        return block.stream()
                .distinct()
                .sorted(Comparator.comparing(JavaCommentTrivia::comment, CommentIndex.sourceOrderComparator()))
                .toList();
    }

    /**
     * Recovers the line comments JavaParser parked between a {@code default} label's colon and its statement-group body
     * when the entry has no label node to anchor the gap on.
     *
     * <p>{@link #gapLineCommentsBefore(Node, Node, Collection)} bounds by {@link CommentIndex#liesBetween} (after the
     * anchor ends), exact for a {@code case X:} label expression. A {@code default:} entry owns no label, so the only
     * anchor is the entry itself, and a colon-to-body comment lies <em>inside</em> its range rather than after it — so it
     * is dropped once a perturbation moves it onto the entry's orphan pool. This bounds by the entry's <em>begin</em>
     * position instead (after the {@code default} keyword) and the body's begin, excluding the body's own leading comment
     * and this entry's leading comments. The {@code default}-label counterpart of the {@code case}-label gap recovery.
     */
    List<JavaCommentTrivia> defaultLabelGapLineCommentsBefore(
            Node entry,
            Node body,
            Collection<? extends Node> attachmentBuckets
    ) {
        Optional<Comment> bodyOwn = ownComment(body).map(JavaCommentTrivia::comment);
        return attachmentBuckets.stream()
                .flatMap(bucket -> ownAndOrphanComments(bucket).stream())
                .filter(JavaCommentTrivia::isLine)
                .filter(comment -> startsAfterBeginOf(entry, comment))
                .filter(comment -> comment.startsBefore(body))
                .filter(comment -> bodyOwn.map(own -> own != comment.comment()).orElse(true))
                .distinct()
                .sorted(Comparator.comparing(JavaCommentTrivia::comment, CommentIndex.sourceOrderComparator()))
                .toList();
    }

    private List<JavaCommentTrivia> ownAndOrphanComments(Node node) {
        return java.util.stream.Stream.concat(ownComment(node).stream(), orphanComments(node).stream()).toList();
    }

    /**
     * Returns the block comments JavaParser parked on the supplied {@code attachmentBuckets} that begin before
     * {@code boundary} in source order.
     *
     * <p>An inline case-label block ({@code case REMOTE /* remote *}{@code /, HYBRID}) attaches to a label expression or
     * the switch entry orphan pool by layout. A caller re-rendering the label list from raw token text needs the exact
     * set that text prints, to mark them accounted without claiming a body comment past {@code boundary} (the
     * {@code ->}/{@code :} marker or body). Source-order, so the set is stable however whitespace lays the labels out.
     */
    List<JavaCommentTrivia> blockCommentsBefore(Collection<? extends Node> attachmentBuckets, Node boundary) {
        return attachmentBuckets.stream()
                .flatMap(bucket -> ownAndOrphanComments(bucket).stream())
                .filter(JavaCommentTrivia::isBlock)
                .filter(comment -> comment.startsBefore(boundary))
                .distinct()
                .sorted(Comparator.comparing(JavaCommentTrivia::comment, CommentIndex.sourceOrderComparator()))
                .toList();
    }

    /**
     * Returns every comment (line <em>and</em> block) JavaParser parked on the supplied {@code attachmentBuckets} that
     * lies source-order strictly between {@code afterNode} and {@code body}, in source order.
     *
     * <p>The {@link com.github.javaparser.ast.stmt.LabeledStmt LabeledStmt} leading-comment counterpart of
     * {@link #gapLineCommentsBefore(Node, Node, Collection)}. A labeled statement's leading comments are reproduced
     * verbatim at {@code @default} from the raw {@code :}-to-statement slice (see
     * {@code StatementPrinter.labeledStatementLeadingComments}); a whitespace collapse re-buckets them onto the
     * {@code LabeledStmt} orphans, the label's own comment, or the nested statement, where the single-line slice no longer
     * exposes them. This recovers those by source position.
     *
     * <p>Unlike {@link #gapLineCommentsBefore(Node, Node, Collection)} it <em>keeps</em> {@code body}'s own comment (a
     * labeled empty {@code for (...) {}} body never emits its own trivia, so that slot can hide the leading comment) and
     * returns blocks too. It never claims; the caller's raw-slice dedupe before claiming keeps {@code @default}
     * byte-identical.
     */
    List<JavaCommentTrivia> gapCommentsBetween(
            Node afterNode,
            Node body,
            Collection<? extends Node> attachmentBuckets
    ) {
        return attachmentBuckets.stream()
                .flatMap(bucket -> ownAndOrphanComments(bucket).stream())
                .filter(comment -> comment.liesBetween(afterNode, body))
                .distinct()
                .sorted(Comparator.comparing(JavaCommentTrivia::comment, CommentIndex.sourceOrderComparator()))
                .toList();
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
     * <p>JavaParser may leave comments as parent orphans even when their source line belongs inside a child range, so
     * this lets block-like printers keep them with the child renderer instead of hoisting them to the parent sequence.
     *
     * <p>A comment that merely <em>trails</em> a child (begins on its end line but after its last token, e.g.
     * {@code return; /* dead code *}{@code /}) is deliberately kept as a parent orphan: it is in no statement's own token
     * range, so excluding it on the coarse line-range test would drop it entirely, whereas keeping it lets the block
     * sequence render it after the statement.
     */
    List<JavaCommentTrivia> orphanCommentsOutsideChildRanges(Node node, Collection<? extends Node> children) {
        return orphanComments(node, comment -> children.stream().noneMatch(comment::isInsideNotTrailing));
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
     * <p>Source-shape and compact-layout decisions ask this before assuming a node can be reconstructed or kept on one
     * line without losing comment content. The answer comes from the per-run {@link JavaCommentMap}, so an unknown
     * detached or cloned node reports {@code false} (see {@link JavaCommentMap#containedComments(Node)}); callers holding
     * clones must keep their own JavaParser scan.
     */
    boolean hasContainedComments(Node node) {
        return !containedComments(node).isEmpty();
    }

    /**
     * Reports whether {@code node} belongs to the current run snapshot.
     */
    boolean contains(Node node) {
        return map().contains(node);
    }

    /**
     * Reports whether {@code node} contains any line comments.
     */
    boolean hasContainedLineComments(Node node) {
        return containedComments(node).stream().anyMatch(JavaCommentTrivia::isLine);
    }

    /**
     * Finds line comments that sit in the source-order gap between two neighboring nodes inside {@code container}.
     *
     * <p>The gap-between-siblings <em>ownership</em> query (call arguments, record components, resources, operands, …),
     * source-order based ({@link CommentIndex#liesBetween(Comment, Node, Node)}) rather than a {@code begin.line}-window:
     * that window dropped a {@code arg(), // note} comment a perturbation moved onto the next line. Source order is a
     * strict superset at {@code @default}, so it does not move that output. Callers still own
     * inline-{@code lineSuffix}-versus-own-gap-line rendering.
     */
    List<JavaCommentTrivia> lineCommentsBetween(Node container, Node previous, Node next) {
        int previousLine = CommentIndex.endLine(previous, Integer.MIN_VALUE);
        return sourceOrderedDistinct(
            java.util.stream.Stream.concat(
                containedLineCommentsBetween(container, previous, next).stream(),
                java.util.stream.Stream.concat(
                    ownComment(next, JavaCommentTrivia::isLine)
                            .filter(comment -> comment.liesBetween(previous, next))
                            .stream(),
                    leadingContentCluster(container, previousLine, next)
                            .stream()
                            .filter(comment -> comment.startsAfterEndOf(previous))
                )
            ).toList()
        );
    }

    private List<JavaCommentTrivia> containedLineCommentsBetween(Node container, Node previous, Node next) {
        return containedComments(container)
                .stream()
                .filter(JavaCommentTrivia::isLine)
                .filter(comment -> comment.liesBetween(previous, next))
                .sorted(Comparator.comparing(JavaCommentTrivia::comment, CommentIndex.sourceOrderComparator()))
                .toList();
    }

    /**
     * Finds block comments that sit in the source-order gap between two neighboring nodes inside {@code container}.
     *
     * <p>The {@code /* ... *}{@code /} sibling of {@link #lineCommentsBetween(Node, Node, Node)}, for a binary chain's
     * between-operand slot. JavaParser parks an inter-operand block ({@code a /* note *}{@code / && b}) as the following
     * operand's own comment, so this unions {@code next}'s own block with {@code container}'s contained blocks, both
     * filtered to the source-order gap ({@link CommentIndex#liesBetween(Comment, Node, Node)}). Never claims; the caller
     * decides inline-versus-own-gap-line placement.
     */
    List<JavaCommentTrivia> blockCommentsBetween(Node container, Node previous, Node next) {
        return sourceOrderedDistinct(
            java.util.stream.Stream.concat(
                containedComments(container)
                        .stream()
                        .filter(JavaCommentTrivia::isBlock)
                        .filter(comment -> comment.liesBetween(previous, next)),
                ownComment(next, JavaCommentTrivia::isBlock)
                        .filter(comment -> comment.liesBetween(previous, next))
                        .stream()
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
