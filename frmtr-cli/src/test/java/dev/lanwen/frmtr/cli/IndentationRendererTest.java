package dev.lanwen.frmtr.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.lanwen.frmtr.IndentedSource;
import dev.lanwen.frmtr.IndentedSource.Line;
import java.util.List;
import org.junit.jupiter.api.Test;

final class IndentationRendererTest {

    /** A structural line at the given indent level (its leading whitespace is a formatter-chosen indent). */
    private static Line block(int level) {
        return new Line(true, level);
    }

    /** A non-structural line (a text-block interior); its level is not meaningful. */
    private static Line literal() {
        return new Line(false, 0);
    }

    private static String render(String text, Line... lines) {
        return IndentationRenderer.render(new IndentedSource(text, List.of(lines)));
    }

    @Test
    void rendersABlockIndentAsOnlyTheColumnsItAddsOverThePreviousLine() {
        // void at level 1 under a class body: +4 over the class opener -> 4 dots. A statement at level 2 under it:
        // +4 over the method -> the shared 4 columns stay blank and only its added 4 are dots.
        String rendered = render(
            "class Order {\n    void place() {\n        total = 1;\n",
            block(0),
            block(1),
            block(2)
        );

        assertThat(rendered).isEqualTo("class Order {\n····void place() {\n    ····total = 1;\n");
    }

    @Test
    void rendersADedentAsPlainSpacesBecauseItAddsNoColumns() {
        // The first structural line opens the fragment's structure (8 dots), then the closing braces step back out: a
        // dedent adds nothing over the line above, so each renders as plain spaces.
        String rendered = render(
            "        total = 1;\n    }\n}\n",
            block(2),
            block(1),
            block(0)
        );

        assertThat(rendered).isEqualTo("········total = 1;\n    }\n}\n");
    }

    @Test
    void marksAContinuationWithAVerticalEllipsisAndDotsForItsOffset() {
        // A chain selector at level 4 continues the statement at level 2 (a two-level jump = continuation): 8 blank
        // columns for the statement, then the ellipsis, then dots for the rest of the 8-column offset (the selector's
        // own leading "." supplies the eighth visible dot).
        String rendered = render(
            "        verifier.assertEachRoute(handler -> assertThat(handler)\n"
                + "                .extracting(HandlerConfig::identifier)\n",
            block(2),
            block(4)
        );

        assertThat(rendered).isEqualTo(
            "········verifier.assertEachRoute(handler -> assertThat(handler)\n"
                + "        ⋮·······.extracting(HandlerConfig::identifier)\n"
        );
    }

    @Test
    void marksEveryConsecutiveSameDepthContinuationLine() {
        // Both selectors sit at level 4 over the statement at level 2; the second is marked exactly like the first
        // even though the line above it is already at the same depth (the whole continuation run is marked).
        String rendered = render(
            "        verifier.assertEachRoute(handler -> assertThat(handler)\n"
                + "                .extracting(HandlerConfig::identifier)\n"
                + "                .containsOnly(\"primaryValue\")\n",
            block(2),
            block(4),
            block(4)
        );

        assertThat(rendered).isEqualTo(
            "········verifier.assertEachRoute(handler -> assertThat(handler)\n"
                + "        ⋮·······.extracting(HandlerConfig::identifier)\n"
                + "        ⋮·······.containsOnly(\"primaryValue\")\n"
        );
    }

    @Test
    void treatsAStatementInsideABlockOpenedWithinAContinuationAsABlockNotAContinuation() {
        // A block-lambda body opens a real block on a continuation line ending in "{": its statements are blocks, so
        // the trailing brace resets the baseline and the statement renders as delta dots, not a continuation. The
        // enclosing method/statement lines establish the block baseline the chain then continues from.
        String rendered = render(
            "class Demo {\n"
                + "    void method() {\n"
                + "        Object handle = coordinator.attach(\n"
                + "            AbstractChainFactory\n"
                + "                    .next(AbstractEvent.Step.class, item -> {\n"
                + "                        item.target();\n",
            block(0),
            block(1),
            block(2),
            block(3),
            block(5),
            block(6)
        );

        // AbstractChainFactory (level 3) is a one-level wrap that reads as a block and moves the baseline to level 3;
        // ".next(... {" (level 5) is then a two-level continuation over that baseline (12 blank columns, then the
        // marker); and item.target() (level 6) is a block because the ".next(... {" line above it opened a real lambda
        // body, so its indentation renders as delta dots.
        assertThat(rendered).isEqualTo(
            "class Demo {\n"
                + "····void method() {\n"
                + "    ····Object handle = coordinator.attach(\n"
                + "        ····AbstractChainFactory\n"
                + "            ⋮·······.next(AbstractEvent.Step.class, item -> {\n"
                + "                    ····item.target();\n"
        );
    }

    @Test
    void leavesTextBlockInteriorLinesAsUniformDotsPreservingThePreExistingBehavior() {
        // The opening ""\"" is structural (8 dots); the interior lines are literal program data and keep the uniform
        // one-dot-per-character rendering rather than the block/continuation scheme.
        String rendered = render(
            "        var sql = \"\"\"\n            select 1\n            \"\"\";\n",
            block(2),
            literal(),
            literal()
        );

        assertThat(rendered).isEqualTo(
            "········var sql = \"\"\"\n············select 1\n············\"\"\";\n"
        );
    }

    @Test
    void doesNotTouchWhitespaceInsideStringLiteralsOrMidLine() {
        String rendered = render("    String gap = \"a    b\";\n", block(1));

        assertThat(rendered).isEqualTo("····String gap = \"a    b\";\n");
    }

    @Test
    void rendersATabIndentedStructuralLineOneGlyphPerCharacter() {
        // The glyphs are laid over the actual leading-whitespace characters, so a tab-indented level-2 line renders one
        // dot per tab regardless of a tab's display width. Its previous line is the class opener at level 0.
        String rendered = render(
            "class Demo {\n\t\tvalue = 1;\n",
            block(0),
            block(2)
        );

        assertThat(rendered).isEqualTo("class Demo {\n··value = 1;\n");
    }

    @Test
    void rendersABlankIndentedLineAsUniformDotsAndDoesNotLetItMoveTheBaseline() {
        // A whitespace-only line keeps the uniform-dot rendering and must not be read as a block/continuation, so the
        // following statement still measures its delta against the real code line above the blank one.
        String rendered = render(
            "class Demo {\n    \n    int value;\n}",
            block(0),
            block(0),
            block(1),
            block(0)
        );

        assertThat(rendered).isEqualTo("class Demo {\n····\n····int value;\n}");
    }

    @Test
    void preservesEveryLeadingWhitespaceCharacterWhenItExceedsTheStructuralIndent() {
        // A block comment's continuation lines are structural at level 0 yet carry a leading "*"-alignment space that is
        // more whitespace than level 0 implies. The glyphs are laid over the actual characters, so the space is rendered
        // (as a dot on the first such line, added over the "/**" above) and never dropped — the count is preserved.
        String rendered = render(
            "/**\n * Reusable session.\n */\nclass Demo {}\n",
            block(0),
            block(0),
            block(0),
            block(0)
        );

        assertThat(rendered).isEqualTo("/**\n·* Reusable session.\n */\nclass Demo {}\n");
    }

    @Test
    void leavesUnindentedTextUnchanged() {
        String rendered = render("class Demo {}\n", block(0));

        assertThat(rendered).isEqualTo("class Demo {}\n");
    }
}
