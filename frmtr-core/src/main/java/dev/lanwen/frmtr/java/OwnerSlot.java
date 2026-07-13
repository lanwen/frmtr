package dev.lanwen.frmtr.java;

/**
 * Names the placement <em>role</em> a comment can be assigned to relative to its anchor node.
 *
 * <p>This enum is the vocabulary of the explicit comment-ownership pre-pass (see {@link CommentTracker}'s
 * {@code ownership} map and {@code ownsHere} filter). A role plus an anchor node forms an {@link OwnerKey}: the single
 * slot a comment is allowed to render in, decided once before any printer claims a comment, rather than being settled
 * implicitly by the first-claim-wins race between competing printer paths.
 *
 * <p>Stage 2a of the B2 comment-ownership consolidation populates <em>every</em> role from the record-only dry-run
 * pre-pass (see {@link CommentTracker#beginRecording}): the dry-run records each comment's first claimant in print
 * traversal order under the slot the offering wrapper names. Stage 2bc then <em>consults</em> {@link CommentTracker#ownsHere}
 * for <em>every</em> role, so a comment renders only from the slot the dry-run recorded as its first claimant. Output
 * stays byte-identical because the recorded owner is the same forward-traversal winner of today's first-claim-wins race,
 * and every suppressed non-owner offer already rendered empty. The dry-run records the <em>real</em> traversal owner
 * rather than a source-order approximation precisely because a pure source-order rule diverges on the contested
 * leading/own families (the parent-interleaver-beats-child cases) while it matches on trailing.
 *
 * <p>This type owns only the role taxonomy. It does not decide which node anchors a comment, how the comment renders,
 * or when ownership is consulted; those stay with {@link JavaCommentPlacementPolicy} (anchoring) and
 * {@link CommentTracker} (consultation and rendering).
 */
enum OwnerSlot {

    /**
     * The comment is an own line comment that trails its anchor node in source order. Consulted by
     * {@link CommentTracker#ownsHere}. See {@link JavaCommentPlacementPolicy#trailingLineComment(com.github.javaparser.ast.Node)}.
     *
     * <p>This is the <em>outer envelope</em> slot for a statement's own trailing {@code //} line comment: the slot
     * {@link StatementRuleEnvelope} and every direct {@link CommentTracker#trailingLineComment(com.github.javaparser.ast.Node)}
     * caller offers under. A content renderer that must place the <em>same</em> node's trailing comment itself (an
     * {@code if}/{@code else} chain positioning the then-body's comment before the {@code else} keyword, say) offers under
     * the distinct {@link #CONTENT_TRAILING} slot instead, so the dry-run's first offerer becomes the sole owner and the
     * losing slot renders empty by ownership rather than by reading a build-time claim side effect.
     */
    TRAILING,

    /**
     * The same trailing {@code //} line comment as {@link #TRAILING}, but offered by an <em>inner content renderer</em>
     * that owns positioning it (rather than by the outer {@link StatementRuleEnvelope}).
     *
     * <p>Kept distinct from {@link #TRAILING} on the same anchor node so the two co-offering paths never collapse to one
     * {@link OwnerKey}. The dry-run records whichever path offers first — the envelope for a plain expression statement
     * (envelope wraps before the content renders), the content renderer for an {@code if}/{@code else} then/else body
     * (the enclosing {@code if} layout renders the nested body's trailing comment, in its chosen spot, before the nested
     * statement's own envelope runs) — and the other path, holding the other slot, is not the owner and renders
     * {@link dev.lanwen.frmtr.doc.Doc#EMPTY}. That reproduces the old first-claim-wins winner without either path reading
     * {@link CommentTracker#isPrinted}. Populated by the dry-run; consulted by {@code ownsHere}.
     */
    CONTENT_TRAILING,

    /**
     * The same trailing {@code //} line comment as {@link #TRAILING}/{@link #CONTENT_TRAILING}, but offered by an
     * <em>enclosing construct</em> that positions a nested statement's trailing comment in a spot only the enclosing
     * layout controls — an {@code if}/{@code else} chain rendering the then-body's comment on its own line before the
     * {@code else} keyword.
     *
     * <p>Kept distinct from both {@link #TRAILING} (the nested statement's own envelope) and {@link #CONTENT_TRAILING}
     * (the nested statement's own content renderer) on the same anchor node, because all three fire for one node when an
     * {@code if} branch body is an expression statement: the enclosing {@code if} layout offers first (before the nested
     * body is rendered), so the dry-run records this slot as the owner and both of the nested statement's own offers
     * render {@link dev.lanwen.frmtr.doc.Doc#EMPTY} by ownership. One slot covers this path's attached-own and its
     * parent-parked ({@link #UNATTACHED_TRAILING}-style) recovery, since a node has at most one of those, so the nested
     * statement's own {@link #UNATTACHED_TRAILING} recovery also yields to it. Populated by the dry-run; consulted by
     * {@code ownsHere}.
     */
    ENCLOSED_TRAILING,

    /**
     * A trailing {@code //} line comment recovered from an enclosing node's bucket because JavaParser parked it there
     * (its placement-policy parent walk), rather than on the node it visually trails.
     *
     * <p>The same parent-parked comment can be reached from more than one recovering node, so it is anchored to the
     * <em>recovering</em> node under this distinct slot: the dry-run's first recovering node owns it and later recovering
     * nodes, keying a different {@link OwnerKey}, render {@link dev.lanwen.frmtr.doc.Doc#EMPTY}. That reproduces the old
     * first-claim-wins recovery without reading {@link CommentTracker#isPrinted}. Kept distinct from {@link #TRAILING}
     * and {@link #CONTENT_TRAILING} because a node's own attached trailing comment and a comment it merely recovers from
     * a parent bucket are different comments that must be able to co-exist on the same recovering node. Populated by the
     * dry-run; consulted by {@code ownsHere}.
     */
    UNATTACHED_TRAILING,

    /** A comment that leads its anchor node. Populated by the dry-run; consulted by {@code ownsHere}. */
    LEADING,

    /** A contiguous leading-comment cluster directly above the anchor. Populated by the dry-run; consulted by {@code ownsHere}. */
    ADJACENT_LEADING,

    /** A comment JavaParser attached directly to the anchor as its own trivia. Populated by the dry-run; consulted by {@code ownsHere}. */
    OWN,

    /** A comment JavaParser parked as an orphan of the anchor. Populated by the dry-run; consulted by {@code ownsHere}. */
    ORPHAN,

    /** A comment interleaved between an anchor's children by a parent sequence printer. Populated by the dry-run; consulted by {@code ownsHere}. */
    INTERLEAVED
}
