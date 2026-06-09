package dev.lanwen.frmtr.tooling;

import static org.assertj.core.api.Assertions.assertThat;

import dev.lanwen.frmtr.FormatterException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class FormatterRunFailureRendererTest {
    @Test
    void rendersFailedFilesAsIndentedBlocks() {
        FormatterException exception = new FormatterException(
                "Unable to parse Java source",
                null,
                List.of(new FormatterException.SourceProblem(
                        "(line 2,col 12) Parse error",
                        Optional.of(new FormatterException.SourceLocation(2, 12)),
                        Optional.of(new FormatterException.SourceLine(1, 1, "class Broken {")),
                        List.of(
                                new FormatterException.SourceLine(1, 1, "class Broken {"),
                                new FormatterException.SourceLine(2, 1, "    int value =")))));
        FormatRunResult run = new FormatRunResult(List.of(
                failed("src/Broken.java", exception),
                failed("src/Other.java", new IllegalStateException("Cannot read source"))));

        String rendered = FormatterRunFailureRenderer.render(run);

        assertThat(rendered).isEqualTo("""
                src/Broken.java
                  Unable to parse Java source:
                  1  class Broken {
                  2      int value =
                     -----------^
                  (line 2,col 12) Parse error

                src/Other.java
                  Cannot read source""");
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
