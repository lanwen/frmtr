package dev.lanwen.frmtr.java;

/**
 * The closed, enumerated set of <em>inline</em> source-shape reads still live in the Java printers.
 *
 * <p>Sibling to {@link SourceShapeException}, which enumerates the reads that go through
 * {@link SourceShapePolicy}. This enum closes the second tier the D3 flip uncovered: printers that hand-roll a
 * "preserve the author's line layout" decision inline, without ever touching the policy, so they were invisible to
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
 * {@code RETIREMENT_TARGET}: a "preserve the author's line breaks" read the formatter overwrites, deferred on the
 * enclosing-column / {@code leftEdgePrefix} foundation (proposal {@code left-edge-prefix-foundation.md}) that also blocks
 * the last {@link SourceShapePolicy} retirements. They are tracked as the G3 retirement slice, not retired here.
 */
enum InlineSourceShapeException {

    /** "did the initializer start on a continuation line after the name?" ({@code initializer.begin.line > name.end.line}). */
    INITIALIZER_STARTS_ON_CONTINUATION_LINE(
        "VariableInitializerLayout#initializerStartsOnContinuationLine",
        Mechanism.LINE_COMPARE,
        "reads whether the author broke the initializer onto its own line to pick the assignment continuation shape",
        "G3 (D3 flip follow-up) — retire when the leftEdgePrefix foundation lands"
    ),

    /** "does the method-call scope end on the name line?" ({@code scope.end.line == name.begin.line}). */
    METHOD_CALL_SCOPE_ENDS_ON_NAME_LINE(
        "VariableInitializerLayout#methodCallScopeEndsOnNameLine",
        Mechanism.LINE_COMPARE,
        "reads whether the author kept the receiver and the call name on one source line",
        "G3 (D3 flip follow-up) — retire when the leftEdgePrefix foundation lands"
    ),

    /** "does the call close stay on the lambda body line?" ({@code parent.end.line == body.end.line}). */
    CALL_CLOSING_STAYS_ON_LAMBDA_BODY_LINE(
        "ExpressionLambdaClosingLayout#callClosingStaysOnLambdaBodyLine",
        Mechanism.LINE_COMPARE,
        "reads whether the author closed the enclosing call on the lambda body's last source line",
        "G3 (D3 flip follow-up) — retire when the leftEdgePrefix foundation lands"
    ),

    /** "is the first source line exactly the chain root?" ({@code rawWithoutOwnComment(...).lines().findFirst() == root}). */
    CHAIN_ROOT_IS_SOLE_FIRST_SOURCE_LINE(
        "MethodCallChainPrinter#sourceFirstLineIsOnlyChainRoot",
        Mechanism.RAW_SOURCE_SHAPE,
        "reads whether the author put the chain root alone on the first source line to gate attaching the first segment",
        "G3 (D3 flip follow-up) — retire when the leftEdgePrefix foundation lands"
    ),

    /** "does the first source line keep a chain segment after the root?" (first source line startsWith root + more). */
    FIRST_SOURCE_LINE_KEEPS_CHAIN_AFTER_ROOT(
        "VariableInitializerLayout#sourceFirstLineKeepsChainAfterRoot",
        Mechanism.RAW_SOURCE_SHAPE,
        "reads whether the author kept a chain segment on the root's first source line to pick the initializer shape",
        "G3 (D3 flip follow-up) — retire when the leftEdgePrefix foundation lands"
    ),

    /** The first source line of an expression-lambda body ({@code rawWithoutOwnComment(node).lines().findFirst()}). */
    LAMBDA_BODY_FIRST_SOURCE_LINE(
        "ExpressionLambdaArgumentLayout#bodyFirstSourceLine",
        Mechanism.RAW_SOURCE_SHAPE,
        "reads the lambda body's first authored source line to measure whether it can hug the opener",
        "G3 (D3 flip follow-up) — retire when the leftEdgePrefix foundation lands"
    ),

    /** "were the lambda parameters written across multiple source lines?" ({@code parameterText(...).contains(newline)}). */
    SOURCE_MULTILINE_LAMBDA_PARAMETERS(
        "LambdaParameterHeaderLayout#hasSourceMultilineParameters",
        Mechanism.RAW_SOURCE_SHAPE,
        "reads whether the author spread the lambda parameter list across source lines to force the broken header",
        "G3 (D3 flip follow-up) — retire when the leftEdgePrefix foundation lands"
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
