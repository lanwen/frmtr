package dev.lanwen.frmtr.java;

import com.github.javaparser.Position;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.comments.Comment;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    private final Set<Comment> rawRendered = Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * The single slot each comment is allowed to render in, recorded once by the record-only dry-run pre-pass (see
     * {@link #beginRecording()} / {@link #endRecordingAndReset()}) before the real render offers a comment.
     *
     * <p>Keyed by JavaParser comment identity ({@link IdentityHashMap}), like {@link #rawRendered}: two structurally equal
     * comment nodes must stay distinct owners. A comment absent from this map has no recorded first-claimant, so
     * {@link #ownsHere} lets every slot offer it (first-claim-wins). The dry-run records the first claimant for
     * <em>every</em> family; {@link #ownsHere} then gates <em>every</em> family, so each comment renders only in the slot
     * that first offered it, and every suppressed non-owner offer renders {@link Doc#EMPTY}.
     */
    private final Map<Comment, OwnerKey> ownership = new IdentityHashMap<>();

    /**
     * Whether this tracker is in the record-only dry-run pass.
     *
     * <p>In record mode {@link #ownedComment(JavaCommentTrivia, Node, OwnerSlot)} mutates no real-pass state; instead
     * it records the first {@code (node, slot)} that offers each comment into {@link #ownership}, so candidate
     * ladders see the identical (first offer wins, later offers no-op) sequence in both passes. See
     * {@link #beginRecording()}.
     */
    private boolean recording = false;

    private final JavaCommentPlacementPolicy commentPlacement;

    CommentTracker(JavaCommentPlacementPolicy commentPlacement) {
        this.commentPlacement = commentPlacement;
    }

    /**
     * Enters the record-only dry-run pass that populates {@link #ownership} with each comment's first claimant.
     *
     * <p>Run once per format from {@link JavaPrinter#print(CompilationUnit)}, after the comment placement policy's run
     * has been started (so {@link JavaCommentPlacementPolicy} can answer queries) and before the real declaration
     * render. The dry-run runs the <em>same</em> print traversal as the real pass, but with {@link #recording} set:
     * every {@link #ownedComment(JavaCommentTrivia, Node, OwnerSlot)} offer records the offering {@code (node, slot)}
     * for a comment the first time it is offered and mutates no real-pass state, so the scratch document it produces
     * is discarded without affecting comment state. The first claimant in print-traversal order is the emergent owner
     * (a pure source-order rule diverges from it on the contested families), so reproducing it via the real traversal is
     * what keeps the ownership gate byte-neutral.
     *
     * <p>The dry-run records the first claimant for <em>every</em> family (trailing, leading, adjacent, own, orphan,
     * interleaved), and {@link #ownsHere} gates <em>all</em> of those families, so every comment renders only in its
     * recorded slot. Recording the owner via the real traversal is what keeps that gating byte-neutral.
     */
    void beginRecording() {
        recording = true;
    }

    /**
     * Leaves the dry-run pass and clears the per-render mutable state so the real render starts clean.
     *
     * <p>The {@link #ownership} map is intentionally <em>kept</em> — it is the dry-run's product. Everything that
     * accumulates <em>during</em> the print traversal must be reset so the real pass behaves exactly as a single render
     * would:
     * <ul>
     *   <li>the {@link #rawRendered} identity set (so the real pass re-accounts every raw-preserved comment
     *       itself);</li>
     *   <li>the {@link LayoutDecisionLog} (so {@code --explain} width arithmetic is not double-filled by the scratch
     *       pass);</li>
     *   <li>the {@link FormatterPragmas} enabled/disabled range state (so an {@code @formatter:off} range the dry-run
     *       left open does not start the real pass with formatting suppressed).</li>
     * </ul>
     *
     * <p>The placement policy and comment map are built once in
     * {@link JavaFormatContext#startCommentRun(CompilationUnit)}, and the policy's contained-comment / content-line
     * caches are pure AST-derived memoization whose values are identical in both passes, so they are deliberately
     * reused rather than reset — no re-parse and no recompute of those caches.
     */
    void endRecordingAndReset(LayoutDecisionLog layoutDecisions, FormatterPragmas formatterPragmas) {
        recording = false;
        rawRendered.clear();
        layoutDecisions.reset();
        formatterPragmas.reset();
    }

    /**
     * Reports whether {@code trivia} may render in {@code node}'s {@code slot} according to the ownership pre-pass.
     *
     * <p>A comment the pre-pass assigned to a specific slot renders only there: a non-owner offer returns {@code false}
     * and renders {@link Doc#EMPTY}. Every family consults this gate, so the only slot that offers a comment is the one
     * the dry-run recorded. A comment with no assignment ({@code owner == null}) is allowed in every slot, keeping
     * first-claim-wins behavior for any comment the dry-run never reached.
     */
    boolean ownsHere(JavaCommentTrivia trivia, Node node, OwnerSlot slot) {
        OwnerKey owner = ownership.get(trivia.comment());
        return owner == null || owner.equals(new OwnerKey(node, slot));
    }

    /**
     * Reports whether {@code trivia} is recorded to an owner that lies <em>outside</em> {@code subtree} — an enclosing or
     * sibling slot will render it, so a layout local to {@code subtree} need not break itself to keep the comment.
     *
     * <p>Returns {@code false} for an unclaimed comment and for one whose owner is {@code subtree} or a node within it, so
     * a comment a local layout must still place keeps its comment-aware shape.
     */
    boolean claimedOutside(JavaCommentTrivia trivia, Node subtree) {
        OwnerKey owner = ownership.get(trivia.comment());
        if (owner == null) {
            return false;
        }
        for (Node node = owner.anchor(); node != null; node = node.getParentNode().orElse(null)) {
            if (node == subtree) {
                return false;
            }
        }
        return true;
    }

    Doc leading(Node node) {
        return commentPlacement.leadingComment(node)
                .map(t -> ownedComment(t, node, OwnerSlot.LEADING))
                .filter(doc -> doc != Doc.EMPTY)
                .map(doc -> Doc.concat(doc, Doc.HARD_LINE))
                .orElse(Doc.EMPTY);
    }

    Doc adjacentLeadingLineComments(Node node) {
        return Doc.concat(
            commentPlacement.adjacentLeadingLineComments(node)
                    .stream()
                    .map(t -> ownedComment(t, node, OwnerSlot.ADJACENT_LEADING))
                    .filter(doc -> doc != Doc.EMPTY)
                    .map(doc -> Doc.concat(doc, Doc.HARD_LINE))
                    .toList()
        );
    }

    Doc leadingCluster(Node node) {
        return Doc.concat(adjacentLeadingLineComments(node), leading(node));
    }

    /**
     * Renders {@code node}'s own trailing {@code //} line comment, anchored to the outer {@link OwnerSlot#TRAILING} slot
     * and routed through the claim-neutral {@link #ownedComment} rail.
     *
     * <p>Emptiness is decided by the ownership pre-pass, not by a build-time claim: this returns the comment's Doc when
     * {@code (node, TRAILING)} is the recorded owner and {@link Doc#EMPTY} otherwise, and it never mutates any per-render
     * claim state. That is what lets a co-offering content path ({@link #contentTrailingLineComment}) or an enclosing
     * construct disambiguate by ownership instead of reading a build-order claim side effect: whichever path the
     * dry-run recorded as the first offerer owns the comment, and every other slot renders empty. Because it is
     * claim-neutral, an owner may emit this same Doc in more than one eagerly-built ranked layout arm without dropping
     * or duplicating the comment.
     */
    Doc trailingLineComment(Node node) {
        return commentPlacement.trailingLineComment(node)
                .map(trivia -> ownedComment(trivia, node, OwnerSlot.TRAILING))
                .orElse(Doc.EMPTY);
    }

    /**
     * Renders {@code node}'s own trailing {@code //} line comment under the distinct {@link OwnerSlot#CONTENT_TRAILING}
     * slot, for a content renderer that must position the comment itself rather than let the outer
     * {@link StatementRuleEnvelope} append it.
     *
     * <p>Same comment, same claim-neutral rail as {@link #trailingLineComment(Node)}, but a different {@link OwnerKey} on
     * the same anchor node so the two paths never collide. An {@code if}/{@code else} layout that renders a nested body's
     * trailing comment before the {@code else} keyword offers here first (before the nested statement's own envelope
     * offers under {@link OwnerSlot#TRAILING}), so the dry-run records this slot as the owner and the envelope offer
     * renders empty. For a plain expression statement the envelope offers first, so this content offer renders empty and
     * the envelope keeps the comment — reproducing today's first-claim-wins winner in both shapes without reading any
     * build-order claim side effect.
     */
    Doc contentTrailingLineComment(Node node) {
        return commentPlacement.trailingLineComment(node)
                .map(trivia -> ownedComment(trivia, node, OwnerSlot.CONTENT_TRAILING))
                .orElse(Doc.EMPTY);
    }

    /**
     * Renders {@code node}'s own trailing {@code //} line comment under the distinct {@link OwnerSlot#ENCLOSED_TRAILING}
     * slot, for an <em>enclosing construct</em> that positions the nested statement's comment in a spot only it controls
     * (an {@code if}/{@code else} chain placing the then-body's comment before the {@code else} keyword).
     *
     * <p>Same comment, same claim-neutral rail as {@link #trailingLineComment(Node)} and
     * {@link #contentTrailingLineComment(Node)}, but a third {@link OwnerKey} on the same anchor node so all three paths
     * stay distinct when a branch body is an expression statement and all three fire for it. The enclosing construct
     * offers here first (before the nested body is rendered), so the dry-run records this slot as the owner and both the
     * nested statement's own envelope ({@link OwnerSlot#TRAILING}) and its own content offer
     * ({@link OwnerSlot#CONTENT_TRAILING}) render empty by ownership — reproducing today's first-claim-wins winner (the
     * enclosing layout) without reading any build-order claim side effect.
     */
    Doc enclosedTrailingLineComment(Node node) {
        return commentPlacement.trailingLineComment(node)
                .map(trivia -> ownedComment(trivia, node, OwnerSlot.ENCLOSED_TRAILING))
                .orElse(Doc.EMPTY);
    }

    /**
     * Renders {@code node}'s trailing comment(s) — its own {@code //} line comment and any same-line trailing block
     * comment — anchored to the single stable {@code (node, }{@link OwnerSlot#TRAILING}{@code )} slot and routed through
     * the claim-neutral {@link #ownedComment} rail.
     *
     * <p>This is the layout-independent trailing-comment anchor. Because both kinds resolve to one fixed
     * {@code (node, TRAILING)} slot rendered through the pure rail, a later cutover can emit this same Doc in every
     * ranked layout arm (each alternative of a {@link Doc#bestFitting}/{@link Doc#conditionalGroup}) without any arm
     * dropping or duplicating the comment: emptiness is decided by the recorded owner, not by which arm claims first.
     *
     * <p>The two kinds keep their existing, deliberately different width behavior so a later cutover does not shift
     * measured layout widths. A {@code //} line comment is deferred as a {@link Doc#lineSuffix} — it flushes at the next
     * line break and measures zero flat width, so the code it trails is laid out as if the comment were absent and can
     * never be pushed over the line width by it. An inline block comment renders as its bare {@link Doc#text} content —
     * it sits on the line and counts toward width, matching the {@code CommentPlacement} block seam. The separating
     * space rides inside the width-free line-suffix; the block's inter-token spacing stays a caller concern, as today.
     *
     * <p>Callers still decide where in the surrounding layout this Doc is concatenated. Currently unused: no call site
     * emits it yet.
     */
    Doc trailingComment(Node node) {
        List<Doc> parts = new ArrayList<>();
        commentPlacement.trailingLineComment(node)
                .map(trivia -> trailingLineCommentSuffix(trivia, node))
                .filter(doc -> doc != Doc.EMPTY)
                .ifPresent(parts::add);
        commentPlacement.unattachedTrailingBlockComment(node)
                .map(trivia -> ownedComment(trivia, node, OwnerSlot.TRAILING))
                .filter(doc -> doc != Doc.EMPTY)
                .ifPresent(parts::add);
        return Doc.concat(parts);
    }

    /**
     * Defers an owned trailing {@code //} line comment past the current line as a width-free {@link Doc#lineSuffix},
     * carrying its separating space inside the suffix, or returns {@link Doc#EMPTY} when this slot does not own it.
     */
    private Doc trailingLineCommentSuffix(JavaCommentTrivia trivia, Node node) {
        Doc rendered = ownedComment(trivia, node, OwnerSlot.TRAILING);
        return rendered == Doc.EMPTY
            ? Doc.EMPTY
            : Doc.lineSuffix(Doc.concat(Doc.text(" "), rendered));
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
                    .map(t -> ownedComment(t, owner, OwnerSlot.TRAILING))
                    .filter(doc -> doc != Doc.EMPTY)
                    .toList()
        );
    }

    /**
     * Claims and renders the {@code //} line comments that trail {@code body} but were parked as an orphan of
     * {@code owner}, as one {@link Doc#HARD_LINE}-separated block rather than a single concatenated line.
     *
     * <p>This is the multi-line sibling of {@link #trailingLineCommentsAfter(Node, Node, java.util.Optional)}: that
     * method {@link Doc#concat}s each recovered {@code //} line with no separator, which is correct for a single
     * recovered comment but fuses a multi-line block ({@code // a}/{@code // b}) onto one physical line ({@code // a// b}).
     * A multi-line comment written between two {@code catch} clauses is parked as a run of {@code TryStmt} orphans and
     * handed into the following clause body, so each source line must stay on its own line. The lines are already
     * source-ordered by
     * {@link JavaCommentPlacementPolicy#trailingLineCommentsAfter(Node, Node, java.util.Optional)}; this method only
     * changes how they are joined, so a single recovered line renders byte-identically to the concatenating sibling.
     */
    Doc trailingLineCommentBlockAfter(Node owner, Node body, java.util.Optional<? extends Node> nextStructural) {
        return Doc.join(
            Doc.HARD_LINE,
            commentPlacement.trailingLineCommentsAfter(owner, body, nextStructural)
                    .stream()
                    .map(t -> ownedComment(t, owner, OwnerSlot.TRAILING))
                    .filter(doc -> doc != Doc.EMPTY)
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
                .map(t -> ownedComment(t, semicolonOwner, OwnerSlot.TRAILING))
                .filter(doc -> doc != Doc.EMPTY)
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
                .map(t -> ownedComment(t, body, OwnerSlot.LEADING))
                .filter(doc -> doc != Doc.EMPTY)
                .toList();
    }

    /**
     * Claims and renders the full {@code //} comment block between {@code afterNode} and {@code body} as one cluster,
     * each line on its own source-ordered line, anchored to {@code anchor}'s {@link OwnerSlot#LEADING} slot.
     *
     * <p>This is the {@code else}/{@code else if} counterpart of {@link #gapLineCommentsBefore(Node, Node, Collection)}.
     * A multi-line block written before {@code else}/{@code else if} is split by JavaParser across {@code body}'s own
     * trivia and the enclosing {@code if}'s orphan pool; this renderer claims every line under the enclosing-if
     * {@code anchor} so the block renders once, together, in a single deterministic spot. Anchoring to the enclosing
     * {@code if} rather than to {@code body} keeps a distinct {@link OwnerKey} from the nested {@code else if} body's own
     * leading slot ({@code (body, LEADING)}): the dry-run runs this offer first (the separator is emitted before the
     * nested body), so the block owns the lines here and the nested {@code else if}'s own leading cluster sees a
     * different recorded owner and renders empty instead of double-claiming. See
     * {@link JavaCommentPlacementPolicy#gapLeadingLineCommentBlock(Node, Node, Collection)}.
     */
    Doc gapLeadingLineCommentBlock(
            Node anchor,
            Node afterNode,
            Node body,
            Collection<? extends Node> attachmentBuckets
    ) {
        return gapLeadingLineCommentBlock(anchor, afterNode, body, attachmentBuckets, Optional.empty());
    }

    /**
     * Bounded variant of {@link #gapLeadingLineCommentBlock(Node, Node, Node, Collection)} that, when
     * {@code upperBound} is present, restricts the recovered block to the line comments that start strictly before that
     * source position.
     *
     * <p>The bound exists for the {@code else} keyword: a braceless else body's own leading {@code //} block sits after
     * the keyword and is owned by the braceless-body handler, while only the genuine then-{@code }}-to-{@code else}
     * separator comment (before the keyword) belongs to this slot. With the bound absent this behaves exactly like the
     * unbounded overload, so {@code else if} and block-else placement is unchanged. Bounding here, before
     * {@link #ownedComment}, is what keeps the body block eligible under its own slot — an out-of-bound comment is
     * never offered under {@code anchor}.
     */
    Doc gapLeadingLineCommentBlock(
            Node anchor,
            Node afterNode,
            Node body,
            Collection<? extends Node> attachmentBuckets,
            Optional<Position> upperBound
    ) {
        List<Doc> rendered = new ArrayList<>();
        for (JavaCommentTrivia trivia : commentPlacement.gapLeadingLineCommentBlock(afterNode, body, attachmentBuckets)) {
            if (upperBound.map(position -> !CommentIndex.startsBefore(trivia.comment(), position)).orElse(false)) {
                continue;
            }
            Doc doc = ownedComment(trivia, anchor, OwnerSlot.LEADING);
            if (doc != Doc.EMPTY) {
                if (!rendered.isEmpty()) {
                    rendered.add(Doc.HARD_LINE);
                }
                rendered.add(doc);
            }
        }
        return Doc.concat(rendered);
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
                .map(t -> ownedComment(t, boundary, OwnerSlot.ORPHAN))
                .filter(doc -> doc != Doc.EMPTY)
                .toList();
    }

    Doc orphanComments(Node node, Predicate<Comment> predicate) {
        return Doc.concat(
            commentPlacement.orphanComments(node)
                    .stream()
                    .filter(trivia -> predicate.test(trivia.comment()))
                    .map(t -> ownedComment(t, node, OwnerSlot.ORPHAN))
                    .filter(doc -> doc != Doc.EMPTY)
                    .map(doc -> Doc.concat(doc, Doc.HARD_LINE))
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
                .map(t -> ownedComment(t, node, OwnerSlot.ORPHAN))
                .filter(doc -> doc != Doc.EMPTY)
                .toList();
    }

    Doc orphanCommentsBeforeLine(Node node, int line) {
        return orphanComments(node, commentPlacement.orphanCommentsBeforeLine(node, line));
    }

    Doc orphanCommentsAfterLine(Node node, int line) {
        return orphanComments(node, commentPlacement.orphanCommentsAfterLine(node, line));
    }

    private Doc orphanComments(Node node, List<JavaCommentTrivia> comments) {
        return Doc.concat(
            comments.stream()
                    .map(t -> ownedComment(t, node, OwnerSlot.ORPHAN))
                    .filter(doc -> doc != Doc.EMPTY)
                    .map(doc -> Doc.concat(doc, Doc.HARD_LINE))
                    .toList()
        );
    }

    Doc ownComment(Node node, Predicate<Comment> predicate) {
        return commentPlacement.ownComment(node)
                .filter(trivia -> predicate.test(trivia.comment()))
                .map(t -> ownedComment(t, node, OwnerSlot.OWN))
                .filter(doc -> doc != Doc.EMPTY)
                .orElse(Doc.EMPTY);
    }

    Doc ownTriviaComment(Node node, Predicate<JavaCommentTrivia> predicate) {
        return commentPlacement.ownComment(node)
                .filter(predicate)
                .map(t -> ownedComment(t, node, OwnerSlot.OWN))
                .filter(doc -> doc != Doc.EMPTY)
                .orElse(Doc.EMPTY);
    }

    /**
     * Renders {@code comment} as a comment the interleaver merged between an anchor's children by source position,
     * routed through the claim-neutral {@link #ownedComment} rail.
     *
     * <p>This overload exists so the source-order interleaver and the printers that hand a comment directly to the
     * tracker can name the anchor node and {@link OwnerSlot} that offers the comment, which is what the record-only
     * dry-run needs to record the first claimant. {@code anchor} is the node whose layout encloses the comment (the
     * interleaved owner, or the printer's own node); {@code slot} is the role under which it is offered.
     *
     * <p>Like every other comment render family, this delegates to {@link #ownedComment}, so emptiness is a pure function
     * of the recorded owner and no {@code comment(...)} render mutates any claim state. That makes the whole
     * {@code comment(...)} family claim-neutral, so an owner may emit the
     * same comment Doc in more than one eagerly-built ranked layout arm (a {@link Doc#conditionalGroup} /
     * {@link Doc#bestFitting} alternative) without dropping or duplicating it: the renderer emits only the arm it picks,
     * and every non-owner {@code (node, slot)} offer renders {@link Doc#EMPTY}. Co-offering paths that must not share an
     * owner therefore disambiguate by giving each a distinct {@code (node, slot)}, exactly as the other migrated
     * families do, rather than by reading a build-order claim side effect.
     */
    Doc comment(JavaCommentTrivia trivia, Node anchor, OwnerSlot slot) {
        return ownedComment(trivia, anchor, slot);
    }

    Doc comment(Comment comment, Node anchor, OwnerSlot slot) {
        return comment(JavaCommentTrivia.from(comment), anchor, slot);
    }

    /**
     * Renders {@code comment} through the claim-neutral rail when no anchor node is available to name; defaults to
     * recording the offering node as the comment itself under {@link OwnerSlot#INTERLEAVED}.
     *
     * <p>Kept for the direct {@code comment(...)} call sites that do not (yet) thread an enclosing owner. The recorded
     * anchor here is the comment node itself, so the dry-run records {@code (comment, INTERLEAVED)} and the real pass
     * gates against that same key — record-slot equals enforce-slot, so the comment still renders from this path. Call
     * sites that can name a real enclosing owner thread it through the {@link #comment(JavaCommentTrivia, Node,
     * OwnerSlot)} overload instead.
     */
    Doc comment(Comment comment) {
        return comment(JavaCommentTrivia.from(comment));
    }

    Doc comment(JavaCommentTrivia trivia) {
        return comment(trivia, trivia.comment(), OwnerSlot.INTERLEAVED);
    }

    /**
     * Renders {@code trivia} as pure content when {@code (node, slot)} owns it — the claim-neutral render entry point
     * ("comments as pure content") that every comment-render family shares.
     *
     * <p>Emptiness is a pure function of the recorded ownership: this returns
     * {@link JavaFormatter#commentDoc(JavaCommentTrivia)} exactly when {@link #ownsHere} admits this slot and
     * {@link Doc#EMPTY} otherwise, so a non-owner slot renders nothing. In the record-only dry-run this also records
     * the first claimant into {@link #ownership} (via {@code putIfAbsent}), which is how the ownership pre-pass gets
     * populated; in the real pass it mutates <em>nothing</em> — there is no per-render claim state to touch. An
     * owner may therefore render the same comment through this rail any number of times in one real pass (once per
     * eagerly-built ranked arm, say) and always get the same non-empty Doc back, with no duplicate-claim throw.
     *
     * <p>{@link #comment} is a thin overload of this same rail; every other comment-render family — leading, trailing,
     * orphan, gap-recovery — is built on it too, so this is the single method that decides whether any comment renders
     * at all.
     *
     * <p>Callers still decide which {@code (node, slot)} anchors a comment and how the returned Doc is laid out (inline
     * versus deferred, spacing, ordering).
     */
    Doc ownedComment(JavaCommentTrivia trivia, Node node, OwnerSlot slot) {
        if (!ownsHere(trivia, node, slot)) {
            return Doc.EMPTY;
        }
        if (recording) {
            ownership.putIfAbsent(trivia.comment(), new OwnerKey(node, slot));
        }
        return JavaFormatter.commentDoc(trivia);
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
     * Computes the comment set the drop guardrail uses: a comment is accounted when the ownership pre-pass recorded a
     * render slot for it ({@link #ownership}) or it reached output through raw-preserved source ({@link #rawRendered}).
     *
     * <p>This is the accounting basis {@link #assertAllCommentsAccounted} feeds to
     * {@link FormatterGuardrails#assertAllCommentsAccounted}: "accounted" means "has a recorded owner", not "was
     * claimed" — every comment family renders through the claim-neutral {@link #ownedComment} rail, and that rail
     * never mutates any per-render claim state, so a recorded owner is the only signal accounting has left to use.
     *
     * <p>Returns a fresh identity set (comments are compared by identity here, like every other comment set), so
     * mutating the result does not affect the tracker.
     */
    Set<Comment> ownershipAccountedComments() {
        Set<Comment> accounted = Collections.newSetFromMap(new IdentityHashMap<>());
        accounted.addAll(ownership.keySet());
        accounted.addAll(rawRendered);
        return accounted;
    }

    /**
     * Fails in debug mode when JavaParser exposed a comment that was neither owned by the render pass nor deliberately
     * raw-preserved.
     *
     * <p>This is a development-only finalization check for one compilation-unit print. {@link FormatterGuardrails#enabled()}
     * controls whether any assertion is evaluated, so normal formatter runs stay best-effort and evaluate none.
     *
     * <p>Accounting is keyed on {@link #ownershipAccountedComments()} (the dry-run's recorded owners plus the
     * raw-rendered set): every render family renders through the claim-neutral {@link #ownedComment} rail, so a
     * recorded owner is the only signal that a comment reached output through the structured render path. The
     * authoritative data-loss witness stays the output-level {@code CommentPresenceDiagnosticTest} lexer multiset,
     * which is independent of this accounting basis.
     */
    void assertAllCommentsAccounted(CompilationUnit unit) {
        FormatterGuardrails.assertAllCommentsAccounted(unit, ownershipAccountedComments(), rawRendered);
    }
}
