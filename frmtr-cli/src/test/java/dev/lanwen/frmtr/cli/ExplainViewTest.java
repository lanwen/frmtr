package dev.lanwen.frmtr.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.lanwen.frmtr.ExplainResult;
import dev.lanwen.frmtr.doc.DocExplanation;
import dev.lanwen.frmtr.doc.DocExplanation.ConditionalGroupDecision;
import dev.lanwen.frmtr.doc.DocExplanation.Decision;
import dev.lanwen.frmtr.doc.DocExplanation.FillDecision;
import dev.lanwen.frmtr.doc.DocExplanation.Node;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link ExplainView}'s rendering of the {@code Fill} and {@code ConditionalGroup} width decisions directly.
 *
 * <p>The {@code ConditionalGroup} primitive is not produced by any Java printer yet, so its "why it wrapped" wording has
 * no end-to-end CLI path; constructing a {@link DocExplanation} here is the only way to prove the renderer surfaces the
 * chosen-alternative arithmetic. The {@code Fill} case is also covered end-to-end by {@code MainExplainTest}, but the
 * focused assertions here pin the exact per-separator wording.
 */
final class ExplainViewTest {

    private static final ExplainView.Styler PLAIN = (role, text) -> text;

    private static String renderWhy(DocExplanation explanation) {
        String rendered = new ExplainView(PLAIN, false).render(new ExplainResult("// formatted\n", explanation));
        int start = rendered.indexOf("Why it wrapped");
        int end = rendered.indexOf("Decision tree", start);
        return rendered.substring(start, end < 0 ? rendered.length() : end);
    }

    private static DocExplanation explanationWith(
        List<FillDecision> fills,
        List<ConditionalGroupDecision> conditionalGroups
    ) {
        return new DocExplanation(
            80,
            List.of(),
            fills,
            conditionalGroups,
            List.of(),
            List.of(),
            new Node(Optional.empty(), Optional.empty(), 0, List.of())
        );
    }

    @Test
    void rendersPerSeparatorWidthMathForABrokenFill() {
        FillDecision fill = new FillDecision(
            Optional.of("java.expression:Fill"),
            List.of(
                new FillDecision.Separator(1, Decision.FLAT, 5, 16, 4),
                new FillDecision.Separator(3, Decision.BREAK, 15, 11, 9)
            )
        );

        String why = renderWhy(explanationWith(List.of(fill), List.of()));

        // The fill names how many separators broke and reports the width arithmetic only for the one that overflowed.
        assertThat(why)
                .contains("Fill wrapped (1 of 2 separators broke):")
                .contains("flat width 15 > 11 available (from column 9)")
                // The separator that stayed flat is not reported as a break.
                .doesNotContain("flat width 5");
    }

    @Test
    void rendersChosenAlternativeAndProbedWidthsForABreakModeConditionalGroup() {
        ConditionalGroupDecision conditionalGroup = new ConditionalGroupDecision(
            Optional.of("java.expression:Conditional"),
            1,
            true,
            20,
            10,
            List.of(
                new ConditionalGroupDecision.Alternative(0, 25, false),
                // A negative flat width is the forced-break sentinel: this fallback alternative contains a hard break.
                new ConditionalGroupDecision.Alternative(1, -1, false)
            )
        );

        String why = renderWhy(explanationWith(List.of(), List.of(conditionalGroup)));

        assertThat(why)
                .contains("Conditional wrapped (no alternative fit, used alternative 1 in break mode):")
                // The first (narrowest) alternative's flat width is shown against the columns left, so a reader sees
                // why it was skipped.
                .contains("alternative 0: flat width 25 > 20 available (from column 10)")
                // The break-mode fallback contains a hard line break, so it reads as forced rather than a huge sentinel.
                .contains("alternative 1: forced (contains a hard line break)");
    }

    @Test
    void aConditionalGroupThatPickedAFlatAlternativeIsNotReportedAsAWrap() {
        ConditionalGroupDecision conditionalGroup = new ConditionalGroupDecision(
            Optional.of("java.expression:Conditional"),
            0,
            false,
            80,
            0,
            List.of(new ConditionalGroupDecision.Alternative(0, 5, true))
        );

        String why = renderWhy(explanationWith(List.of(), List.of(conditionalGroup)));

        // A conditional group that fit a flat alternative did not wrap, so it stays out of "why it wrapped".
        assertThat(why).contains("Nothing wrapped").doesNotContain("Conditional");
    }
}
