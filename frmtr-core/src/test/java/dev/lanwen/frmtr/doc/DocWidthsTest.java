package dev.lanwen.frmtr.doc;

import static org.assertj.core.api.Assertions.assertThat;

import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.TestFormatterOptions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

final class DocWidthsTest {

    private static final int INDENT_WIDTH = 2;

    private static DocRenderer renderer(int lineWidth) {
        return new DocRenderer(
            TestFormatterOptions.forLayout(
                lineWidth,
                FormatterOptions.IndentStyle.SPACE,
                INDENT_WIDTH,
                FormatterOptions.LineEnding.LF,
                false
            )
        );
    }

    private static DocWidths.Measurement measurement() {
        DocWidths.Measurement widths = DocWidths.measurement();
        // Match the renderer's indent-unit width so a newline resets the simulated column exactly as the renderer does.
        widths.indentWidth(INDENT_WIDTH);
        return widths;
    }

    private static long renderedNewlines(int lineWidth, Doc doc) {
        return renderer(lineWidth).render(doc).chars().filter(ch -> ch == '\n').count();
    }

    @Test
    void doesNotCacheBoundedOverflowAsFlatWidth() {
        Doc shared = Doc.label("shared", Doc.concat(Doc.text("prefix"), Doc.text("-suffix")));
        Doc outer = Doc.concat(shared, Doc.text("-tail"));
        DocWidths.Measurement widths = DocWidths.measurement();

        assertThat(widths.fits(outer, 3)).isFalse();

        assertThat(widths.flatWidth(shared)).isEqualTo("prefix-suffix".length());
        assertThat(widths.fits(shared, "prefix-suffix".length())).isTrue();
    }

    @Test
    void reusesCompleteWidthFoundDuringBoundedFit() {
        Doc shared = Doc.label("shared", Doc.text("prefix-suffix"));
        DocWidths.Measurement widths = DocWidths.measurement();

        assertThat(widths.fits(shared, 3)).isFalse();

        assertThat(widths.flatWidth(shared)).isEqualTo("prefix-suffix".length());
        assertThat(widths.fits(shared, "prefix-suffix".length())).isTrue();
    }

    @Test
    void measureLineCountCountsNewlinesAndOverflowAtEachBreakAndAtEnd() {
        // Two hard breaks make three lines; the middle line "0123456789ABCDE" is 15 wide and the last "tail" is 4 wide.
        // At line width 10, the middle line overflows by 5 and the trailing line does not, so overflow is 5 total.
        Doc doc = Doc.concat(
            Doc.text("short"),
            Doc.HARD_LINE,
            Doc.text("0123456789ABCDE"),
            Doc.HARD_LINE,
            Doc.text("tail")
        );

        DocWidths.LineCount count = measurement().measureLineCount(doc, 0, 0, 10);

        assertThat(count.lines()).isEqualTo(2);
        assertThat(count.overflow()).isEqualTo(5);
    }

    @Test
    void measureLineCountResetsColumnToIndentAfterANewlineLikeTheRenderer() {
        // The indented continuation begins at column INDENT_WIDTH, so an 8-char token on it reaches column 10 and, at a
        // line width of 6, overflows by 4 — proving the simulation reset the column to the indent, not to zero.
        Doc doc = Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.text("continue")));

        DocWidths.LineCount count = measurement().measureLineCount(doc, 0, 0, 6);

        assertThat(count.lines()).isEqualTo(1);
        assertThat(count.overflow()).isEqualTo(INDENT_WIDTH + "continue".length() - 6);
    }

    @Test
    void measureLineCountReadsTheAnchoredLevelNotTheAmbientIndent() {
        // The atIndent(1, …) resets the base to an absolute level 1 (column 2), discarding the ambient indent 3 the
        // walk is entered at (column 6). At line width 10 the 12-wide continuation therefore reaches column 14 and
        // overflows by 4 — the anchored column, not the ambient one it would reach (column 18, overflow 8).
        Doc doc = Doc.atIndent(1, Doc.concat(Doc.text("head"), Doc.HARD_LINE, Doc.text("continuation")));

        DocWidths.LineCount count = measurement().measureLineCount(doc, 3, 0, 10);

        assertThat(count.lines()).isEqualTo(1);
        assertThat(count.overflow()).isEqualTo(INDENT_WIDTH * 1 + "continuation".length() - 10);
    }

    /**
     * The anti-drift guard for #205: the line-count simulation and the real renderer are separate walks, so this pins
     * that they cannot diverge — {@code measureLineCount(doc).lines()} must equal the number of newlines the renderer
     * actually emits for the same document at the same line width. If a future edit changes one walk's newline, mode,
     * fill, if-break, line-suffix, or best-fitting behavior without the other, this test fails.
     */
    @ParameterizedTest
    @MethodSource("congruenceCases")
    void measureLineCountMatchesRenderedNewlineCount(int lineWidth, Doc doc) {
        assertThat((long) measurement().measureLineCount(doc, 0, 0, lineWidth).lines())
            .isEqualTo(renderedNewlines(lineWidth, doc));
    }

    private static List<org.junit.jupiter.params.provider.Arguments> congruenceCases() {
        Doc commaLine = Doc.concat(Doc.text(","), Doc.LINE);
        Doc argList = Doc.group(
            Doc.concat(
                Doc.text("invoke("),
                Doc.indent(Doc.concat(
                    Doc.SOFT_LINE,
                    Doc.text("first"),
                    Doc.text(","),
                    Doc.LINE,
                    Doc.text("second"),
                    Doc.text(","),
                    Doc.LINE,
                    Doc.text("third")
                )),
                Doc.SOFT_LINE,
                Doc.text(")")
            )
        );
        Doc fill = Doc.fill(List.of(
            Doc.text("alpha"),
            commaLine,
            Doc.text("beta"),
            commaLine,
            Doc.text("gammagamma"),
            commaLine,
            Doc.text("delta")
        ));
        Doc withSuffix = Doc.concat(
            Doc.text("value"),
            Doc.lineSuffix(Doc.text(" // trailing note")),
            Doc.text(";"),
            Doc.HARD_LINE,
            Doc.text("next")
        );
        Doc ifBreakDoc = Doc.group(
            Doc.concat(
                Doc.text("prefixprefixprefix"),
                Doc.SOFT_LINE,
                Doc.ifBreak(Doc.concat(Doc.HARD_LINE, Doc.text("broke")), Doc.text("flat"))
            )
        );
        Doc bestFitting = Doc.bestFitting(List.of(
            Doc.text("root().alpha().beta()"),
            Doc.concat(Doc.text("root()"), Doc.HARD_LINE, Doc.text(".alpha()"), Doc.HARD_LINE, Doc.text(".beta()"))
        ));
        Doc nestedBestFitting = Doc.bestFitting(List.of(
            Doc.text("outerFlat"),
            Doc.concat(
                Doc.text("outerBroken"),
                Doc.HARD_LINE,
                Doc.bestFitting(List.of(
                    Doc.text("innerFlatInnerFlat"),
                    Doc.concat(Doc.text("innerBroken"), Doc.HARD_LINE, Doc.text("innerTail"))
                ))
            )
        ));
        Doc conditional = Doc.conditionalGroup(List.of(
            Doc.text("compactcompactcompact"),
            Doc.concat(Doc.text("wide("), Doc.indent(Doc.concat(Doc.SOFT_LINE, Doc.text("payloadpayload"))), Doc.SOFT_LINE, Doc.text(")"))
        ));
        // An anchor nested under ambient indents: the walk and the renderer must reset the continuation column to the
        // absolute level identically, or their newline counts drift once a continuation crosses the width.
        Doc atIndentDoc = Doc.indent(Doc.indent(
            Doc.atIndent(0, Doc.concat(Doc.text("head"), Doc.HARD_LINE, Doc.text("anchoredContinuationLine")))
        ));

        List<Doc> docs = List.of(
            argList,
            fill,
            withSuffix,
            ifBreakDoc,
            bestFitting,
            nestedBestFitting,
            conditional,
            atIndentDoc
        );
        List<org.junit.jupiter.params.provider.Arguments> cases = new java.util.ArrayList<>();
        // Sweep several line widths (20 is the configured minimum) so both the fitting (flat) and overflowing (broken)
        // branch of every construct is exercised against the renderer.
        for (int lineWidth : new int[] {20, 24, 28, 32, 40, 80}) {
            for (Doc doc : docs) {
                cases.add(org.junit.jupiter.params.provider.Arguments.of(lineWidth, doc));
            }
        }
        return cases;
    }
}
