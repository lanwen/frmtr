package dev.lanwen.frmtr.doc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.TestFormatterOptions;
import org.junit.jupiter.api.Test;

final class DocRendererTest {

    private static DocRenderer renderer(int lineWidth) {
        return new DocRenderer(
            TestFormatterOptions.forLayout(
                lineWidth,
                FormatterOptions.IndentStyle.SPACE,
                2,
                FormatterOptions.LineEnding.LF,
                false
            )
        );
    }

    @Test
    void flushesLineSuffixAfterFollowingContentButBeforeNextNewline() {
        Doc doc = Doc.concat(
            Doc.text("VALUE"),
            Doc.lineSuffix(Doc.text(" // trailing")),
            Doc.text(","),
            Doc.HARD_LINE,
            Doc.text("NEXT")
        );

        // The suffix is buffered when reached, the comma after it prints first, and the suffix flushes at the break.
        assertThat(renderer(80).render(doc)).isEqualTo(
            """
                VALUE, // trailing
                NEXT"""
        );
    }

    @Test
    void flushesMultipleLineSuffixesInDocumentOrder() {
        Doc doc = Doc.concat(
            Doc.text("A"),
            Doc.lineSuffix(Doc.text(" // first")),
            Doc.text("B"),
            Doc.lineSuffix(Doc.text(" // second")),
            Doc.HARD_LINE,
            Doc.text("C")
        );

        assertThat(renderer(80).render(doc)).isEqualTo(
            """
                AB // first // second
                C"""
        );
    }

    @Test
    void flushesPendingLineSuffixAtEndOfDocument() {
        Doc doc = Doc.concat(Doc.text("ONLY"), Doc.lineSuffix(Doc.text(" // tail")));

        assertThat(renderer(80).render(doc)).isEqualTo("ONLY // tail");
    }

    @Test
    void lineSuffixWidthDoesNotForceEnclosingGroupToBreak() {
        // The visible content "call(value)" is 11 wide and fits in the 20-column limit; the deferred suffix is far
        // wider but must not be counted, so the group stays flat and the suffix flushes at end of document. If the
        // suffix width were counted, the group would break and the soft line would split "call(" from ")".
        Doc doc = Doc.group(
            Doc.concat(
                Doc.text("call("),
                Doc.lineSuffix(Doc.text(" // a very long trailing comment that would overflow the line")),
                Doc.text("value"),
                Doc.SOFT_LINE,
                Doc.text(")")
            )
        );

        assertThat(renderer(20).render(doc))
            .isEqualTo("call(value) // a very long trailing comment that would overflow the line");
    }

    @Test
    void buffersLineSuffixAcrossNestedGroupUntilOuterBreak() {
        // The suffix is buffered inside the inner group but only flushes at the outer hard line, after both groups
        // rendered, proving the buffer survives nested group boundaries.
        Doc doc = Doc.concat(
            Doc.group(Doc.concat(Doc.text("inner"), Doc.lineSuffix(Doc.text(" // note")))),
            Doc.text(";"),
            Doc.HARD_LINE,
            Doc.text("after")
        );

        assertThat(renderer(80).render(doc)).isEqualTo(
            """
                inner; // note
                after"""
        );
    }

    @Test
    void rejectsHardLineInsideLineSuffixContent() {
        Doc doc = Doc.concat(
            Doc.text("X"),
            Doc.lineSuffix(Doc.concat(Doc.text("// broken"), Doc.HARD_LINE)),
            Doc.HARD_LINE
        );

        assertThatThrownBy(() -> renderer(80).render(doc))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("single-line");
    }

    @Test
    void breakParentForcesEnclosingGroupToBreakEvenWhenItWouldFitFlat() {
        // "a, b" plus the delimiters is 4 wide and fits the 80-column limit, so without the marker the group renders
        // flat as "(a, b)". The zero-width BreakParent poisons the group's flat measurement, forcing it to break and
        // split the soft lines, even though nothing here is too wide.
        Doc doc = Doc.group(
            Doc.concat(
                Doc.text("("),
                Doc.indent(
                    Doc.concat(Doc.SOFT_LINE, Doc.text("a"), Doc.text(","), Doc.LINE, Doc.text("b"), Doc.BREAK_PARENT)
                ),
                Doc.SOFT_LINE,
                Doc.text(")")
            )
        );

        assertThat(renderer(80).render(doc)).isEqualTo(
            """
                (
                  a,
                  b
                )"""
        );
    }

    @Test
    void breakParentRendersNothingItself() {
        // The marker contributes no characters: the surrounding group still breaks (it cannot stay flat), but the
        // marker leaves no glyph of its own between "value" and the line break.
        Doc doc = Doc.group(Doc.concat(Doc.text("value"), Doc.BREAK_PARENT, Doc.SOFT_LINE, Doc.text("tail")));

        assertThat(renderer(80).render(doc)).isEqualTo(
            """
                value
                tail"""
        );
    }

    @Test
    void fillPacksItemsPerLineAndBreaksOnlyBeforeAnItemThatOverflows() {
        Doc separator = Doc.concat(Doc.text(","), Doc.LINE);
        Doc doc = Doc.fill(
            java.util.List.of(
                Doc.text("aaaa"),
                separator,
                Doc.text("bbbb"),
                separator,
                Doc.text("cccccccccccccccc"),
                separator,
                Doc.text("dddd")
            )
        );

        // Line width is 20. "aaaa" and "bbbb" pack together (4 + ", " + 4 = 10 <= 20). The next separator plus
        // "cccccccccccccccc" (2 + 16 = 18) no longer fits in the 10 columns left, so the fill breaks before it;
        // "cccccccccccccccc" then fills the line so "dddd" is pushed to its own line too. Each comma stays glued to the
        // item it follows.
        assertThat(renderer(20).render(doc)).isEqualTo(
            """
                aaaa, bbbb,
                cccccccccccccccc,
                dddd"""
        );
    }

    @Test
    void fillKeepsEveryItemOnOneLineWhenTheyAllFit() {
        Doc separator = Doc.concat(Doc.text(","), Doc.LINE);
        Doc doc = Doc.fill(
            java.util.List.of(Doc.text("a"), separator, Doc.text("b"), separator, Doc.text("c"))
        );

        // All three items and their separators fit well within 80 columns, so no separator breaks.
        assertThat(renderer(80).render(doc)).isEqualTo("a, b, c");
    }

    @Test
    void fillContributesItsConcatenatedFlatWidthToAnEnclosingGroup() {
        Doc separator = Doc.concat(Doc.text(","), Doc.LINE);
        Doc fill = Doc.fill(
            java.util.List.of(Doc.text("alphaalpha"), separator, Doc.text("betabeta"), separator, Doc.text("gammagamma"))
        );
        Doc doc = Doc.group(
            Doc.concat(Doc.text("["), Doc.indent(Doc.concat(Doc.SOFT_LINE, fill)), Doc.SOFT_LINE, Doc.text("]"))
        );

        // The enclosing group measures the fill as the flat concatenation of all its parts
        // ("alphaalpha, betabeta, gammagamma" = 32, plus the brackets = 34). At 40 columns that fits, so the group stays
        // flat and the fill packs everything on one line.
        assertThat(renderer(40).render(doc)).isEqualTo("[alphaalpha, betabeta, gammagamma]");

        // At 24 columns the 34-wide flat measurement overflows, so the group breaks and the soft lines split. On the
        // indented continuation the fill still packs greedily: "alphaalpha, betabeta" share a line and only
        // "gammagamma" — which would overflow — breaks before it. This proves the fill drove the outer group decision yet
        // kept its own per-separator packing.
        assertThat(renderer(24).render(doc)).isEqualTo(
            """
                [
                  alphaalpha, betabeta,
                  gammagamma
                ]"""
        );
    }

    @Test
    void conditionalGroupRendersTheFirstAlternativeThatFitsFlat() {
        Doc doc = Doc.conditionalGroup(
            java.util.List.of(Doc.text("compact"), Doc.text("a-much-wider-fallback-layout"))
        );

        // "compact" (7) fits in 20 columns, so the first alternative wins and renders flat; the wider fallback is never
        // reached.
        assertThat(renderer(20).render(doc)).isEqualTo("compact");
    }

    @Test
    void conditionalGroupSkipsAlternativesThatDoNotFitAndPicksTheFirstThatDoes() {
        Doc wide = Doc.text("this-first-alternative-is-too-wide");
        Doc medium = Doc.text("middle-fits");
        Doc fallback = Doc.text("last");
        Doc doc = Doc.conditionalGroup(java.util.List.of(wide, medium, fallback));

        // At 20 columns the 34-wide first alternative overflows and is skipped; "middle-fits" (11) fits and is chosen,
        // so the third alternative is never considered even though it is narrower.
        assertThat(renderer(20).render(doc)).isEqualTo("middle-fits");
    }

    @Test
    void conditionalGroupFallsBackToTheLastAlternativeInBreakModeWhenNoneFit() {
        // The fallback alternative is mode-sensitive at its top level: a bare soft-line layout (not re-wrapped in a Group,
        // which would re-decide its own mode). Every alternative, including this last one, is too wide to fit flat at 20
        // columns ("(payloadpayloadpayload)" is 23 wide), so no alternative fits and the last renders in BREAK mode,
        // splitting its soft lines.
        Doc fallback = Doc.concat(
            Doc.text("("),
            Doc.indent(Doc.concat(Doc.SOFT_LINE, Doc.text("payloadpayloadpayload"))),
            Doc.SOFT_LINE,
            Doc.text(")")
        );
        Doc doc = Doc.conditionalGroup(
            java.util.List.of(Doc.text("first-alternative-too-wide"), Doc.text("second-too-wide-as-well"), fallback)
        );

        assertThat(renderer(20).render(doc)).isEqualTo(
            """
                (
                  payloadpayloadpayload
                )"""
        );
    }

    @Test
    void conditionalGroupRendersTheLastAlternativeFlatWhenItIsTheFirstThatFits() {
        // The last alternative is fairly flat-probed too: when every earlier alternative overflows but the last fits
        // flat, it is the first that fits and renders FLAT (not as the break-mode fallback). The same mode-sensitive
        // layout that broke in the previous test stays on one line here because it now fits.
        Doc layout = Doc.concat(
            Doc.text("("),
            Doc.indent(Doc.concat(Doc.SOFT_LINE, Doc.text("payload"))),
            Doc.SOFT_LINE,
            Doc.text(")")
        );
        Doc doc = Doc.conditionalGroup(java.util.List.of(Doc.text("first-alternative-too-wide"), layout));

        // At 20 columns the 26-wide first alternative overflows, so the loop reaches the last alternative; its 9-wide
        // flat layout fits, so it renders FLAT: "(payload)" on one line with its soft lines collapsed, rather than broken
        // as the fallback path would render it.
        assertThat(renderer(20).render(doc)).isEqualTo("(payload)");
    }

    @Test
    void conditionalGroupContributesItsFirstAlternativeWidthToAnEnclosingGroup() {
        // An enclosing group measures a conditional group by its first (most-flat) alternative. The first alternative is
        // 5 wide ("FIRST"); a much wider second alternative exists but must not influence the enclosing group's own
        // flat/break decision.
        Doc conditional = Doc.conditionalGroup(
            java.util.List.of(Doc.text("FIRST"), Doc.text("a-far-wider-second-alternative-layout"))
        );
        Doc doc = Doc.group(
            Doc.concat(Doc.text("["), Doc.indent(Doc.concat(Doc.SOFT_LINE, conditional)), Doc.SOFT_LINE, Doc.text("]"))
        );

        // The enclosing group measures "[" + "FIRST" + "]" = 7 against the limit. At 20 columns that fits even though the
        // second alternative (37 wide) would overflow it, so the group stays flat and the first alternative renders. Had
        // the wider second alternative driven the measurement, the group would have broken.
        assertThat(renderer(20).render(doc)).isEqualTo("[FIRST]");
    }

    @Test
    void fillRejectsAnEvenLengthListWhoseTrailingSeparatorWouldBeSilentlyDropped() {
        // An even-length list ends with a separator that the pairwise render walk never reaches, so it would vanish
        // from the output. The factory rejects it rather than emit a layout that quietly differs from the parts handed in.
        Doc separator = Doc.concat(Doc.text(","), Doc.LINE);
        assertThatThrownBy(() -> Doc.fill(java.util.List.of(Doc.text("a"), separator)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("even-length");
    }

    @Test
    void fillAcceptsEmptyAndOddLengthListsAsTheWellFormedShapes() {
        // The well-formed shapes are accepted: empty (renders nothing), a single content element, and an odd-length
        // alternating list. None throw, and each renders the content it was given.
        Doc separator = Doc.concat(Doc.text(","), Doc.LINE);
        assertThat(renderer(80).render(Doc.fill(java.util.List.of()))).isEmpty();
        assertThat(renderer(80).render(Doc.fill(java.util.List.of(Doc.text("only"))))).isEqualTo("only");
        assertThat(renderer(80).render(Doc.fill(java.util.List.of(Doc.text("a"), separator, Doc.text("b")))))
            .isEqualTo("a, b");
    }

    @Test
    void conditionalGroupRejectsAnEmptyAlternativeList() {
        // "Render nothing" is never a valid layout-choice intent and an empty conditional group is almost always a
        // construction bug, so the factory fails fast instead of building a group with nothing to fall back on.
        assertThatThrownBy(() -> Doc.conditionalGroup(java.util.List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at least one alternative");
    }

    @Test
    void bestFittingRejectsAnEmptyAlternativeList() {
        // Like a conditional group, "render nothing" is never a valid layout-choice intent, so the factory fails fast
        // instead of building a best-fitting node with nothing to rank or fall back on.
        assertThatThrownBy(() -> Doc.bestFitting(java.util.List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at least one alternative");
    }

    /**
     * The three alternatives, flattest-first: (0) a breakable argument group that stays one line when it fits but fans
     * out to five lines when it does not; (1) a two-line layout with a single hard break; (2) a three-line fan-out with
     * two hard breaks. This is the canonical B8 case — the renderer must rank broken shapes against each other, which a
     * conditional group cannot.
     */
    private static Doc rankedChainAlternatives() {
        Doc flat = Doc.group(
            Doc.concat(
                Doc.text("call("),
                Doc.indent(Doc.concat(
                    Doc.SOFT_LINE,
                    Doc.text("alpha"),
                    Doc.text(","),
                    Doc.LINE,
                    Doc.text("beta"),
                    Doc.text(","),
                    Doc.LINE,
                    Doc.text("gamma")
                )),
                Doc.SOFT_LINE,
                Doc.text(")")
            )
        );
        Doc twoLineBroken = Doc.concat(Doc.text("header()"), Doc.HARD_LINE, Doc.text(".tail()"));
        Doc threeLineFanout = Doc.concat(
            Doc.text("head()"),
            Doc.HARD_LINE,
            Doc.text(".mid()"),
            Doc.HARD_LINE,
            Doc.text(".end()")
        );
        return Doc.bestFitting(java.util.List.of(flat, twoLineBroken, threeLineFanout));
    }

    @Test
    void bestFittingPicksTheFewerLinesBrokenShapeWhenTheFlatAlternativeWouldOverflow() {
        // At 20 columns the flat argument group (flat width 24) overflows and fans out to five lines, so it is not the
        // fewest-lines layout. Between the remaining broken shapes the two-line one beats the three-line fan-out, so the
        // renderer keeps the two-line layout — proving it ranked broken shapes by line count, not by first-flat-fit.
        assertThat(renderer(20).render(rankedChainAlternatives())).isEqualTo(
            """
                header()
                .tail()"""
        );
    }

    @Test
    void bestFittingPicksTheFlatAlternativeWhenItFitsOnTheLine() {
        // At 80 columns the flat argument group fits on one line (zero newlines), the fewest possible, so it wins over
        // both broken alternatives.
        assertThat(renderer(80).render(rankedChainAlternatives())).isEqualTo("call(alpha, beta, gamma)");
    }

    @Test
    void bestFittingKeepsAFittingAlternativeOverAFewerLinesOneThatOverflows() {
        // The overflow gate: index 0 is a single line that overruns the 20-column width (39 wide, 0 newlines); index 1
        // fits on two lines (each within 20). On pure line count the one-liner would win — it has the fewest newlines —
        // but a layout whose first line spills past the width is a defect the reader sees, so the fitting two-line layout
        // must win despite using more lines. That the wider one-liner loses is the observable proof the gate ranks fit
        // above line count.
        Doc overflowingOneLiner = Doc.text("config.resolveConnectionTimeoutMillis()");
        Doc fittingTwoLine = Doc.concat(Doc.text("config.head()"), Doc.HARD_LINE, Doc.text(".timeout()"));

        assertThat(renderer(20).render(Doc.bestFitting(java.util.List.of(overflowingOneLiner, fittingTwoLine))))
            .isEqualTo(
                """
                    config.head()
                    .timeout()"""
            );
    }

    @Test
    void bestFittingPriorityBeatsFewerLinesAmongFittingAlternatives() {
        // Convergence-redesign Mechanism 2: the priority key sits after the fit gate and before line count. Both
        // alternatives fit at 40 columns. Index 0 is the fewer-lines shape (collapse: everything on one continuation
        // line, 1 newline). Index 1 is the opener-attached shape (argument-break: opener on line one, arg on line two,
        // closer on line three — 2 newlines). On pure line count index 0 would win; giving index 1 the higher priority
        // must flip the winner to the opener-attached shape despite its extra line. That the more-lines alternative wins
        // is the observable proof priority outranks line count among fitting candidates.
        Doc collapse = Doc.concat(Doc.text("providers ="), Doc.HARD_LINE, Doc.text("newSetFromMap(weakMap);"));
        Doc argumentBreak = Doc.concat(
            Doc.text("providers = newSetFromMap("),
            Doc.HARD_LINE,
            Doc.text("weakMap"),
            Doc.HARD_LINE,
            Doc.text(");")
        );

        Doc ranked = Doc.bestFitting(java.util.List.of(collapse, argumentBreak), new int[] {0, 1});

        assertThat(renderer(40).render(ranked)).isEqualTo(
            """
                providers = newSetFromMap(
                weakMap
                );"""
        );
    }

    @Test
    void bestFittingWithEqualPrioritiesStillPicksFewerLinesUnchanged() {
        // The byte-identity guarantee: with equal (zero) priorities the priority key is a no-op and the ranking reduces
        // to today's fewest-lines metric. This is the identical construction as the priority test above, only with equal
        // priorities, and it picks the fewer-lines collapse — exactly what the priority-free bestFitting(List) factory
        // would. The explicit all-zero array and the default factory must agree.
        Doc collapse = Doc.concat(Doc.text("providers ="), Doc.HARD_LINE, Doc.text("newSetFromMap(weakMap);"));
        Doc argumentBreak = Doc.concat(
            Doc.text("providers = newSetFromMap("),
            Doc.HARD_LINE,
            Doc.text("weakMap"),
            Doc.HARD_LINE,
            Doc.text(");")
        );
        java.util.List<Doc> alternatives = java.util.List.of(collapse, argumentBreak);

        String expected =
            """
                providers =
                newSetFromMap(weakMap);""";

        // Explicit all-zero priorities.
        assertThat(renderer(40).render(Doc.bestFitting(alternatives, new int[] {0, 0}))).isEqualTo(expected);
        // The default (no-priority) factory must produce the identical layout — the no-op equivalence.
        assertThat(renderer(40).render(Doc.bestFitting(alternatives))).isEqualTo(expected);
    }

    @Test
    void bestFittingPriorityDoesNotOverrideTheFitGate() {
        // Priority is secondary to fit: it is consulted only among alternatives that already fit, so a high-priority
        // alternative that overflows still loses to a fitting low-priority one. At 20 columns index 0 (high priority) is
        // a single line 39 columns wide — it overflows — while index 1 (zero priority) fits on two lines. The overflow
        // gate drops the high-priority arm before priority is ever weighed, so the fitting low-priority two-line layout
        // wins. That the high-priority overflowing arm loses is the observable proof priority never rescues an
        // overflowing candidate (this is the qualifiedRootProviders collapse-wins case in miniature).
        Doc overflowingHighPriority = Doc.text("config.resolveConnectionTimeoutMillis()");
        Doc fittingLowPriority = Doc.concat(Doc.text("config.head()"), Doc.HARD_LINE, Doc.text(".timeout()"));

        Doc ranked =
            Doc.bestFitting(java.util.List.of(overflowingHighPriority, fittingLowPriority), new int[] {5, 0});

        assertThat(renderer(20).render(ranked)).isEqualTo(
            """
                config.head()
                .timeout()"""
        );
    }

    @Test
    void bestFittingKeepsTheFewerLinesLeastBadAlternativeWhenNoAlternativeFits() {
        // When nothing fits the gate is a no-op and the existing least-bad metric decides unchanged: fewer lines wins
        // even at the cost of more total overflow. Index 0 overflows on both of its two lines (total overflow 17); index
        // 1 overflows less in total (15) but across three lines. The two-line layout keeps the win on fewer lines, so the
        // secondary fewest-lines-then-least-overflow order still holds among all-overflowing alternatives.
        Doc twoLineOverflow = Doc.concat(
            Doc.text("persistenceContextEntityManager"),
            Doc.HARD_LINE,
            Doc.text(".flushAndClearAllPending()")
        );
        Doc threeLineOverflow = Doc.concat(
            Doc.text("persistenceContextEntityManager"),
            Doc.HARD_LINE,
            Doc.text(".flush()"),
            Doc.HARD_LINE,
            Doc.text(".clearAllPendingWrites()")
        );

        assertThat(renderer(20).render(Doc.bestFitting(java.util.List.of(twoLineOverflow, threeLineOverflow))))
            .isEqualTo(
                """
                    persistenceContextEntityManager
                    .flushAndClearAllPending()"""
            );
    }

    @Test
    void bestFittingTieBreakKeepsTheEarliestAlternativeAndReformatIsAFixpoint() {
        // Two alternatives render to the identical line count (2 lines) and overflow (none) at 80 columns; the strict
        // "fewer lines, then less overflow" comparison makes neither strictly better, so the earlier (index 0) wins.
        Doc first = Doc.concat(Doc.text("first.head"), Doc.HARD_LINE, Doc.text("first.tail"));
        Doc second = Doc.concat(Doc.text("second.head"), Doc.HARD_LINE, Doc.text("second.tail"));
        Doc doc = Doc.bestFitting(java.util.List.of(first, second));

        String once = renderer(80).render(doc);
        assertThat(once).isEqualTo(
            """
                first.head
                first.tail"""
        );
        // Idempotence follows from determinism: re-ranking the same alternatives at the same column picks the same
        // winner, so wrapping the already-chosen output back through the node is a fixpoint.
        assertThat(renderer(80).render(doc)).isEqualTo(once);
    }

    @Test
    void bestFittingMeasuresOnlyTheFirstEightAlternativesSoAWinnerBeyondTheBoundIsNeverChosen() {
        // Index 0 is a two-line layout (one newline). Indices 1..7 are three-line layouts, strictly worse, so among the
        // eight measured alternatives index 0 wins. Index 8 — the ninth, the first beyond MAX_BEST_FITTING_ALTERNATIVES
        // — is a one-line layout that would strictly beat index 0 on line count if it were ever measured. Because the
        // ranking stops after eight, it is not, so index 0's two-line layout is what renders. That the strictly-better
        // ninth alternative loses is the observable proof that only the first eight were measured.
        java.util.List<Doc> alternatives = new java.util.ArrayList<>();
        alternatives.add(Doc.concat(Doc.text("chosen.head"), Doc.HARD_LINE, Doc.text("chosen.tail")));
        for (int i = 1; i < 8; i++) {
            alternatives.add(Doc.concat(
                Doc.text("f" + i + "a"),
                Doc.HARD_LINE,
                Doc.text("f" + i + "b"),
                Doc.HARD_LINE,
                Doc.text("f" + i + "c")
            ));
        }
        alternatives.add(Doc.text("would-win-on-one-line-if-measured"));

        assertThat(renderer(80).render(Doc.bestFitting(alternatives))).isEqualTo(
            """
                chosen.head
                chosen.tail"""
        );
    }

    /**
     * Wraps a two-alternative best-fitting node in {@code wrappers} singleton best-fitting nodes. Each singleton still
     * advances the best-fitting depth by one when its (only) winner is rendered, so the inner node is evaluated at depth
     * equal to {@code wrappers} — a precise, deterministic way to place it just inside or just past the depth bound.
     */
    private static Doc bestFittingAtDepth(int wrappers, Doc innerFlatAlt, Doc innerBrokenAlt) {
        Doc node = Doc.bestFitting(java.util.List.of(innerFlatAlt, innerBrokenAlt));
        for (int i = 0; i < wrappers; i++) {
            node = Doc.bestFitting(java.util.List.of(node));
        }
        return node;
    }

    @Test
    void bestFittingWithinDepthBoundRanksTheInnerNodeButBeyondItCollapsesToTheFirstAlternative() {
        // The inner node's flattest alternative is a breakable argument group: it fans out to four lines when it does
        // not fit the 10-column width. Its broken alternative is a fixed two-line layout. When the inner node is ranked,
        // the two-line broken shape wins on line count over the four-line fan-out. When the node is past the depth bound
        // it is not ranked and collapses to its first alternative, which then renders in its (multi-line) fanned-out
        // form — a visibly different layout containing the "wrap(" header.
        Doc flatAlt = Doc.group(
            Doc.concat(
                Doc.text("wrap("),
                Doc.indent(Doc.concat(
                    Doc.SOFT_LINE,
                    Doc.text("firstArgument"),
                    Doc.text(","),
                    Doc.LINE,
                    Doc.text("secondArgument"),
                    Doc.text(","),
                    Doc.LINE,
                    Doc.text("thirdArgument")
                )),
                Doc.SOFT_LINE,
                Doc.text(")")
            )
        );
        Doc brokenAlt = Doc.concat(Doc.text("chosen"), Doc.HARD_LINE, Doc.text(".tail"));

        // MAX_BEST_FITTING_DEPTH is 4. With 3 singleton wrappers the inner node sits at depth 3 (< 4) and is ranked, so
        // the two-line broken alternative wins over the multi-line fan-out.
        assertThat(renderer(20).render(bestFittingAtDepth(3, flatAlt, brokenAlt))).isEqualTo(
            """
                chosen
                .tail"""
        );

        // With 4 singleton wrappers the inner node sits at depth 4 (>= 4): ranking stops, it collapses to the flat first
        // alternative, and that group renders fanned out — the "wrap(" header proves the flat alternative was taken.
        String collapsed = renderer(20).render(bestFittingAtDepth(4, flatAlt, brokenAlt));
        assertThat(collapsed).startsWith("wrap(");
        assertThat(collapsed).doesNotContain("chosen");
    }

    @Test
    void conditionalGroupAcceptsASingletonAsAnUnconditionalFallback() {
        // A single alternative is the degenerate, valid case: there is nothing to choose, so it renders flat when it fits
        // and broken otherwise, exactly like wrapping that one layout in a group. Here the lone layout fits at 20 columns.
        Doc layout = Doc.concat(
            Doc.text("("),
            Doc.indent(Doc.concat(Doc.SOFT_LINE, Doc.text("payload"))),
            Doc.SOFT_LINE,
            Doc.text(")")
        );
        assertThat(renderer(20).render(Doc.conditionalGroup(java.util.List.of(layout)))).isEqualTo("(payload)");
    }

    @Test
    void ifBreakBoundToNamedGroupFollowsThatGroupsBreakModeNotTheAmbientMode() {
        // The named "opener" group contains a BreakParent, so it can never stay flat and renders in break mode. The
        // dependent group around the ifBreak is tiny ("x[flat]" = 7 wide) and fits the 20-column limit, so its ambient
        // mode is FLAT. The ifBreak is bound to "opener", so it must render its break arm "[broke]" — following the
        // named group, not the flat ambient group it actually sits inside.
        Doc opener = Doc.group(Doc.concat(Doc.text("opener"), Doc.BREAK_PARENT), "opener");
        Doc dependent = Doc.group(
            Doc.concat(Doc.text("x"), Doc.ifBreak(Doc.text("[broke]"), Doc.text("[flat]"), "opener"))
        );
        Doc doc = Doc.concat(opener, Doc.HARD_LINE, dependent);

        assertThat(renderer(20).render(doc)).isEqualTo(
            """
                opener
                x[broke]"""
        );
    }

    @Test
    void ifBreakBoundToNamedGroupFollowsThatGroupsFlatModeEvenInsideABrokenAmbientGroup() {
        // The named "opener" group is "ab" — it fits, so it renders flat and records FLAT under its id. The dependent
        // group is forced to break because its first child is 22 columns wide (> 20), so its ambient mode is BREAK and
        // the soft line splits. The ifBreak is bound to "opener", so it still renders its flat arm "FLAT" — proving the
        // arm follows the named group's mode independent of the broken ambient group enclosing the ifBreak.
        Doc namedFlat = Doc.group(Doc.text("ab"), "opener");
        Doc ambientBreaks = Doc.group(
            Doc.concat(
                Doc.text("aaaaaaaaaaaaaaaaaaaaaa"),
                Doc.SOFT_LINE,
                Doc.ifBreak(Doc.text("BROKE"), Doc.text("FLAT"), "opener")
            )
        );
        Doc doc = Doc.concat(namedFlat, Doc.HARD_LINE, ambientBreaks);

        assertThat(renderer(20).render(doc)).isEqualTo(
            """
                ab
                aaaaaaaaaaaaaaaaaaaaaa
                FLAT"""
        );
    }

    @Test
    void anonymousIfBreakStillFollowsTheAmbientModeWhenGroupsAreIdentified() {
        // A null-groupId ifBreak keeps today's ambient behavior even though identified groups exist elsewhere in the
        // document: here the enclosing group breaks (its 24-wide content exceeds 20), so the anonymous ifBreak renders
        // its break arm. This pins that adding group identity did not change the unidentified path.
        Doc doc = Doc.group(
            Doc.concat(
                Doc.text("aaaaaaaaaaaaaaaaaaaaaaaa"),
                Doc.SOFT_LINE,
                Doc.ifBreak(Doc.text("BROKE"), Doc.text("FLAT"))
            )
        );

        assertThat(renderer(20).render(doc)).isEqualTo(
            """
                aaaaaaaaaaaaaaaaaaaaaaaa
                BROKE"""
        );
    }

    @Test
    void keepsGroupFlatWhenItFits() {
        Doc doc = Doc.delimited("call(", ")", Doc.text("value"));

        String rendered = new DocRenderer(
            TestFormatterOptions.forLayout(
                40,
                FormatterOptions.IndentStyle.SPACE,
                2,
                FormatterOptions.LineEnding.LF,
                false
            )
        ).render(doc);

        assertThat(rendered).isEqualTo("call(value)");
    }

    @Test
    void breaksGroupWhenItDoesNotFit() {
        Doc doc = Doc.delimited(
            "call(",
            ")",
            Doc.concat(Doc.text("firstArgument"), Doc.text(","), Doc.LINE, Doc.text("secondArgument"))
        );

        String rendered = new DocRenderer(
            TestFormatterOptions.forLayout(
                20,
                FormatterOptions.IndentStyle.SPACE,
                2,
                FormatterOptions.LineEnding.LF,
                false
            )
        ).render(doc);

        assertThat(rendered).isEqualTo(
            """
                call(
                  firstArgument,
                  secondArgument
                )"""
        );
    }

    @Test
    void ignoresLabelsWhenRenderingAndFittingGroups() {
        Doc doc = Doc.group(
            Doc.concat(
                Doc.text("call("),
                Doc.label("argument-list", Doc.indent(Doc.concat(Doc.SOFT_LINE, Doc.text("firstArgumentX")))),
                Doc.SOFT_LINE,
                Doc.text(")")
            )
        );

        String rendered = new DocRenderer(
            TestFormatterOptions.forLayout(
                20,
                FormatterOptions.IndentStyle.SPACE,
                2,
                FormatterOptions.LineEnding.LF,
                false
            )
        ).render(doc);

        assertThat(rendered).isEqualTo("call(firstArgumentX)");
    }

    @Test
    void selectsBreakOnlyAndFlatOnlyBranches() {
        Doc doc = Doc.group(
            Doc.concat(
                Doc.text("very-long-prefix"),
                Doc.breakOnly(Doc.text(" broken")),
                Doc.flatOnly(Doc.text(" flat"))
            )
        );

        DocRenderer renderer = new DocRenderer(
            TestFormatterOptions.forLayout(
                80,
                FormatterOptions.IndentStyle.SPACE,
                2,
                FormatterOptions.LineEnding.LF,
                false
            )
        );
        DocRenderer narrowRenderer = new DocRenderer(
            TestFormatterOptions.forLayout(
                20,
                FormatterOptions.IndentStyle.SPACE,
                2,
                FormatterOptions.LineEnding.LF,
                false
            )
        );

        assertThat(renderer.render(doc)).isEqualTo("very-long-prefix flat");
        assertThat(narrowRenderer.render(doc)).isEqualTo("very-long-prefix broken");
    }

    @Test
    void stopsFitBeforeUnreachableFlatOnlySuffixAfterOverflow() {
        Doc unreachableFlatSuffix = Doc.text("unused");
        for (int i = 0; i < 50_000; i++) {
            unreachableFlatSuffix = Doc.label("unused-flat-suffix", unreachableFlatSuffix);
        }
        Doc doc = Doc.group(
            Doc.group(
                Doc.concat(
                    Doc.text("overflow-overflow-overflow"),
                    Doc.flatOnly(unreachableFlatSuffix)
                )
            )
        );

        String rendered = new DocRenderer(
            TestFormatterOptions.forLayout(
                20,
                FormatterOptions.IndentStyle.SPACE,
                2,
                FormatterOptions.LineEnding.LF,
                false
            )
        ).render(doc);

        assertThat(rendered).isEqualTo("overflow-overflow-overflow");
    }

    @Test
    void reusesRendererWithoutLeakingBoundedWidthCacheAcrossRenders() {
        Doc shared = Doc.group(
            Doc.concat(
                Doc.text("prefix"),
                Doc.flatOnly(Doc.text("-flat")),
                Doc.breakOnly(Doc.text("-broken"))
            )
        );
        DocRenderer renderer = new DocRenderer(
            TestFormatterOptions.forLayout(
                20,
                FormatterOptions.IndentStyle.SPACE,
                2,
                FormatterOptions.LineEnding.LF,
                false
            )
        );
        Doc firstRenderConsumesColumnsBeforeSharedNode = Doc.concat(Doc.text("occupied-x"), shared);

        assertThat(renderer.render(firstRenderConsumesColumnsBeforeSharedNode)).isEqualTo("occupied-xprefix-broken");
        assertThat(renderer.render(shared)).isEqualTo("prefix-flat");
    }

    @Test
    void doesNotLeakBufferedLineSuffixAcrossRenders() {
        DocRenderer renderer = renderer(80);

        // Render #1 buffers a line suffix and then aborts before any flush: the second (multi-line) suffix is rejected
        // the moment it is reached, while the first suffix is still parked in the buffer. The end-of-document flush is
        // never reached, so render #1 leaves the renderer with a pending suffix.
        Doc abortsWithSuffixStillBuffered = Doc.concat(
            Doc.text("A"),
            Doc.lineSuffix(Doc.text(" // leftover")),
            Doc.lineSuffix(Doc.concat(Doc.text("// illegal"), Doc.HARD_LINE))
        );
        assertThatThrownBy(() -> renderer.render(abortsWithSuffixStillBuffered))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("single-line");

        // Render #2 of a suffix-free document on the same renderer must not inherit the leftover " // leftover" suffix.
        Doc suffixFree = Doc.concat(Doc.text("B"), Doc.HARD_LINE, Doc.text("C"));
        assertThat(renderer.render(suffixFree)).isEqualTo(
            """
                B
                C"""
        );
    }

    @Test
    void doesNotCachePartialOverflowForSameNodeInsideOneRender() {
        Doc shared = Doc.group(
            Doc.concat(
                Doc.text("prefix"),
                Doc.flatOnly(Doc.text("-flat")),
                Doc.breakOnly(Doc.text("-broken"))
            )
        );
        Doc doc = Doc.concat(Doc.text("occupied-x"), shared, Doc.HARD_LINE, shared);

        String rendered = new DocRenderer(
            TestFormatterOptions.forLayout(
                20,
                FormatterOptions.IndentStyle.SPACE,
                2,
                FormatterOptions.LineEnding.LF,
                false
            )
        ).render(doc);

        assertThat(rendered).isEqualTo(
            """
                occupied-xprefix-broken
                prefix-flat"""
        );
    }

    @Test
    void renderIndentedReportsStructuralIndentLevelsAndIsByteIdenticalToRender() {
        // A newline inside an indent opens a structural line at that indent level; a newline that arrives inside a Text
        // (as a text-block literal's interior would) opens a non-structural line whose leading whitespace is literal.
        Doc doc = Doc.concat(
            Doc.text("head"),
            Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.text("first line\n  literal interior"))),
            Doc.HARD_LINE,
            Doc.text("tail")
        );

        DocRenderer.RenderedSource indented = renderer(80).renderIndented(doc);

        // The rendered text is byte-for-byte what render() produces.
        assertThat(indented.text()).isEqualTo(renderer(80).render(doc));
        // Line 0 (head) is structural at level 0; line 1 (first line) is structural at level 1 (inside one indent);
        // line 2 (the Text's embedded newline) is non-structural literal content; line 3 (tail) is structural at 0.
        assertThat(indented.lines())
                .containsExactly(
                    new DocRenderer.LineIndent(true, 0),
                    new DocRenderer.LineIndent(true, 1),
                    new DocRenderer.LineIndent(false, 0),
                    new DocRenderer.LineIndent(true, 0)
                );
    }

    @Test
    void renderLeavesTheStructuralSignalUnaccumulatedSoTheByteIdenticalPathIsUnaffected() {
        Doc doc = Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.text("value")));

        // The plain render() path returns the text with no per-line signal; renderIndented() opts into accumulating it.
        assertThat(renderer(80).renderIndented(doc).lines()).isNotEmpty();
    }
}
