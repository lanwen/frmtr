package dev.lanwen.frmtr.doc;

import static org.assertj.core.api.Assertions.assertThat;

import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.TestFormatterOptions;
import org.junit.jupiter.api.Test;

final class DocRendererTest {

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
