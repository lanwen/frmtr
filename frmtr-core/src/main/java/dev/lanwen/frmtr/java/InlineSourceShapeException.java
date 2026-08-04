package dev.lanwen.frmtr.java;

/**
 * The inline source-shape read registry — now empty: all inline aesthetic reads are retired.
 *
 * <p>The enum and its guard ({@link InlineSourceLineReadGuardTest}) stand as a ratchet: any new inline read forces a
 * reviewed enum entry. Two mechanisms make such a read: {@link Mechanism#LINE_COMPARE} (comparing two
 * {@code getRange()} line numbers) and {@link Mechanism#RAW_SOURCE_SHAPE} (reading a node's raw source text and
 * inspecting its line structure). Neither is permitted without a catalogued entry here.
 */
enum InlineSourceShapeException {
    ;

    /** How the inline read consults source shape — the mechanism the guard scans for. */
    enum Mechanism {
        /** Compares two {@code getRange()} line numbers to preserve the author's line breaks. */
        LINE_COMPARE,
        /** Reads a node's raw source text and inspects its line structure (first line / multiline). */
        RAW_SOURCE_SHAPE
    }

    private final String key;

    private final Mechanism mechanism;

    private final String deferralCause;

    private final String tracking;

    InlineSourceShapeException(String key, Mechanism mechanism, String deferralCause, String tracking) {
        this.key = key;
        this.mechanism = mechanism;
        this.deferralCause = deferralCause;
        this.tracking = tracking;
    }

    /** The {@code SimpleClassName#method} key of the inline read, stable across line moves. */
    String key() {
        return key;
    }

    /** The mechanism this read uses to consult source shape. */
    Mechanism mechanism() {
        return mechanism;
    }

    /** Why the read is still live (what layout decision it makes off the author's source shape). */
    String deferralCause() {
        return deferralCause;
    }

    /** The retirement tracking note for this residual read. */
    String tracking() {
        return tracking;
    }
}
