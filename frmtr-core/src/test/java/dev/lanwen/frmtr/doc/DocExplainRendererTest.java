package dev.lanwen.frmtr.doc;

import static org.assertj.core.api.Assertions.assertThat;

import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.TestFormatterOptions;
import dev.lanwen.frmtr.doc.DocExplanation.ConditionalGroupDecision;
import dev.lanwen.frmtr.doc.DocExplanation.Decision;
import dev.lanwen.frmtr.doc.DocExplanation.FillDecision;
import dev.lanwen.frmtr.doc.DocExplanation.ForcedBreak;
import dev.lanwen.frmtr.doc.DocExplanation.GroupDecision;
import dev.lanwen.frmtr.doc.PrinterWrap;
import java.util.List;
import org.junit.jupiter.api.Test;

final class DocExplainRendererTest {

    private static DocExplanation explain(int lineWidth, Doc doc) {
        FormatterOptions options = TestFormatterOptions.forLayout(
            lineWidth,
            FormatterOptions.IndentStyle.SPACE,
            2,
            FormatterOptions.LineEnding.LF,
            false
        );
        return new DocExplainRenderer(options).explain(doc, java.util.List.of());
    }

    @Test
    void recordsFlatDecisionWithWidthMathWhenGroupFits() {
        Doc doc = Doc.label("java.expression:Call", Doc.delimited("call(", ")", Doc.text("value")));

        DocExplanation explanation = explain(40, doc);

        assertThat(explanation.brokenGroups()).isEmpty();
        GroupDecision outer = explanation.decisions().getFirst();
        assertThat(outer.decision()).isEqualTo(Decision.FLAT);
        assertThat(outer.label()).contains("java.expression:Call");
        // call(value) is 11 columns wide and easily fits the 40-column budget on a fresh line.
        assertThat(outer.flatWidth()).isEqualTo(11);
        assertThat(outer.available()).isEqualTo(40);
    }

    @Test
    void recordsBreakWhenGroupExceedsAvailableColumns() {
        Doc doc = Doc.delimited(
            "call(",
            ")",
            Doc.concat(Doc.text("firstArgument"), Doc.text(","), Doc.LINE, Doc.text("secondArgument"))
        );

        DocExplanation explanation = explain(20, doc);

        GroupDecision broken = explanation.brokenGroups().getFirst();
        assertThat(broken.decision()).isEqualTo(Decision.BREAK);
        // "call(firstArgument, secondArgument)" is 35 wide, well past the 20-column budget.
        assertThat(broken.flatWidth()).isEqualTo(35);
        assertThat(broken.available()).isEqualTo(20);
        assertThat(broken.forcedBreak()).isFalse();
    }

    @Test
    void tracksColumnSoNestedGroupSeesReducedAvailableWidth() {
        Doc inner = Doc.group(Doc.text("inner"));
        Doc doc = Doc.concat(Doc.text("0123456789"), inner);

        DocExplanation explanation = explain(40, doc);

        GroupDecision innerDecision = explanation.decisions().getFirst();
        // The ten-character prefix consumes columns before the renderer reaches the group.
        assertThat(innerDecision.startColumn()).isEqualTo(10);
        assertThat(innerDecision.available()).isEqualTo(30);
    }

    @Test
    void attributesForcedHardBreaksToNearestEnclosingLabel() {
        Doc chain = Doc.label(
            "java.expression:MethodCallExpr",
            Doc.concat(
                Doc.text("root()"),
                Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.text(".a()"), Doc.HARD_LINE, Doc.text(".b()")))
            )
        );
        Doc doc = Doc.label("java.statement:ExpressionStmt", chain);

        DocExplanation explanation = explain(40, doc);

        assertThat(explanation.forcedBreaks())
                .singleElement()
                .satisfies(forced -> {
                    assertThat(forced.label()).contains("java.expression:MethodCallExpr");
                    assertThat(forced.count()).isEqualTo(2);
                });
    }

    @Test
    void groupContainingHardBreakReportsForcedBreakAndCannotFit() {
        Doc doc = Doc.group(Doc.concat(Doc.text("a"), Doc.HARD_LINE, Doc.text("b")));

        DocExplanation explanation = explain(80, doc);

        GroupDecision decision = explanation.decisions().getFirst();
        assertThat(decision.decision()).isEqualTo(Decision.BREAK);
        assertThat(decision.forcedBreak()).isTrue();
    }

    @Test
    void measuresIfBreakFlatBranchForGroupWidth() {
        Doc doc = Doc.group(Doc.concat(
            Doc.text("prefix"),
            Doc.ifBreak(Doc.text("-broken-branch"), Doc.text("-flat"))
        ));

        DocExplanation explanation = explain(80, doc);

        GroupDecision decision = explanation.decisions().getFirst();
        assertThat(decision.decision()).isEqualTo(Decision.FLAT);
        assertThat(decision.flatWidth()).isEqualTo("prefix-flat".length());
    }

    @Test
    void carriesPrinterWrapsThroughUntouchedForCallerMerge() {
        Doc doc = Doc.label("java.statement:ExpressionStmt", Doc.text("x"));
        PrinterWrap wrap = new PrinterWrap(
            "method chain",
            "java.expression:MethodCallExpr",
            "foo().bar()…",
            78,
            40,
            8
        );

        FormatterOptions options = TestFormatterOptions.forLayout(
            40,
            FormatterOptions.IndentStyle.SPACE,
            2,
            FormatterOptions.LineEnding.LF,
            false
        );
        DocExplanation explanation = new DocExplainRenderer(options).explain(doc, java.util.List.of(wrap));

        // The renderer passes printer wraps through verbatim and exposes their labels so callers can de-duplicate the
        // renderer-trace forced breaks they correspond to.
        assertThat(explanation.printerWraps()).containsExactly(wrap);
        assertThat(explanation.printerWrapLabels()).containsExactly("java.expression:MethodCallExpr");
    }

    @Test
    void recordsPerSeparatorFillDecisionsWithWidthMath() {
        // A fill packs greedily: the first separator still fits the line, the second runs out of room and breaks.
        Doc fill = Doc.label(
            "java.expression:Fill",
            Doc.fill(List.of(
                Doc.text("aaaa"),
                Doc.LINE,
                Doc.text("bbbb"),
                Doc.LINE,
                Doc.text("cccccccccccccc")
            ))
        );

        DocExplanation explanation = explain(20, fill);

        assertThat(explanation.fillDecisions()).singleElement().satisfies(decision -> {
            assertThat(decision.label()).contains("java.expression:Fill");
            assertThat(decision.anyBroke()).isTrue();
            assertThat(decision.separators()).hasSize(2);

            FillDecision.Separator first = decision.separators().get(0);
            assertThat(first.index()).isEqualTo(1);
            assertThat(first.decision()).isEqualTo(Decision.FLAT);
            // "aaaa" reaches column 4; the separator+next content "( )bbbb" is 5 wide and fits the 16 left.
            assertThat(first.startColumn()).isEqualTo(4);
            assertThat(first.available()).isEqualTo(16);
            assertThat(first.flatWidth()).isEqualTo(5);

            FillDecision.Separator second = decision.separators().get(1);
            assertThat(second.index()).isEqualTo(3);
            assertThat(second.decision()).isEqualTo(Decision.BREAK);
            // The flat run reached column 9, leaving only 11, but "( )cccccccccccccc" needs 15, so this separator breaks.
            assertThat(second.startColumn()).isEqualTo(9);
            assertThat(second.available()).isEqualTo(11);
            assertThat(second.flatWidth()).isEqualTo(15);
        });
        assertThat(explanation.brokenFills()).containsExactly(explanation.fillDecisions().getFirst());
    }

    @Test
    void recordsConditionalGroupChosenAlternativeWhenFirstFlatLayoutFits() {
        Doc conditional = Doc.label(
            "java.expression:Conditional",
            Doc.conditionalGroup(List.of(Doc.text("short"), Doc.text("muchlongeralternativelayout")))
        );

        DocExplanation explanation = explain(40, conditional);

        assertThat(explanation.conditionalGroupDecisions()).singleElement().satisfies(decision -> {
            assertThat(decision.label()).contains("java.expression:Conditional");
            assertThat(decision.chosenIndex()).isEqualTo(0);
            assertThat(decision.chosenInBreakMode()).isFalse();
            assertThat(decision.available()).isEqualTo(40);
            // Probing stops at the first fit, so only the chosen alternative was measured.
            assertThat(decision.alternatives()).singleElement().satisfies(alternative -> {
                assertThat(alternative.index()).isEqualTo(0);
                assertThat(alternative.fits()).isTrue();
                assertThat(alternative.flatWidth()).isEqualTo("short".length());
            });
        });
    }

    @Test
    void recordsConditionalGroupBreakModeFallbackAndEachProbedAlternative() {
        // The first flat alternative does not fit the narrow budget and the last alternative contains a forced break,
        // so the renderer falls back to the last alternative in break mode and every alternative was probed.
        Doc conditional = Doc.label(
            "java.expression:Conditional",
            Doc.conditionalGroup(List.of(
                Doc.text("aaaaaaaaaaaaaaaaaaaaaaaaa"),
                Doc.concat(Doc.text("x"), Doc.HARD_LINE, Doc.text("y"))
            ))
        );

        DocExplanation explanation = explain(20, conditional);

        assertThat(explanation.conditionalGroupDecisions()).singleElement().satisfies(decision -> {
            assertThat(decision.chosenIndex()).isEqualTo(1);
            assertThat(decision.chosenInBreakMode()).isTrue();
            assertThat(decision.available()).isEqualTo(20);
            assertThat(decision.alternatives()).hasSize(2);

            ConditionalGroupDecision.Alternative flatCandidate = decision.alternatives().get(0);
            assertThat(flatCandidate.index()).isEqualTo(0);
            assertThat(flatCandidate.fits()).isFalse();
            assertThat(flatCandidate.flatWidth()).isEqualTo(25);

            ConditionalGroupDecision.Alternative fallback = decision.alternatives().get(1);
            assertThat(fallback.index()).isEqualTo(1);
            assertThat(fallback.fits()).isFalse();
            // The fallback contains a hard line break, so its flat width is the NO_FIT sentinel (negative).
            assertThat(fallback.flatWidth()).isNegative();
        });
    }

    @Test
    void prunesStructuralWrappersButKeepsLabelsAndGroups() {
        Doc doc = Doc.concat(
            Doc.text("prefix "),
            Doc.label("java.expression:Inner", Doc.group(Doc.text("x")))
        );

        DocExplanation explanation = explain(40, doc);

        // The bare concat and text leaves are collapsed; only the label and its group survive in the tree.
        assertThat(explanation.tree().children())
                .singleElement()
                .satisfies(label -> {
                    assertThat(label.label()).contains("java.expression:Inner");
                    assertThat(label.children()).singleElement().satisfies(
                        group -> assertThat(group.decision()).isPresent()
                    );
                });
    }
}
