package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.comments.Comment;
import dev.lanwen.frmtr.doc.Doc;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Tracks which JavaParser comments have already been consumed during one Java formatting run.
 *
 * <p>This helper owns stateful comment accounting: identity-based comment claims, leading and trailing attached comment
 * consumption, orphan-comment consumption, and the raw-preserved comment marks supplied by {@link RawPreservedSource}.
 * The boundary exists so comment consumption is centralized outside the parser/configuration entrypoint while printers
 * still share one "print once" state for a source file.
 *
 * <p>Callers still decide syntax-specific placement, spacing, ordering around neighboring nodes, and whether a raw
 * {@link Comment} predicate or classified {@link JavaCommentTrivia} predicate best describes the local layout rule.
 * Comment text rendering stays with {@link JavaFormatter#commentDoc(JavaCommentTrivia)} so this helper only accounts for
 * consumption and does not introduce new comment rendering policy.
 */
final class CommentTracker {

    private final Set<Comment> printed = Collections.newSetFromMap(new IdentityHashMap<>());

    private final Set<Comment> rawRendered = Collections.newSetFromMap(new IdentityHashMap<>());

    private final JavaCommentPlacementPolicy commentPlacement;

    CommentTracker(JavaCommentPlacementPolicy commentPlacement) {
        this.commentPlacement = commentPlacement;
    }

    Doc leading(Node node) {
        return commentPlacement.leadingComment(node)
                .filter(this::claim)
                .map(JavaFormatter::commentDoc)
                .map(doc -> Doc.concat(doc, Doc.HARD_LINE))
                .orElse(Doc.EMPTY);
    }

    Doc adjacentLeadingLineComments(Node node) {
        return Doc.concat(
            commentPlacement.adjacentLeadingLineComments(node)
                    .stream()
                    .filter(this::claim)
                    .map(JavaFormatter::commentDoc)
                    .map(doc -> Doc.concat(doc, Doc.HARD_LINE))
                    .toList()
        );
    }

    Doc leadingCluster(Node node) {
        return Doc.concat(adjacentLeadingLineComments(node), leading(node));
    }

    Doc trailingLineComment(Node node) {
        return commentPlacement.trailingLineComment(node)
                .filter(this::claim)
                .map(JavaFormatter::commentDoc)
                .orElse(Doc.EMPTY);
    }

    /**
     * Claims and renders the line comments that trail {@code body} but were parked as an orphan of {@code owner}.
     *
     * <p>This is the orphan-bucket counterpart of {@link #trailingLineComment(Node)}: the try-clause handoff uses it to
     * recover a clause body's trailing comment when a whitespace shape moved it off the brace line, so JavaParser left it
     * as a {@code try}-statement orphan instead of the body's own trivia. See
     * {@link JavaCommentPlacementPolicy#trailingLineCommentsAfter(Node, Node, java.util.Optional)}.
     */
    Doc trailingLineCommentsAfter(Node owner, Node body, java.util.Optional<? extends Node> nextStructural) {
        return Doc.concat(
            commentPlacement.trailingLineCommentsAfter(owner, body, nextStructural)
                    .stream()
                    .filter(this::claim)
                    .map(JavaFormatter::commentDoc)
                    .toList()
        );
    }

    /**
     * Claims and renders the line comments that trail {@code initializer} after its last token but before the closing
     * {@code ;}, which JavaParser parked as an orphan of {@code semicolonOwner}.
     *
     * <p>This is the after-initializer/before-{@code ;} counterpart of {@link #trailingLineComment(Node)} (the declarator's
     * post-{@code ;} own trailing slot) and of the initializer-contained comment recovery (e.g. the binary printer's
     * between-operand lines). Neither of those buckets owns a {@code //} line that begins after a multi-line concatenation
     * initializer's last operand and before the {@code ;}, so this wrapper recovers exactly that slice and claims each
     * comment once so it is never double-printed. See
     * {@link JavaCommentPlacementPolicy#trailingInitializerCommentsBeforeSemicolon(Node, Node)}.
     */
    List<Doc> trailingInitializerCommentsBeforeSemicolon(Node semicolonOwner, Node initializer) {
        return commentPlacement.trailingInitializerCommentsBeforeSemicolon(semicolonOwner, initializer)
                .stream()
                .filter(this::claim)
                .map(JavaFormatter::commentDoc)
                .toList();
    }

    /**
     * Claims and renders the line comments that sit between {@code afterNode} and {@code body} but that JavaParser parked
     * on one of {@code attachmentBuckets} instead of as {@code body}'s own leading trivia.
     *
     * <p>This is the switch-rule arm counterpart of {@link #trailingLineCommentsAfter(Node, Node, java.util.Optional)}: it
     * recovers a {@code case x -> // note body} comment when a whitespace shape moved it off the body and onto the case
     * label expression or the entry orphan bucket. See
     * {@link JavaCommentPlacementPolicy#gapLineCommentsBefore(Node, Node, Collection)}.
     */
    List<Doc> gapLineCommentsBefore(Node afterNode, Node body, Collection<? extends Node> attachmentBuckets) {
        return commentPlacement.gapLineCommentsBefore(afterNode, body, attachmentBuckets)
                .stream()
                .filter(this::claim)
                .map(JavaFormatter::commentDoc)
                .toList();
    }

    /**
     * Claims and renders the block comments that begin before {@code boundary} but that JavaParser parked on one of
     * {@code attachmentBuckets} instead of as the node that originally owned them.
     *
     * <p>This is the orphan-bucket counterpart to a node's own leading block comment, in the same source-order ownership
     * shape as {@link #gapLineCommentsBefore(Node, Node, Collection)} and
     * {@link #trailingLineCommentsAfter(Node, Node, java.util.Optional)}. A {@code finally}/{@code while}/array-element
     * leading or trailing block comment is the owning node's own trivia at {@code @default}, so the owner's own-comment
     * path renders it and this query is only consulted on the empty-own fallback; under a whitespace perturbation the same
     * comment re-buckets onto the enclosing statement orphan pool, where this query recovers it. Already-claimed comments
     * render as {@link Doc#EMPTY} so an earlier clause that consumed a shared block comment (e.g. a {@code catch} prefix)
     * is not double-printed. See {@link JavaCommentPlacementPolicy#blockCommentsBefore(Collection, Node)}.
     */
    List<Doc> blockCommentsBefore(Collection<? extends Node> attachmentBuckets, Node boundary) {
        return commentPlacement.blockCommentsBefore(attachmentBuckets, boundary)
                .stream()
                .filter(this::claim)
                .map(JavaFormatter::commentDoc)
                .toList();
    }

    Doc orphanComments(Node node) {
        return orphanComments(node, ignored -> true);
    }

    Doc orphanComments(Node node, Predicate<Comment> predicate) {
        return Doc.concat(
            commentPlacement.orphanComments(node)
                    .stream()
                    .filter(trivia -> predicate.test(trivia.comment()))
                    .filter(this::claim)
                    .map(comment -> Doc.concat(JavaFormatter.commentDoc(comment), Doc.HARD_LINE))
                    .toList()
        );
    }

    List<Doc> orphanCommentStatements(Node node) {
        return orphanCommentStatements(node, ignored -> true);
    }

    List<Doc> orphanCommentStatements(Node node, Predicate<Comment> predicate) {
        return commentPlacement.orphanComments(node)
                .stream()
                .filter(trivia -> predicate.test(trivia.comment()))
                .filter(this::claim)
                .map(JavaFormatter::commentDoc)
                .toList();
    }

    List<Doc> orphanTriviaCommentStatements(Node node, Predicate<JavaCommentTrivia> predicate) {
        return commentPlacement.orphanComments(node)
                .stream()
                .filter(predicate)
                .filter(this::claim)
                .map(JavaFormatter::commentDoc)
                .toList();
    }

    Doc orphanCommentsBeforeLine(Node node, int line) {
        return orphanComments(commentPlacement.orphanCommentsBeforeLine(node, line));
    }

    Doc orphanCommentsAfterLine(Node node, int line) {
        return orphanComments(commentPlacement.orphanCommentsAfterLine(node, line));
    }

    private Doc orphanComments(List<JavaCommentTrivia> comments) {
        return Doc.concat(
            comments.stream()
                    .filter(this::claim)
                    .map(comment -> Doc.concat(JavaFormatter.commentDoc(comment), Doc.HARD_LINE))
                    .toList()
        );
    }

    Doc ownComment(Node node, Predicate<Comment> predicate) {
        return commentPlacement.ownComment(node)
                .filter(trivia -> predicate.test(trivia.comment()))
                .filter(this::claim)
                .map(JavaFormatter::commentDoc)
                .orElse(Doc.EMPTY);
    }

    Doc ownTriviaComment(Node node, Predicate<JavaCommentTrivia> predicate) {
        return commentPlacement.ownComment(node)
                .filter(predicate)
                .filter(this::claim)
                .map(JavaFormatter::commentDoc)
                .orElse(Doc.EMPTY);
    }

    Doc comment(Comment comment) {
        return comment(JavaCommentTrivia.from(comment));
    }

    Doc comment(JavaCommentTrivia trivia) {
        return claim(trivia) ? JavaFormatter.commentDoc(trivia) : Doc.EMPTY;
    }

    private boolean claim(JavaCommentTrivia trivia) {
        return FormatterGuardrails.claimComment(trivia, printed);
    }

    boolean isPrinted(JavaCommentTrivia trivia) {
        return trivia.isClaimedBy(printed);
    }

    /**
     * Records comments that {@link RawPreservedSource} intentionally preserved inside raw source text.
     *
     * <p>The canonical raw-preservation helper calls this while building the output {@link Doc}, keeping raw comment
     * accounting behind one output boundary instead of making each raw fallback remember a separate side effect.
     */
    void accountRaw(Node node) {
        FormatterGuardrails.accountRawComments(node, rawRendered);
    }

    /**
     * Records source-region-selected comments that reached output through raw-preserved source text.
     *
     * <p>{@link RecoveredSourceRegions} uses this after it has already checked offset boundaries. The tracker still
     * owns the raw-rendered identity set, while recovery owns deciding which comments are fully contained by a raw
     * source island.
     */
    void accountRaw(Collection<? extends Comment> comments) {
        FormatterGuardrails.accountRawComments(comments, rawRendered);
    }

    /**
     * Records raw-rendered comments after the node's own attached comment has been printed separately.
     *
     * <p>{@link RawPreservedSource} uses this for fallbacks that already emitted the node's own comment through normal
     * attached-comment accounting. Nested and orphan comments inside the raw span remain represented only by the
     * recovered source text, so they are raw-accounted here without also counting the already printed own comment.
     */
    void accountRawWithoutOwnComment(Node node) {
        FormatterGuardrails.accountRawCommentsWithoutOwnComment(node, rawRendered);
    }

    /**
     * Fails in debug mode when JavaParser exposed a comment that was neither printed nor deliberately raw-preserved.
     *
     * <p>This is a development-only finalization check for one compilation-unit print. Normal formatter runs leave the
     * legacy best-effort behavior unchanged because {@link FormatterGuardrails#enabled()} controls whether any assertion
     * is evaluated.
     */
    void assertAllCommentsAccounted(CompilationUnit unit) {
        FormatterGuardrails.assertAllCommentsAccounted(unit, printed, rawRendered);
    }
}
