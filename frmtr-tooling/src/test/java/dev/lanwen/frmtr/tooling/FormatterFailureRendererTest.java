package dev.lanwen.frmtr.tooling;

import static org.assertj.core.api.Assertions.assertThat;

import dev.lanwen.frmtr.FormatterException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class FormatterFailureRendererTest {
    @Test
    void rendersDeclarationLineBeforeNearbyContextWhenItIsOutsideContextWindow() {
        FormatterException exception = new FormatterException(
                "Unable to parse Java source",
                null,
                List.of(new FormatterException.SourceProblem(
                        "(line 8,col 20) Parse error",
                        Optional.of(new FormatterException.SourceLocation(8, 20)),
                        Optional.of(new FormatterException.SourceLine(2, 1, "    void method() {")),
                        List.of(
                                new FormatterException.SourceLine(6, 1, "        int before4 = 4;"),
                                new FormatterException.SourceLine(7, 1, "        int before5 = 5;"),
                                new FormatterException.SourceLine(8, 1, "        var value =")))));

        String rendered = FormatterFailureRenderer.render(exception);

        assertThat(rendered).isEqualTo("""
                Unable to parse Java source:
                2      void method() {
                6          int before4 = 4;
                7          int before5 = 5;
                8          var value =
                   -------------------^
                (line 8,col 20) Parse error""");
    }

    @Test
    void rendersFormatterFailureAsStructuredDiagnosticText() {
        FormatterException exception = new FormatterException(
                "Unable to parse Java source",
                null,
                List.of(new FormatterException.SourceProblem(
                        "(line 8,col 20) Parse error",
                        Optional.of(new FormatterException.SourceLocation(8, 20)),
                        Optional.of(new FormatterException.SourceLine(2, 1, "    void method() {")),
                        List.of(
                                new FormatterException.SourceLine(6, 1, "        int before4 = 4;"),
                                new FormatterException.SourceLine(7, 1, "        int before5 = 5;"),
                                new FormatterException.SourceLine(8, 1, "        var value =")))));

        DiagnosticText diagnostic = FormatterFailureRenderer.renderDiagnostic(exception);

        assertThat(diagnostic.plainText()).isEqualTo(FormatterFailureRenderer.render(exception));
        assertThat(diagnostic.lines().get(0).spans())
                .containsExactly(new DiagnosticSpan("Unable to parse Java source:", DiagnosticStyle.ERROR_TEXT));
        assertThat(diagnostic.lines())
                .anySatisfy(line -> assertThat(line.spans())
                        .containsExactly(
                                new DiagnosticSpan("8", DiagnosticStyle.LINE_NUMBER),
                                new DiagnosticSpan("  ", DiagnosticStyle.BORDER_GUTTER),
                                new DiagnosticSpan("        var value =", DiagnosticStyle.SOURCE_TEXT)));
        assertThat(diagnostic.lines())
                .anySatisfy(line -> assertThat(line.spans())
                        .containsExactly(
                                new DiagnosticSpan("   ", DiagnosticStyle.BORDER_GUTTER),
                                new DiagnosticSpan("-------------------^", DiagnosticStyle.POINTER)));
        assertThat(diagnostic.lines().get(diagnostic.lines().size() - 1).spans())
                .containsExactly(new DiagnosticSpan("(line 8,col 20) Parse error", DiagnosticStyle.ERROR_TEXT));
    }
}
