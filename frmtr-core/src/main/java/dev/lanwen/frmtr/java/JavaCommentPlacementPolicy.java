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
     * <p>This is the trailing-comment <em>ownership</em> question — "is {@code node}'s own line comment one that comes
     * after the node rather than leading it" — and is deliberately source-order based (see
     * {@link CommentIndex#startsAfterEndOf(Node, Comment)}) rather than line-equality based. Keying on the end line breaks
     * when a whitespace perturbation moves a {@code } // note} comment onto the line below the brace even though the AST is
     * unchanged, dropping the comment. The source-order test is a strict superset at the {@code @default} shape: a comment
     * genuinely on the node's end line begins past the node's end column, so both tests select the same owner there.
     * Callers still decide how the recovered comment is rendered (inline {@code lineSuffix} versus its own line); that
     * rendering choice stays line-based at the call sites and is not part of this ownership query.
     */
    Optional<JavaCommentTrivia> trailingLineComment(Node node) {
        return ownComment(node, JavaCommentTrivia::isLine).filter(comment -> comment.startsAfterEndOf(node));
    }

    /**
     * Recovers the line comment that trails {@code body} but that JavaParser parked as an orphan of {@code owner} rather
     * than as {@code body}'s own trivia.
     *
     * <p>This is the orphan-bucket sibling of {@link #trailingLineComment(Node)}, and exists for the same shape-independent
     * ownership reason. When a clause body's trailing {@code } // note} comment sits on the body's end line, JavaParser
     * attaches it to the body and {@link #trailingLineComment(Node)} recovers it. When a whitespace perturbation moves the
     * comment onto the line below the brace, JavaParser instead leaves it as an orphan of the enclosing construct (e.g. the
     * {@code try}). The AST is otherwise identical, so the comment still trails the same clause; this query keeps that
     * ownership by selecting the {@code owner} orphan line comments whose source position is after {@code body} ends and
     * before {@code nextStructural} begins (the next clause, or — when absent — open to the owner's end). Bounding by the
     * next structural element keeps each clause handoff claiming exactly its own slice instead of swallowing later clauses'
     * trailing comments.
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
     * Recovers the line comments that trail a variable/field {@code initializer} after its last token but before the
     * closing {@code ;}, which neither the initializer's own renderer nor the declarator's post-{@code ;} trailing slot
     * prints.
     *
     * <p>This is the after-initializer/before-{@code ;} sibling of the two trailing buckets that already cover a declared
     * initializer's comments, and exists because that slot is owned by neither. The initializer-contained renderer (e.g.
     * {@code BinaryExpressionPrinter.commentedBinaryLines}) only emits comments <em>between</em> operands — between
     * {@code ""} and the first {@code +}, or between two {@code +} operands — so it recovers a leading/inter-operand
     * comment but never one that begins after the whole initializer's last token, even when JavaParser attaches that
     * trailing comment to the last operand as contained trivia. The declarator's own trailing-line slot
     * ({@link #trailingLineComment(Node)}, consumed at the call site after the {@code ;}) only sees a comment JavaParser
     * attached to the declarator and positioned after it, which for a multi-line concatenation is the post-{@code ;}
     * comment, a different bucket. A {@code //} line that sits after the last {@code +} operand and before the closing
     * {@code ;} lands in neither: depending on how whitespace lays the operands out JavaParser parks it either as an orphan
     * of the {@code semicolonOwner} ({@link com.github.javaparser.ast.body.FieldDeclaration} or
     * {@link com.github.javaparser.ast.stmt.ExpressionStmt}) — the multi-line shape — or as the initializer's own contained
     * trivia on the last operand — the collapsed shape — so it is dropped both ways.
     *
     * <p>This query keeps that comment owned by the initializer tail by unioning both buckets and selecting only the line
     * comments whose source position is after {@code initializer} ends (see
     * {@link CommentIndex#startsAfterEndOf(Node, Comment)}), in source order. The {@code startsAfterEndOf} bound is what
     * makes the union safe: a leading/inter-operand comment such as the START line begins inside the initializer range, so
     * it is excluded from both halves and only the binary renderer prints it; the post-{@code ;} declarator comment is in
     * neither bucket. So this query can only ever add the genuinely-trailing comment that both existing slots drop, and the
     * claim-once wrapper renders it exactly once even if a future initializer renderer also reaches it.
     */
    List<JavaCommentTrivia> trailingInitializerCommentsBeforeSemicolon(Node semicolonOwner, Node initializer) {
        return java.util.stream.Stream.concat(
                orphanComments(semicolonOwner).stream(),
                containedComments(initializer).stream()
            )
                .filter(JavaCommentTrivia::isLine)
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
     * <p>This is the orphan-bucket sibling of the condition's own {@code getAllContainedComments()} leading comments, and
     * exists for the same shape-independent ownership reason as {@link #trailingLineCommentsAfter(Node, Node, Optional)}.
     * At the {@code @default} shape a {@code while ( // note value )} comment is contained trivia of the condition, so the
     * condition renderer already sees it and this query adds nothing (the control statement holds no such orphan). A
     * whitespace perturbation that re-shapes the condition re-buckets the same comment onto the enclosing control
     * statement as an orphan even though the AST is otherwise identical, so the contained-trivia view loses it and it is
     * dropped. This query keeps the comment owned by the condition by selecting the {@code controlStmt} orphan line
     * comments whose source position is after the control statement begins (so a comment leading the whole statement is
     * never claimed) and before {@code condition} begins.
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
     * <p>This is the trailing counterpart to {@link #leadingConditionComments(Node, Node)}. At the {@code @default} shape
     * an {@code if (cond) // note} comment is the condition's (or its last operand's) own trivia, so the close-paren
     * renderer recovers it directly. A whitespace perturbation that pushes the comment onto its own line below the
     * close-paren re-buckets it onto the enclosing {@code if}/{@code while}/{@code switch} as an orphan; the own-trivia
     * view then loses it. This query keeps the comment owned by the condition's tail by selecting the {@code controlStmt}
     * orphan line comments whose source position is after {@code condition} ends and before {@code body} begins. Bounding
     * by {@code body} keeps the close-paren tail from swallowing the body/then/else leading comments.
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
     * <p>This is the gap-ownership counterpart to {@link #adjacentLeadingLineComments(Node)} for a comment that lives
     * inside a single grammar slot — here, the {@code case label ->}/{@code body} arm of a switch rule. At the
     * {@code @default} shape JavaParser attaches a {@code case x -> // note body} comment to {@code body}, so the body's
     * own leading comment renders it; a whitespace perturbation re-buckets the same comment onto the case label
     * expression (collapse) or onto the switch entry as an orphan (expand) even though the AST is unchanged, so the
     * body-own slot no longer holds it and it is dropped. This query keeps the comment owned by the gap by selecting the
     * line comments from {@code attachmentBuckets} whose source position lies strictly after {@code afterNode} ends and
     * strictly before {@code body} begins (see {@link CommentIndex#liesBetween(Comment, Node, Node)}). It deliberately
     * excludes {@code body}'s own comment: the caller renders that one through the body statement renderer, so returning
     * it here would double-print it. The bucket scan is source-order and shape-independent, so re-pointing ownership here
     * does not move {@code @default} output, where the gap comment is the body's own trivia and is not in any bucket.
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

    private List<JavaCommentTrivia> ownAndOrphanComments(Node node) {
        return java.util.stream.Stream.concat(ownComment(node).stream(), orphanComments(node).stream()).toList();
    }

    /**
     * Returns the block comments JavaParser parked on the supplied {@code attachmentBuckets} that begin before
     * {@code boundary} in source order.
     *
     * <p>Inline case-label block comments ({@code case REMOTE /* remote *}{@code /, HYBRID}) attach to a label expression
     * as its own comment, or to the switch entry as an orphan, depending on layout. A caller that re-renders the label
     * list from its raw commented token text needs the exact set of comments that text already prints, so it can mark them
     * accounted without claiming any body comment that begins past {@code boundary} (the {@code ->}/{@code :} marker or the
     * body statement). The query is source-order, so it selects the same set however whitespace lays the labels out.
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
     * <p>This is the {@link com.github.javaparser.ast.stmt.LabeledStmt LabeledStmt} leading-comment counterpart of
     * {@link #gapLineCommentsBefore(Node, Node,
     * Collection)}. A labeled statement's leading comments (the lines between {@code loop:} and the nested {@code for}/
     * block) are reproduced verbatim at {@code @default} from the raw source slice between the {@code :} and the nested
     * statement (see {@code StatementPrinter.labeledStatementLeadingComments}), which preserves author blank-line groups
     * the AST cannot reconstruct. Under a whitespace collapse the same comments re-bucket onto the {@code LabeledStmt}
     * orphan pool, the label {@code SimpleName}'s own comment, the nested {@link com.github.javaparser.ast.stmt.ForEachStmt
     * ForEachStmt}'s own/orphan comments, and the single-line raw slice no longer exposes them as comment-only lines, so
     * they are dropped. This query recovers exactly those re-bucketed comments by source position.
     *
     * <p>Unlike {@link #gapLineCommentsBefore(Node, Node, Collection)} this query deliberately <em>keeps</em>
     * {@code body}'s own comment: a labeled empty {@code for (...) {}} body is rendered as flat text that never emits the
     * body node's own trivia, so the body-own slot is one of the buckets the leading comment can hide in. It also returns
     * block comments, since labeled leading comments are mixed line/block. The query never claims; the caller applies its
     * own raw-slice string dedupe before claiming each surviving comment by identity (which renders already-claimed
     * comments empty), so the {@code @default} shape — where every such comment is already produced by the raw slice — is
     * left byte-identical.
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
     * Finds line comments that sit in the source-order gap between two neighboring nodes inside {@code container}.
     *
     * <p>This is the gap-between-siblings <em>ownership</em> query: which line comments belong to the slot between
     * {@code previous} and {@code next} (call arguments, record components, resources, operands, …). It is deliberately
     * source-order based (see {@link CommentIndex#liesBetween(Comment, Node, Node)}) rather than keyed on the
     * {@code begin.line >= previous.end.line && begin.line < next.begin.line} source-line window the gap printers used
     * before: that window broke when a whitespace perturbation moved a {@code arg(), // note} trailing comment onto the
     * line below its argument even though the AST was unchanged, so the comment fell out of every sibling slot and was
     * dropped. The source-order test is a strict superset at the {@code @default} shape — a comment genuinely on the gap
     * lines selects the same owner under both — so re-pointing ownership here does not move {@code @default} output.
     * Callers still own how a recovered comment renders (inline {@code lineSuffix} when it trails a sibling on the
     * sibling's printed line versus on its own gap line); that rendering choice stays line-based at the call sites.
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
