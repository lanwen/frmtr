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

    /**
     * The ranking cache must not change any answer: a repeated ranking of the same node in the same context returns the
     * cached index, and a cold {@link DocWidths.Measurement} computing it from scratch returns the identical index at
     * every nesting depth. Pins that memoization is a pure speedup — removing the old depth cap only lets the exact
     * ranking reach deeper, never a different verdict.
     */
    @Test
    void chooseBestFittingIsIdenticalCachedRepeatedAndComputedFreshAtEveryDepth() {
        // Inner node: a fanning-argument flat arm vs a fixed two-line broken arm — the broken arm wins on line count.
        Doc innerFlat = Doc.group(
            Doc.concat(
                Doc.text("wrap("),
                Doc.indent(Doc.concat(
                    Doc.SOFT_LINE,
                    Doc.text("firstArgument"),
                    Doc.text(","),
                    Doc.LINE,
                    Doc.text("secondArgument")
                )),
                Doc.SOFT_LINE,
                Doc.text(")")
            )
        );
        Doc innerBroken = Doc.concat(Doc.text("chosen"), Doc.HARD_LINE, Doc.text(".tail"));

        // Nest the same inner node inside many singleton best-fitting wrappers so it is reached at an identical column
        // whatever the depth, then rank it directly and confirm the verdict is stable across depth, repeat, and cold runs.
        int reference = -1;
        for (int wrappers : new int[] {0, 1, 4, 16}) {
            Doc.BestFitting inner = new Doc.BestFitting(List.of(innerFlat, innerBroken), new int[0]);
            Doc nested = inner;
            for (int i = 0; i < wrappers; i++) {
                nested = new Doc.BestFitting(List.of(nested), new int[0]);
            }

            DocWidths.Measurement warm = measurement();
            // Rank the whole nested tree once so the cache is populated for the inner node at its true column.
            warm.measureLineCount(nested, 0, 0, 20);
            int cachedFirst = warm.chooseBestFitting(inner, 0, 0, 20);
            int cachedRepeat = warm.chooseBestFitting(inner, 0, 0, 20);
            int cold = measurement().chooseBestFitting(inner, 0, 0, 20);

            assertThat(cachedFirst).as("cached==repeat at %d wrappers", wrappers).isEqualTo(cachedRepeat);
            assertThat(cold).as("cold==cached at %d wrappers", wrappers).isEqualTo(cachedFirst);
            // The broken arm (index 1) is the exact winner and does not drift with nesting depth.
            assertThat(cold).as("winner is depth-invariant").isEqualTo(1);
            if (reference < 0) {
                reference = cold;
            }
            assertThat(cold).isEqualTo(reference);
        }
    }

    /**
     * The cache is keyed per (node, indent, start column), not per node: the SAME best-fitting node ranked at two
     * different start columns within one {@link DocWidths.Measurement} must yield each context's own correct winner,
     * and each cached answer must match a cold recompute for that context. A single-line flat arm that fits near column
     * 0 but overflows deep into the line flips the winner from the flat arm to the always-fitting broken arm.
     */
    @Test
    void chooseBestFittingKeysPerContextNotPerNode() {
        Doc flatArm = Doc.text("resolveHandle().annotateSource().dedupe()"); // 41 wide
        Doc brokenArm = Doc.concat(Doc.text("resolveHandle()"), Doc.HARD_LINE, Doc.text(".annotateSource()"));
        Doc.BestFitting node = new Doc.BestFitting(List.of(flatArm, brokenArm), new int[0]);
        int lineWidth = 60;

        // Independently: near column 0 the 41-wide flat arm fits (0 lines, 0 overflow) and beats the 1-line broken arm;
        // starting at column 40 it reaches column 81 and overflows, so the fitting broken arm wins instead.
        assertThat(flatFitsAt(node, 0, lineWidth)).as("flat arm fits near column 0").isTrue();
        assertThat(flatFitsAt(node, 40, lineWidth)).as("flat arm overflows deep in the line").isFalse();

        DocWidths.Measurement shared = measurement();
        int nearStart = shared.chooseBestFitting(node, 0, 0, lineWidth);
        int deepStart = shared.chooseBestFitting(node, 0, 40, lineWidth);

        assertThat(nearStart).as("flat arm wins near column 0").isEqualTo(0);
        assertThat(deepStart).as("broken arm wins deep in the line").isEqualTo(1);

        // Each context's cached answer (second call, same Measurement) equals a cold recompute for that same context.
        assertThat(shared.chooseBestFitting(node, 0, 0, lineWidth)).isEqualTo(nearStart);
        assertThat(shared.chooseBestFitting(node, 0, 40, lineWidth)).isEqualTo(deepStart);
        assertThat(measurement().chooseBestFitting(node, 0, 0, lineWidth)).isEqualTo(nearStart);
        assertThat(measurement().chooseBestFitting(node, 0, 40, lineWidth)).isEqualTo(deepStart);
    }

    /** Whether the node's first (flat) alternative fits without overflow when ranked at {@code startColumn}. */
    private static boolean flatFitsAt(Doc.BestFitting node, int startColumn, int lineWidth) {
        return measurement()
            .measureLineCount(node.alternatives().getFirst(), 0, startColumn, lineWidth)
            .fits();
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

        List<Doc> docs = List.of(
            argList,
            fill,
            withSuffix,
            ifBreakDoc,
            bestFitting,
            nestedBestFitting,
            conditional
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
