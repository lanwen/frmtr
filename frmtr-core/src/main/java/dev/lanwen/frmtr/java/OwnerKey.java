package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;

/**
 * Identifies the single slot a comment is allowed to render in: a specific anchor {@link Node} paired with an
 * {@link OwnerSlot} role.
 *
 * <p>This record is the value the explicit comment-ownership pre-pass stores per comment (see {@link CommentTracker}'s
 * {@code ownership} map). A printer slot renders a comment only when the ownership pre-pass assigned that exact
 * {@code (anchor, slot)} key, which lets ownership be decided once up front instead of being settled by the implicit
 * first-claim-wins race between competing printer paths.
 *
 * <p><strong>Anchor equality is identity, not structure.</strong> JavaParser's {@link Node#equals(Object)} is a deep
 * structural comparison (via {@code EqualsVisitor}), so two distinct nodes that happen to be syntactically equal would
 * compare equal — which would make ownership keys collide and point a comment at the wrong node. This record therefore
 * compares and hashes the anchor by reference identity ({@code ==} / {@link System#identityHashCode(Object)}),
 * matching how {@link CommentTracker} keys claimed comments in an {@link java.util.IdentityHashMap}-backed set
 * (see {@code CommentTracker.printed}). The generated record {@code equals}/{@code hashCode} are overridden for this
 * reason; the canonical accessors stay.
 *
 * <p>This type owns only the key identity. It does not decide which node anchors a comment, what the role means, or
 * when ownership is consulted; those stay with {@link JavaCommentPlacementPolicy} and {@link CommentTracker}.
 */
record OwnerKey(Node anchor, OwnerSlot slot) {

    OwnerKey {
        if (anchor == null) {
            throw new IllegalArgumentException("anchor must not be null");
        }
        if (slot == null) {
            throw new IllegalArgumentException("slot must not be null");
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof OwnerKey(Node otherAnchor, OwnerSlot otherSlot)
            && anchor == otherAnchor
            && slot == otherSlot;
    }

    @Override
    public int hashCode() {
        return 31 * System.identityHashCode(anchor) + slot.hashCode();
    }
}
