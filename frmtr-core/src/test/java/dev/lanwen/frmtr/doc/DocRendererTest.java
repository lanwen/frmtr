package dev.lanwen.frmtr.doc;

import static org.assertj.core.api.Assertions.assertThat;

import dev.lanwen.frmtr.FormatterOptions;
import org.junit.jupiter.api.Test;

final class DocRendererTest {
    @Test
    void keepsGroupFlatWhenItFits() {
        Doc doc = Doc.group(Doc.concat(Doc.text("call("), Doc.indent(Doc.concat(Doc.SOFT_LINE, Doc.text("value"))), Doc.SOFT_LINE, Doc.text(")")));

        String rendered = new DocRenderer(new FormatterOptions(40, FormatterOptions.IndentStyle.SPACE, 2, FormatterOptions.LineEnding.LF, false))
                .render(doc);

        assertThat(rendered).isEqualTo("call(value)");
    }

    @Test
    void breaksGroupWhenItDoesNotFit() {
        Doc doc = Doc.group(Doc.concat(
                Doc.text("call("),
                Doc.indent(Doc.concat(Doc.SOFT_LINE, Doc.text("firstArgument"), Doc.text(","), Doc.LINE, Doc.text("secondArgument"))),
                Doc.SOFT_LINE,
                Doc.text(")")));

        String rendered = new DocRenderer(new FormatterOptions(20, FormatterOptions.IndentStyle.SPACE, 2, FormatterOptions.LineEnding.LF, false))
                .render(doc);

        assertThat(rendered).isEqualTo("""
                call(
                  firstArgument,
                  secondArgument
                )""");
    }
}
