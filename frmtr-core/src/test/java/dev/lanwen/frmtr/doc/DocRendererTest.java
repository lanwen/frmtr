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
}
