package dev.lanwen.frmtr.java;

/**
 * The closed, enumerated set of <em>inline</em> source-shape reads still live in the Java printers.
 *
 * <p>Sibling to {@link SourceShapeException}, which enumerates the reads that go through
 * {@link SourceShapePolicy}. This enum closes the second tier: printers that hand-roll a
 * "preserve the author's line layout" decision inline, without ever touching the policy, so they are invisible to
 * {@code SourceShapeExceptionGovernanceTest}. Two mechanisms make such a read:
 *
 * <ul>
 *   <li>{@link Mechanism#LINE_COMPARE} — comparing two {@code getRange()} line numbers
 *       ({@code X.begin.line == / < / > Y.begin.line}/{@code .end.line}, or {@code range.begin.line < range.end.line}
 *       "was this node multiline in source?"); and</li>
 *   <li>{@link Mechanism#RAW_SOURCE_SHAPE} — reading a node's raw source text and inspecting its line structure: the
 *       first source line ({@code rawWithoutOwnComment(...).lines().findFirst()}) or whether it spans lines
 *       ({@code ....contains("\n")} on non-compact text).</li>
 * </ul>
 *
 * <p>{@link InlineSourceLineReadGuardTest} scans the non-excluded printer sources for both mechanisms and asserts the
 * live set is exactly the keys enumerated here — no uncatalogued read (a NEW inline read forces a reviewed enum value)
 * and no stale entry (a retired read forces its enum value's deletion, which is progress). Every value below is a
 * {@code RETIREMENT_TARGET}: a "preserve the author's line breaks" read the formatter overwrites.
 *
 * <p>The remaining entry is genuinely load-bearing: it preserves an author-intent line shape that no clean width/AST
 * rule reproduces without regressing the corpus or a curated golden, so it stays deferred on the enclosing-column /
 * {@code leftEdgePrefix} foundation (proposal {@code left-edge-prefix-foundation.md}) that also blocks the last
 * {@link SourceShapePolicy} retirements.
 */
enum InlineSourceShapeException {

    /** "does the method-call scope end on the name line?" ({@code scope.end.line == name.begin.line}). */
    METHOD_CALL_SCOPE_ENDS_ON_NAME_LINE(
        "InitializerMethodCallChainLayout#methodCallScopeEndsOnNameLine",
        Mechanism.LINE_COMPARE,
        "reads whether the author kept the receiver and the call name on one source line",
        "G3: LEFT — decides attach-vs-fan for a sub-threshold chain-scoped initializer; forcing the structural constant "
        + "true (always attach) laterally moves the curated method-chain-block-lambda golden off its fanned shape (no "
        + "clear improvement) and forcing false regresses three goldens; no clean width/AST partition. Defer on leftEdgePrefix."
    );

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
