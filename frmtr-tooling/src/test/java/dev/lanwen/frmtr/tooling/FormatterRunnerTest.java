package dev.lanwen.frmtr.tooling;

import static org.assertj.core.api.Assertions.assertThat;

import dev.lanwen.frmtr.FormatterOptions;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FormatterRunnerTest {
    @Test
    void checksFilesInStableOrderWithDiffsAndDeduplication(@TempDir Path dir) throws IOException {
        Path unchanged = write(
                dir.resolve("src/Unchanged.java"),
                """
                class Unchanged {

                    int value;
                }
                """);
        Path changed = write(dir.resolve("src/Changed.java"), "class Changed{int value;}");

        FormatRunResult run = FormatterRunner.check(
                dir,
                List.of(unchanged, changed, changed),
                FormatterOptions.defaults(),
                true);
        List<FormatFileResult> results = run.results();

        assertThat(results)
                .extracting(FormatFileResult::displayPath)
                .containsExactly(Path.of("src/Changed.java"), Path.of("src/Unchanged.java"));
        assertThat(run.hasChanges()).isTrue();
        assertThat(run.hasFailures()).isFalse();
        assertThat(run.changedCount()).isEqualTo(1);
        assertThat(results.getFirst().status()).isEqualTo(FormatFileStatus.CHANGED);
        assertThat(results.getFirst().unifiedDiff())
                .hasValueSatisfying(diff -> assertThat(diff)
                        .contains("diff --git origin frmtr")
                        .contains("--- origin\n+++ frmtr")
                        .doesNotContain("a/src/Changed.java")
                        .doesNotContain("b/src/Changed.java")
                        .contains("-class Changed{int value;}")
                        .contains("+class Changed {"));
        assertThat(results.getLast().status()).isEqualTo(FormatFileStatus.UNCHANGED);
    }

    @Test
    void checkCanDecorateDiffsWithTheConfiguredLineWidthRuler(@TempDir Path dir) {
        Path changed = write(dir.resolve("src/Changed.java"), "class Changed{int value;}");
        FormatterOptions options = FormatterOptions.forLayout(
                20,
                FormatterOptions.IndentStyle.SPACE,
                FormatterOptions.DEFAULT_INDENT_WIDTH,
                FormatterOptions.LineEnding.LF,
                true);

        FormatRunResult run = FormatterRunner.check(
                dir,
                List.of(changed),
                options,
                true,
                UnifiedDiffRenderer.RenderMode.LINE_WIDTH_RULER);

        assertThat(run.results().getFirst().unifiedDiff()).hasValueSatisfying(diff -> assertThat(diff)
                .contains("@@ -1 +1,4 @@        ⋮ 20")
                .contains("-class Changed{int va⋮lue;}")
                .contains("                     ⋮+5")
                .contains("+class Changed {     ⋮"));
    }

    @Test
    void writesChangedFilesAndContinuesAfterFailures(@TempDir Path dir) throws IOException {
        Path changed = write(dir.resolve("src/Changed.java"), "class Changed{int value;}");
        Path broken = write(dir.resolve("src/Broken.java"), "class {");

        FormatRunResult run = FormatterRunner.write(dir, List.of(changed, broken), FormatterOptions.defaults());
        List<FormatFileResult> results = run.results();

        assertThat(results)
                .extracting(FormatFileResult::status)
                .containsExactly(FormatFileStatus.FAILED, FormatFileStatus.WRITTEN);
        assertThat(run.hasChanges()).isTrue();
        assertThat(run.hasFailures()).isTrue();
        assertThat(run.changedCount()).isEqualTo(1);
        assertThat(run.failureCount()).isEqualTo(1);
        assertThat(run.firstFailure()).isPresent();
        assertThat(Files.readString(changed, StandardCharsets.UTF_8)).isEqualTo("""
                class Changed {

                    int value;
                }
                """);
    }

    @Test
    void reportsPartialWriteWhenChangedFileCannotBeWritten(@TempDir Path dir) {
        Path readOnly = write(dir.resolve("src/ReadOnly.java"), "class ReadOnly{int value;}");
        assertThat(readOnly.toFile().setWritable(false)).isTrue();

        try {
            FormatRunResult run = FormatterRunner.write(dir, List.of(readOnly), FormatterOptions.defaults());

            assertThat(run.results()).singleElement().satisfies(result -> {
                assertThat(result.status()).isEqualTo(FormatFileStatus.WRITTEN_PARTIALLY);
                assertThat(result.changed()).isTrue();
                assertThat(result.failed()).isTrue();
                assertThat(result.failureException()).isPresent();
            });
            assertThat(run.changedCount()).isEqualTo(1);
            assertThat(run.failureCount()).isEqualTo(1);
        } finally {
            readOnly.toFile().setWritable(true);
        }
    }

    private static Path write(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8);
            return path;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
