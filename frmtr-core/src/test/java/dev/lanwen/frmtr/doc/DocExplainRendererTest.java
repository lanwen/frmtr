package dev.lanwen.frmtr.doc;

import static org.assertj.core.api.Assertions.assertThat;

import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.TestFormatterOptions;
import dev.lanwen.frmtr.doc.DocExplanation.Decision;
import dev.lanwen.frmtr.doc.DocExplanation.ForcedBreak;
import dev.lanwen.frmtr.doc.DocExplanation.GroupDecision;
import dev.lanwen.frmtr.doc.PrinterWrap;
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
