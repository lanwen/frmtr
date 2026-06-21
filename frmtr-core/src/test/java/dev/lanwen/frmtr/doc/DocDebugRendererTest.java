package dev.lanwen.frmtr.doc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

final class DocDebugRendererTest {

    @Test
    void rendersFlattenedConcatShape() {
        Doc doc = Doc.concat(Doc.text("a"), Doc.concat(Doc.text("b"), Doc.text("c")));

        String rendered = DocDebugRenderer.render(doc);

        assertThat(rendered).isEqualTo(
            """
                Concat
                  Text("a")
                  Text("b")
                  Text("c")"""
        );
    }

    @Test
    void rendersGroupIndentAndBreakChoices() {
        Doc doc = Doc.group(
            Doc.concat(
                Doc.text("call("),
                Doc.indent(
                    Doc.concat(
                        Doc.SOFT_LINE,
                        Doc.ifBreak(
                            Doc.concat(Doc.HARD_LINE, Doc.text("broken")),
                            Doc.concat(Doc.LINE, Doc.text("flat"))
                        )
                    )
                ),
                Doc.SOFT_LINE,
                Doc.text(")")
            )
        );

        String rendered = DocDebugRenderer.render(doc);

        assertThat(rendered).isEqualTo(
            """
                Group
                  Concat
                    Text("call(")
                    Indent
                      Concat
                        SoftLine
                        IfBreak
                          break:
                            Concat
                              HardLine
                              Text("broken")
                          flat:
                            Concat
                              Line
                              Text("flat")
                    SoftLine
                    Text(")")"""
        );
    }

    @Test
    void rendersLabelsAsDebugOnlyProvenance() {
        Doc doc = Doc.label(
            "expression:MethodCallExpr",
            Doc.group(Doc.concat(Doc.text("call("), Doc.SOFT_LINE, Doc.text(")")))
        );

        String rendered = DocDebugRenderer.render(doc);

        assertThat(rendered).isEqualTo(
            """
                Label("expression:MethodCallExpr")
                  Group
                    Concat
                      Text("call(")
                      SoftLine
                      Text(")")"""
        );
    }

    @Test
    void rendersLineSuffixContentAsChild() {
        Doc doc = Doc.concat(
            Doc.text("VALUE"),
            Doc.lineSuffix(Doc.concat(Doc.text(" "), Doc.text("// trailing")))
        );

        String rendered = DocDebugRenderer.render(doc);

        assertThat(rendered).isEqualTo(
            """
                Concat
                  Text("VALUE")
                  LineSuffix
                    Concat
                      Text(" ")
                      Text("// trailing")"""
        );
    }

    @Test
    void rendersFillPartsAsChildren() {
        Doc separator = Doc.concat(Doc.text(","), Doc.LINE);
        Doc doc = Doc.fill(java.util.List.of(Doc.text("a"), separator, Doc.text("b")));

        String rendered = DocDebugRenderer.render(doc);

        assertThat(rendered).isEqualTo(
            """
                Fill
                  Text("a")
                  Concat
                    Text(",")
                    Line
                  Text("b")"""
        );
    }

    @Test
    void rendersConditionalGroupAlternativesUnderNumberedHeaders() {
        Doc doc = Doc.conditionalGroup(
            java.util.List.of(Doc.text("compact"), Doc.concat(Doc.text("broken"), Doc.HARD_LINE, Doc.text("tail")))
        );

        String rendered = DocDebugRenderer.render(doc);

        assertThat(rendered).isEqualTo(
            """
                ConditionalGroup
                  alt 0:
                    Text("compact")
                  alt 1:
                    Concat
                      Text("broken")
                      HardLine
                      Text("tail")"""
        );
    }

    @Test
    void rendersBreakParentMarker() {
        Doc doc = Doc.concat(Doc.text("value"), Doc.BREAK_PARENT);

        String rendered = DocDebugRenderer.render(doc);

        assertThat(rendered).isEqualTo(
            """
                Concat
                  Text("value")
                  BreakParent"""
        );
    }

    @Test
    void rendersGroupIdentityOnIdentifiedGroupAndIfBreak() {
        Doc doc = Doc.group(
            Doc.concat(Doc.text("("), Doc.ifBreak(Doc.text("br"), Doc.text("fl"), "opener"), Doc.text(")")),
            "opener"
        );

        String rendered = DocDebugRenderer.render(doc);

        assertThat(rendered).isEqualTo(
            """
                Group(#opener)
                  Concat
                    Text("(")
                    IfBreak(#opener)
                      break:
                        Text("br")
                      flat:
                        Text("fl")
                    Text(")")"""
        );
    }

    @Test
    void escapesTextValues() {
        Doc doc = Doc.text("quote \" tab\tnewline\nslash\\");

        String rendered = DocDebugRenderer.render(doc);

        assertThat(rendered).isEqualTo("Text(\"quote \\\" tab\\tnewline\\nslash\\\\\")");
    }
}
