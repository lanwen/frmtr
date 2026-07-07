package dev.lanwen.frmtr.java;

import java.util.List;

/**
 * The closed, enumerated set of source-shape reads {@link SourceShapePolicy} is permitted to make.
 *
 * <p>frmtr's direction is reprint-from-scratch ({@code docs/proposals/reprint-by-default-break-rules.md}): the formatter
 * does not preserve the author's line layout except through the reads named here. This enum is the single registry of
 * those reads — every source-shape decision on {@link SourceShapePolicy} maps to exactly one value — and it is enforced
 * by {@code SourceShapeExceptionGovernanceTest}, which fails if a policy method appears that is not categorized here.
 * Adding a source-shape read is therefore a deliberate, reviewed act (declare its value + {@link Stability} + rationale)
 * rather than an inline one-off, which is what makes "reprint by default, these exceptions only" an enforced contract
 * rather than an aspiration.
 *
 * <p>Each value carries a {@link Stability}. A {@link Stability#FIXPOINT_SAFE} read is one the formatter's own output
 * reproduces verbatim or normalizes to a canonical form, so re-reading the formatted text yields the same answer and the
 * read round-trips to a fixpoint — these are permanent. A {@link Stability#RETIREMENT_TARGET} read is a
 * "preserve the author's line breaks" read: because the formatter overwrites the very line breaks it reads, the output
 * depends on incidental input shape and is not a one-pass fixpoint, so each is slated to be replaced by a deterministic
 * structural {@link BreakRule} and then deleted. The retirement-target count is the roadmap's progress metric.
 */
enum SourceShapeException {

    /** Whether the author left a blank line between two source-adjacent nodes. */
    BLANK_LINE(
        Stability.FIXPOINT_SAFE,
        "Blank lines collapse to at most one, so re-reading the formatted output yields the same answer.",
        List.of("hadBlankLineBetween", "hadBlankLineBefore")
    ),

    /** Whether a node's source-equivalent compact form fits on one line at its indentation. */
    WIDTH_FIT(
        Stability.FIXPOINT_SAFE,
        "A width probe over source-equivalent compact text — a width decision, not a read of the author's line breaks.",
        List.of("fitsOnOneLine")
    ),

    /** Whether a method's {@code throws} clause starts its own source line. */
    THROWS_ON_OWN_LINE(
        Stability.FIXPOINT_SAFE,
        "throws-on-its-own-line is reproduced verbatim in the output, so the read round-trips to a fixpoint.",
        List.of("throwsStartsOnOwnLine")
    ),

    /** The source-only try-with-resources section shape (multiline sections, trailing semicolon). */
    TRY_RESOURCES_SHAPE(
        Stability.FIXPOINT_SAFE,
        "The try-with-resources trailing semicolon and section layout are reproduced verbatim, so the read round-trips.",
        List.of("tryResources")
    ),

    /** Whether a node carries contained comments, gating whether a compact reconstruction is safe. */
    COMMENT_PRESENCE_GATE(
        Stability.FIXPOINT_SAFE,
        "Gates compact reconstruction on comment presence — a correctness gate, not an aesthetic; comments are preserved verbatim.",
        List.of("hasContainedComments")
    ),

    /** "The author broke this node across lines, so keep it broken" — the base preserve-the-source-shape read. */
    WAS_MULTILINE(
        Stability.RETIREMENT_TARGET,
        "\"The author broke this node across lines, so keep it broken.\" The formatter overwrites the very line breaks it "
            + "reads, so the output depends on incidental input shape and is not a one-pass fixpoint; replace with a "
            + "deterministic structural BreakRule.",
        List.of(
            "wasMultiline",
            "methodCallArgumentsSpanMultipleLines",
            "methodCallOperandSpansMultipleLines",
            "methodCallFirstArgumentStartsAfterName",
            "containsSourceMultilineMethodCallArgument",
            "sourceMultilineLogicalCondition",
            "expressionLambdaStartsOnSelectorLine",
            "objectCreationArgumentsSpanMultipleLines",
            "callableParametersSpanMultipleLines"
        )
    ),

    /** Whether two nodes began on the same source line. */
    STARTS_ON_SAME_LINE(
        Stability.RETIREMENT_TARGET,
        "Keys on whether two nodes shared a source line — a wasMultiline-flavored read the formatter overwrites.",
        List.of("startsOnSameLine")
    ),

    /** Whether a chain selector started a later source line than the previous segment ended. */
    CHAIN_SELECTOR_BROKE(
        Stability.RETIREMENT_TARGET,
        "Keys on whether a chain selector started a new source line; superseded by the canonical-fan BreakRule and "
            + "slated for deletion once its remaining callers drop it.",
        List.of("selectorBrokeAfter")
    );

    /** How stable across formatting passes a read is — the property that decides whether it stays or is retired. */
    enum Stability {
        /** Reproduced or normalized to a canonical form by the formatter's own output, so it round-trips to a fixpoint. */
        FIXPOINT_SAFE,
        /** A "preserve the author's line breaks" read the formatter overwrites; slated for a structural replacement. */
        RETIREMENT_TARGET
    }

    private final Stability stability;

    private final String rationale;

    private final List<String> methods;

    SourceShapeException(Stability stability, String rationale, List<String> methods) {
        this.stability = stability;
        this.rationale = rationale;
        this.methods = List.copyOf(methods);
    }

    Stability stability() {
        return stability;
    }

    /** The idempotence justification for permitting (or the reason for retiring) this read. */
    String rationale() {
        return rationale;
    }

    /** The {@link SourceShapePolicy} method names that make this read. */
    List<String> methods() {
        return methods;
    }
}
