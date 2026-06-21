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
    void escapesTextValues() {
        Doc doc = Doc.text("quote \" tab\tnewline\nslash\\");

        String rendered = DocDebugRenderer.render(doc);

        assertThat(rendered).isEqualTo("Text(\"quote \\\" tab\\tnewline\\nslash\\\\\")");
    }
}
