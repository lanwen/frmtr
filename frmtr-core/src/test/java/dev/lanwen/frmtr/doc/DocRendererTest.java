package dev.lanwen.frmtr.doc;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.lanwen.frmtr.FormatterOptions;
import org.junit.jupiter.api.Test;

final class DocRendererTest {
    @Test
    void keepsGroupFlatWhenItFits() {
        Doc doc = Doc.group(Doc.concat(Doc.text("call("), Doc.indent(Doc.concat(Doc.SOFT_LINE, Doc.text("value"))), Doc.SOFT_LINE, Doc.text(")")));

        String rendered = new DocRenderer(new FormatterOptions(40, FormatterOptions.IndentStyle.SPACE, 2, FormatterOptions.LineEnding.LF, false))
                .render(doc);

        assertEquals("call(value)", rendered);
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

        assertEquals("""
                call(
                  firstArgument,
                  secondArgument
                )""", rendered);
    }
}
