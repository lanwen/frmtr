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
     */
    TRAILING,

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
