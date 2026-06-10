package dev.lanwen.frmtr.tooling;

import static org.assertj.core.api.Assertions.assertThat;

import dev.lanwen.frmtr.FormatterException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class FormatterRunFailureRendererTest {
    @Test
    void rendersFailedFilesAsOutlinedBlocks() {
        FormatterException.SourceProblem firstProblem = new FormatterException.SourceProblem(
                "(line 2,col 12) Parse error",
                Optional.of(new FormatterException.SourceLocation(2, 12)),
                Optional.of(new FormatterException.SourceLine(1, 1, "class Broken {")),
                List.of(
                        new FormatterException.SourceLine(1, 1, "class Broken {"),
                        new FormatterException.SourceLine(2, 1, "    int value =")));
        FormatterException.SourceProblem secondProblem = new FormatterException.SourceProblem(
                "(line 3,col 12) Parse error",
                Optional.of(new FormatterException.SourceLocation(3, 12)),
                Optional.of(new FormatterException.SourceLine(1, 1, "class Broken {")),
                List.of(
                        new FormatterException.SourceLine(1, 1, "class Broken {"),
                        new FormatterException.SourceLine(3, 1, "    int other =")));
        FormatterException exception = new FormatterException(
                "Unable to parse Java source",
                null,
                List.of(firstProblem, secondProblem));
        FormatRunResult run = new FormatRunResult(List.of(
                failed("src/Broken.java", exception),
                failed("src/Other.java", new IllegalStateException("Cannot read source"))));

        String rendered = FormatterRunFailureRenderer.render(run);

        assertThat(rendered).isEqualTo("""
                ┌─ Unable to parse Java source:
                │ 1  class Broken {
                │ 2      int value =
                │    ┌──────────^
                │    │
                │    └─ (line 2,col 12) Parse error
                │ ⋮
                │ 1  class Broken {
                │ ⋮
                │ 3      int other =
                │    ┌──────────^
                │    │
                │    └─ (line 3,col 12) Parse error
                └─

                ┌─ Cannot read source
                └─""");
    }

    @Test
    void wrapsLongMessagesUnderConnector() {
        FormatterException exception = new FormatterException(
                "Unable to parse Java source",
                null,
                List.of(new FormatterException.SourceProblem(
                        "Parse error message with enough words to force wrapping in the failure renderer without losing connector indentation.",
                        Optional.of(new FormatterException.SourceLocation(2, 7)),
                        Optional.empty(),
                        List.of(new FormatterException.SourceLine(2, 1, "class Broken {")))));
        FormatRunResult run = new FormatRunResult(List.of(failed("src/Broken.java", exception)));

        String rendered = FormatterRunFailureRenderer.render(run);

        assertThat(rendered).isEqualTo("""
                ┌─ Unable to parse Java source:
                │ 2  class Broken {
                │    ┌─────^
                │    │
                │    ├─ Parse error message with enough words to force wrapping in the failure renderer
                │    └─ without losing connector indentation.
                └─""");
    }

    @Test
    void rendersFailedRunAsStructuredDiagnosticText() {
        FormatterException.SourceProblem firstProblem = new FormatterException.SourceProblem(
                "(line 2,col 12) Parse error",
                Optional.of(new FormatterException.SourceLocation(2, 12)),
                Optional.of(new FormatterException.SourceLine(1, 1, "class Broken {")),
                List.of(
                        new FormatterException.SourceLine(1, 1, "class Broken {"),
                        new FormatterException.SourceLine(2, 1, "    int value =")));
        FormatterException.SourceProblem secondProblem = new FormatterException.SourceProblem(
                "(line 3,col 12) Parse error",
                Optional.of(new FormatterException.SourceLocation(3, 12)),
                Optional.of(new FormatterException.SourceLine(1, 1, "class Broken {")),
                List.of(
                        new FormatterException.SourceLine(1, 1, "class Broken {"),
                        new FormatterException.SourceLine(3, 1, "    int other =")));
        FormatterException exception = new FormatterException(
                "Unable to parse Java source",
                null,
                List.of(firstProblem, secondProblem));
        FormatRunResult run = new FormatRunResult(List.of(failed("src/Broken.java", exception)));

        DiagnosticText diagnostic = FormatterRunFailureRenderer.renderDiagnostic(run);

        assertThat(diagnostic.plainText()).isEqualTo(FormatterRunFailureRenderer.render(run));
        assertThat(diagnostic.lines().get(0).spans())
                .containsExactly(
                        new DiagnosticSpan("┌─ ", DiagnosticStyle.BORDER_GUTTER),
                        new DiagnosticSpan("Unable to parse Java source:", DiagnosticStyle.ERROR_TEXT));
        assertThat(diagnostic.lines())
                .anySatisfy(line -> assertThat(line.spans())
                        .containsExactly(
                                new DiagnosticSpan("│ ", DiagnosticStyle.BORDER_GUTTER),
                                new DiagnosticSpan("2", DiagnosticStyle.LINE_NUMBER),
                                new DiagnosticSpan("  ", DiagnosticStyle.BORDER_GUTTER),
                                new DiagnosticSpan("    int value =", DiagnosticStyle.SOURCE_TEXT)));
        assertThat(diagnostic.lines())
                .anySatisfy(line -> assertThat(line.spans())
                        .containsExactly(
                                new DiagnosticSpan("│ ", DiagnosticStyle.BORDER_GUTTER),
                                new DiagnosticSpan("   ", DiagnosticStyle.BORDER_GUTTER),
                                new DiagnosticSpan("┌──────────^", DiagnosticStyle.POINTER)));
        assertThat(diagnostic.lines())
                .anySatisfy(line -> assertThat(line.spans())
                        .containsExactly(
                                new DiagnosticSpan("│ ", DiagnosticStyle.BORDER_GUTTER),
                                new DiagnosticSpan("   ", DiagnosticStyle.BORDER_GUTTER),
                                new DiagnosticSpan("└─ ", DiagnosticStyle.POINTER),
                                new DiagnosticSpan("(line 2,col 12) Parse error", DiagnosticStyle.ERROR_TEXT)));
        assertThat(diagnostic.lines())
                .anySatisfy(line -> assertThat(line.spans())
                        .containsExactly(
                                new DiagnosticSpan("│ ", DiagnosticStyle.BORDER_GUTTER),
                                new DiagnosticSpan("⋮", DiagnosticStyle.GAP)));
        assertThat(diagnostic.lines().get(diagnostic.lines().size() - 1).spans())
                .containsExactly(new DiagnosticSpan("└─", DiagnosticStyle.BORDER_GUTTER));
    }

    private static FormatFileResult failed(String path, Exception exception) {
        return new FormatFileResult(
                Path.of("/workspace").resolve(path),
                Path.of(path),
                FormatFileStatus.FAILED,
                "",
                exception);
    }
}
