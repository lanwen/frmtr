package dev.lanwen.frmtr.cli;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

final class IndentationRendererTest {

    @Test
    void dotsOnlyLeadingWhitespaceAndLeavesMidLineSpacesIntact() {
        String rendered = IndentationRenderer.render("        return first + second;\n");

        // The eight leading spaces become dots; the single spaces around the operator stay spaces.
        assertThat(rendered).isEqualTo("········return first + second;\n");
    }

    @Test
    void doesNotTouchWhitespaceInsideStringLiterals() {
        String rendered = IndentationRenderer.render("    String gap = \"a    b\";\n");

        assertThat(rendered).isEqualTo("····String gap = \"a    b\";\n");
    }

    @Test
    void dotsTabIndentationOnePerCharacter() {
        String rendered = IndentationRenderer.render("\t\tvalue = 1;\n");

        assertThat(rendered).isEqualTo("··value = 1;\n");
    }

    @Test
    void rendersAllDotsForABlankIndentedLineAndPreservesLineStructure() {
        String rendered = IndentationRenderer.render("class Demo {\n    \n    int value;\n}");

        // The middle line is whitespace-only and becomes all dots; the final line has no terminator and no indentation.
        assertThat(rendered).isEqualTo("class Demo {\n····\n····int value;\n}");
    }

    @Test
    void leavesUnindentedTextUnchanged() {
        String rendered = IndentationRenderer.render("class Demo {}\n");

        assertThat(rendered).isEqualTo("class Demo {}\n");
    }
}
