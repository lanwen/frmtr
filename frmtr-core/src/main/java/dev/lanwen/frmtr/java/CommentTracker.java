package dev.lanwen.frmtr.java;

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

    private final Set<Comment> printed = Collections.newSetFromMap(new IdentityHashMap<>());

    private final Set<Comment> rawRendered = Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * The single slot each comment is allowed to render in, recorded once by the record-only dry-run pre-pass (see
     * {@link #beginRecording()} / {@link #endRecordingAndReset()}) before the real render claims a comment.
     *
     * <p>Keyed by JavaParser comment identity ({@link IdentityHashMap}), like {@link #printed}: two structurally equal
     * comment nodes must stay distinct owners. A comment absent from this map is <em>unmigrated</em> — no first-claimant
     * was recorded for it, so {@link #ownsHere} lets every slot offer it and today's first-claim-wins behavior is
     * preserved. Stage 2a records the first claimant for <em>every</em> family during the dry-run; Stage 2bc then gates
     * <em>every</em> family by {@link #ownsHere}, so only the recorded first claimant offers each comment. Output stays
     * byte-identical because the recorded owner is the same forward-traversal first-claimant that wins the claim race
     * today, and every suppressed non-owner offer already rendered {@link Doc#EMPTY}.
     */
    private final Map<Comment, OwnerKey> ownership = new IdentityHashMap<>();

    /**
     * Whether this tracker is in the record-only dry-run pass.
     *
     * <p>In record mode {@link #claim(JavaCommentTrivia, Node, OwnerSlot)} does not consume the real {@link #printed}
     * set; instead it records the first {@code (node, slot)} that offers each comment into {@link #ownership} and
     * returns the claim outcome from the {@code ownership} map itself, so candidate ladders see the identical
     * (first offer wins, later offers lose) boolean sequence they see in the real pass. See {@link #beginRecording()}.
     */
    private boolean recording = false;

    private final JavaCommentPlacementPolicy commentPlacement;

    /**
     * The width-decision log and pragma range state are the two per-render side channels (besides the claim sets) that
     * a print traversal mutates. {@link #speculatively} must roll them back together with the claim state when a
     * discarded probe is abandoned, so the tracker holds them to snapshot/restore in lockstep — the same channels
     * {@link #endRecordingAndReset} resets between the dry-run and the real pass.
     */
    private final LayoutDecisionLog layoutDecisions;

    private final FormatterPragmas formatterPragmas;

    CommentTracker(
            JavaCommentPlacementPolicy commentPlacement,
            LayoutDecisionLog layoutDecisions,
            FormatterPragmas formatterPragmas
    ) {
        this.commentPlacement = commentPlacement;
        this.layoutDecisions = layoutDecisions;
        this.formatterPragmas = formatterPragmas;
    }

    /**
     * Builds a tracker that owns its own width-decision log and pragma state, for callers (mainly unit tests) that only
     * exercise the claim/account paths and never run the speculative scope or the dry-run reset against a shared context.
     */
    CommentTracker(JavaCommentPlacementPolicy commentPlacement) {
        this(commentPlacement, new LayoutDecisionLog(), new FormatterPragmas());
    }

    /**
     * Enters the record-only dry-run pass that populates {@link #ownership} with each comment's first claimant.
     *
     * <p>Run once per format from {@link JavaPrinter#print(CompilationUnit)}, after the comment placement policy's run
     * has been started (so {@link JavaCommentPlacementPolicy} can answer queries) and before the real declaration
     * render. The dry-run runs the <em>same</em> print traversal as the real pass, but with {@link #recording} set:
     * every {@link #claim(JavaCommentTrivia, Node, OwnerSlot)} records the offering {@code (node, slot)} for a comment
     * the first time it is offered and skips the real {@link #printed} accounting, so the scratch document it produces
     * is discarded without affecting comment state. The first claimant in print-traversal order is the emergent owner a
     * pure source-order rule provably diverges from on the contested families, so reproducing it via the real traversal
     * is what keeps a later filter byte-neutral.
     *
     * <p>Stage 2a records the first claimant for <em>every</em> family (trailing, leading, adjacent, own, orphan,
     * interleaved). Stage 2bc consults {@link #ownsHere} for <em>all</em> of those families, so every comment renders
     * only in its recorded slot. Recording the dry-run owner via the real traversal is what keeps that gating
     * byte-neutral.
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
     *   <li>the {@link #printed} and {@link #rawRendered} identity sets (so the real pass re-claims and re-accounts
     *       every comment itself);</li>
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
        printed.clear();
        rawRendered.clear();
        layoutDecisions.reset();
        formatterPragmas.reset();
    }

    /**
     * Runs {@code probe} inside a re-entrant speculative scope and rolls back every per-render side effect it made if its
     * result is empty.
     *
     * <p>This is the decoupling primitive for the candidate-ladder probe pattern. Several printers eagerly build an
     * {@code Optional<Doc>} for a layout candidate to measure its fit; building it claims any comments inside the
     * candidate. The chosen candidate keeps its claims, but a <em>discarded</em> candidate has already committed its
     * claims, so the next ladder rung re-claims the same comments — which the strict-claims guardrail (correctly) rejects
     * as a duplicate. Wrapping a rung in this scope makes a discarded probe claim-free: on an empty result every claim it
     * made is undone, so the eventual winner is the only path that claims each comment.
     *
     * <p><strong>Dry-run symmetry — the design's sharpest edge.</strong> A print traversal mutates different state in
     * the two passes {@link JavaPrinter#print} runs. While {@link #recording}, {@link #claim} records owners into
     * {@link #ownership}; otherwise it consumes the {@link #printed} (and, via {@code accountRaw*}, {@link #rawRendered})
     * identity sets. The scope must roll back whichever set the <em>active</em> pass mutates: if it rolled back only
     * {@link #printed} during the dry-run, a discarded probe would leave its losing {@code (node, slot)} recorded as the
     * comment's owner, {@link #ownsHere} would then block the real winner, and the comment would silently drop. Rolling
     * back {@link #ownership} during recording instead lets the dry-run record the <em>winner</em> as owner, keeping the
     * gating byte-neutral. The {@link LayoutDecisionLog} and {@link FormatterPragmas} range state are rolled back in both
     * passes — the same per-render channels {@link #endRecordingAndReset} resets — so a discarded probe leaves neither a
     * phantom {@code --explain} wrap nor a stray open {@code @formatter:off} range.
     *
     * <p>Snapshots are element copies of the live maps/sets, so the fields stay {@code final} and nesting composes: a
     * nested scope snapshots the partially-mutated state of its enclosing scope and restores exactly to it, which is what
     * lets the chain/initializer/lambda ladders nest probes within probes. A present result keeps everything — the
     * winning render's claims, wraps, and pragma state all stand.
     */
    <T> Optional<T> speculatively(java.util.function.Supplier<Optional<T>> probe) {
        Map<Comment, OwnerKey> ownershipSnapshot = recording ? new IdentityHashMap<>(ownership) : null;
        Set<Comment> printedSnapshot = recording ? null : copyIdentitySet(printed);
        Set<Comment> rawRenderedSnapshot = recording ? null : copyIdentitySet(rawRendered);
        int layoutDecisionsSnapshot = layoutDecisions.size();
        boolean pragmasSnapshot = formatterPragmas.snapshot();
        Optional<T> result = probe.get();
        if (result.isEmpty()) {
            if (recording) {
                restoreIdentityMap(ownership, ownershipSnapshot);
            } else {
                restoreIdentitySet(printed, printedSnapshot);
                restoreIdentitySet(rawRendered, rawRenderedSnapshot);
            }
            layoutDecisions.truncateTo(layoutDecisionsSnapshot);
            formatterPragmas.restore(pragmasSnapshot);
        }
        return result;
    }

    private static Set<Comment> copyIdentitySet(Set<Comment> source) {
        Set<Comment> copy = Collections.newSetFromMap(new IdentityHashMap<>());
        copy.addAll(source);
        return copy;
    }

    private static void restoreIdentitySet(Set<Comment> target, Set<Comment> snapshot) {
        target.clear();
        target.addAll(snapshot);
    }

    private static void restoreIdentityMap(Map<Comment, OwnerKey> target, Map<Comment, OwnerKey> snapshot) {
        target.clear();
        target.putAll(snapshot);
    }

    /**
     * Reports whether {@code trivia} may render in {@code node}'s {@code slot} according to the ownership pre-pass.
     *
     * <p>This is the migration ratchet. A comment the pre-pass assigned to a specific slot renders only there: a
     * non-owner offer returns {@code false} and renders {@link Doc#EMPTY}, which today already loses the claim race, so
     * the filter is output-neutral. As of Stage 2bc every family consults this gate, so the only slot that offers a
     * comment is the one the dry-run recorded. A comment with no assignment ({@code owner == null}) is unmigrated and is
     * allowed in every slot, preserving first-claim-wins behavior for any comment the dry-run never reached.
     */
    boolean ownsHere(JavaCommentTrivia trivia, Node node, OwnerSlot slot) {
        OwnerKey owner = ownership.get(trivia.comment());
        return owner == null || owner.equals(new OwnerKey(node, slot));
    }

    Doc leading(Node node) {
        return commentPlacement.leadingComment(node)
                .filter(t -> ownsHere(t, node, OwnerSlot.LEADING))
                .filter(t -> claim(t, node, OwnerSlot.LEADING))
                .map(JavaFormatter::commentDoc)
                .map(doc -> Doc.concat(doc, Doc.HARD_LINE))
                .orElse(Doc.EMPTY);
    }

    Doc adjacentLeadingLineComments(Node node) {
        return Doc.concat(
            commentPlacement.adjacentLeadingLineComments(node)
                    .stream()
                    .filter(t -> ownsHere(t, node, OwnerSlot.ADJACENT_LEADING))
                    .filter(t -> claim(t, node, OwnerSlot.ADJACENT_LEADING))
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
                .filter(t -> ownsHere(t, node, OwnerSlot.TRAILING))
                .filter(t -> claim(t, node, OwnerSlot.TRAILING))
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
                    .filter(t -> ownsHere(t, owner, OwnerSlot.TRAILING))
                    .filter(t -> claim(t, owner, OwnerSlot.TRAILING))
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
                .filter(t -> ownsHere(t, semicolonOwner, OwnerSlot.TRAILING))
                .filter(t -> claim(t, semicolonOwner, OwnerSlot.TRAILING))
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
                .filter(t -> ownsHere(t, body, OwnerSlot.LEADING))
                .filter(t -> claim(t, body, OwnerSlot.LEADING))
                .map(JavaFormatter::commentDoc)
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
        List<Doc> rendered = new ArrayList<>();
        for (JavaCommentTrivia trivia : commentPlacement.gapLeadingLineCommentBlock(afterNode, body, attachmentBuckets)) {
            if (ownsHere(trivia, anchor, OwnerSlot.LEADING) && claim(trivia, anchor, OwnerSlot.LEADING)) {
                if (!rendered.isEmpty()) {
                    rendered.add(Doc.HARD_LINE);
                }
                rendered.add(JavaFormatter.commentDoc(trivia));
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
                .filter(t -> ownsHere(t, boundary, OwnerSlot.ORPHAN))
                .filter(t -> claim(t, boundary, OwnerSlot.ORPHAN))
                .map(JavaFormatter::commentDoc)
                .toList();
    }

    Doc orphanComments(Node node, Predicate<Comment> predicate) {
        return Doc.concat(
            commentPlacement.orphanComments(node)
                    .stream()
                    .filter(trivia -> predicate.test(trivia.comment()))
                    .filter(t -> ownsHere(t, node, OwnerSlot.ORPHAN))
                    .filter(t -> claim(t, node, OwnerSlot.ORPHAN))
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
                .filter(t -> ownsHere(t, node, OwnerSlot.ORPHAN))
                .filter(t -> claim(t, node, OwnerSlot.ORPHAN))
                .map(JavaFormatter::commentDoc)
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
                    .filter(t -> ownsHere(t, node, OwnerSlot.ORPHAN))
                    .filter(t -> claim(t, node, OwnerSlot.ORPHAN))
                    .map(comment -> Doc.concat(JavaFormatter.commentDoc(comment), Doc.HARD_LINE))
                    .toList()
        );
    }

    Doc ownComment(Node node, Predicate<Comment> predicate) {
        return commentPlacement.ownComment(node)
                .filter(trivia -> predicate.test(trivia.comment()))
                .filter(t -> ownsHere(t, node, OwnerSlot.OWN))
                .filter(t -> claim(t, node, OwnerSlot.OWN))
                .map(JavaFormatter::commentDoc)
                .orElse(Doc.EMPTY);
    }

    Doc ownTriviaComment(Node node, Predicate<JavaCommentTrivia> predicate) {
        return commentPlacement.ownComment(node)
                .filter(predicate)
                .filter(t -> ownsHere(t, node, OwnerSlot.OWN))
                .filter(t -> claim(t, node, OwnerSlot.OWN))
                .map(JavaFormatter::commentDoc)
                .orElse(Doc.EMPTY);
    }

    /**
     * Claims and renders {@code comment} as a comment the interleaver merged between an anchor's children by source
     * position.
     *
     * <p>This overload exists so the source-order interleaver and the printers that hand a comment directly to the
     * tracker can name the anchor node and {@link OwnerSlot} that offers the comment, which is what the record-only
     * dry-run needs to record the first claimant. {@code anchor} is the node whose layout encloses the comment (the
     * interleaved owner, or the printer's own node); {@code slot} is the role under which it is offered.
     */
    Doc comment(JavaCommentTrivia trivia, Node anchor, OwnerSlot slot) {
        return ownsHere(trivia, anchor, slot) && claim(trivia, anchor, slot)
            ? JavaFormatter.commentDoc(trivia)
            : Doc.EMPTY;
    }

    Doc comment(Comment comment, Node anchor, OwnerSlot slot) {
        return comment(JavaCommentTrivia.from(comment), anchor, slot);
    }

    /**
     * Claims and renders {@code comment} when no anchor node is available to name; defaults to recording the offering
     * node as the comment itself under {@link OwnerSlot#INTERLEAVED}.
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
     * Records or consumes one comment claim, depending on whether this tracker is in the record-only dry-run pass.
     *
     * <p>In the real pass this is the unchanged {@link FormatterGuardrails#claimComment} call against {@link #printed};
     * {@code node} and {@code slot} are ignored there. In the record pass it does not touch {@link #printed}: the first
     * offer of a comment records {@code (node, slot)} as its owner in {@link #ownership} and returns {@code true}; every
     * later offer of the same comment finds it already recorded and returns {@code false}. The {@code ownership} map
     * thus plays the same once-only role {@code printed} plays in the real pass, so candidate ladders see the identical
     * (first offer wins, later offers lose) boolean sequence in both passes and the recorded first claimant matches the
     * real one.
     */
    private boolean claim(JavaCommentTrivia trivia, Node node, OwnerSlot slot) {
        if (recording) {
            return ownership.putIfAbsent(trivia.comment(), new OwnerKey(node, slot)) == null;
        }
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
