package dev.lanwen.frmtr.java;

/**
 * Names the placement <em>role</em> a comment can be assigned to relative to its anchor node.
 *
 * <p>This enum is the vocabulary of the explicit comment-ownership pre-pass (see {@link CommentTracker}'s
 * {@code ownership} map and {@code ownsHere} filter). A role plus an anchor node forms an {@link OwnerKey}: the single
 * slot a comment is allowed to render in, decided once before any printer claims a comment, rather than being settled
 * implicitly by the first-claim-wins race between competing printer paths.
 *
 * <p>The enum is deliberately broader than what the current stage uses. Stage 1 of the B2 comment-ownership
 * consolidation migrates only the {@link #TRAILING} family — empirically the unique family a pure source-order
 * assignment rule reproduces byte-for-byte. The remaining roles are declared here so later stages can extend the
 * pre-pass without reshaping the key type, but they are intentionally not yet populated or consulted: a source-order
 * rule diverges on the contested leading/own families (the parent-interleaver-beats-child cases), so those families
 * still rely on today's claim-race behavior until a traversal-order ownership rule migrates them.
 *
 * <p>This type owns only the role taxonomy. It does not decide which node anchors a comment, how the comment renders,
 * or when ownership is consulted; those stay with {@link JavaCommentPlacementPolicy} (anchoring) and
 * {@link CommentTracker} (consultation and rendering).
 */
enum OwnerSlot {

    /**
     * The comment is an own line comment that trails its anchor node in source order — the only role migrated to the
     * explicit pre-pass in Stage 1. See {@link JavaCommentPlacementPolicy#trailingLineComment(com.github.javaparser.ast.Node)}.
     */
    TRAILING,

    /** Reserved for a later stage: a comment that leads its anchor node. Not yet populated or consulted. */
    LEADING,

    /** Reserved for a later stage: a contiguous leading-comment cluster directly above the anchor. Not yet populated or consulted. */
    ADJACENT_LEADING,

    /** Reserved for a later stage: a comment JavaParser attached directly to the anchor as its own trivia. Not yet populated or consulted. */
    OWN,

    /** Reserved for a later stage: a comment JavaParser parked as an orphan of the anchor. Not yet populated or consulted. */
    ORPHAN,

    /** Reserved for a later stage: a comment interleaved between an anchor's children by a parent sequence printer. Not yet populated or consulted. */
    INTERLEAVED
}
