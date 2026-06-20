package dev.lanwen.frmtr.tooling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.lanwen.frmtr.FormatterOptions;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
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
                """
        );
        Path changed = write(dir.resolve("src/Changed.java"), "class Changed{int value;}");

        FormatRunResult run = FormatterRunner.check(
            dir,
            List.of(unchanged, changed, changed),
            FormatterOptions.defaults(),
            true,
            state -> {}
        );
        List<FormatFileResult> results = run.results();

        assertThat(results)
                .extracting(FormatFileResult::displayPath)
                .containsExactly(Path.of("src/Changed.java"), Path.of("src/Unchanged.java"));
        assertThat(run.hasChanges()).isTrue();
        assertThat(run.hasFailures()).isFalse();
        assertThat(run.changedCount()).isEqualTo(1);
        assertThat(results.getFirst().status()).isEqualTo(FormatFileStatus.CHANGED);
        assertThat(results.getFirst().unifiedDiff()).hasValueSatisfying(diff -> assertThat(diff)
                    .contains("diff --git origin frmtr")
                    .contains("--- origin\n+++ frmtr")
                    .doesNotContain("a/src/Changed.java")
                    .doesNotContain("b/src/Changed.java")
                    .contains("-class Changed{int value;}")
                    .contains("+class Changed {")
        );
        assertThat(results.getLast().status()).isEqualTo(FormatFileStatus.UNCHANGED);
    }

    @Test
    void checkCanDecorateDiffsWithTheConfiguredLineWidthRuler(@TempDir Path dir) {
        Path changed = write(dir.resolve("src/Changed.java"), "class Changed{int value;}");
        FormatterOptions options = FormatterOptions.defaults().withLineWidth(20);

        FormatRunResult run = FormatterRunner.check(
            dir,
            List.of(changed),
            options,
            true,
            UnifiedDiffRenderer.RenderMode.LINE_WIDTH_RULER,
            state -> {}
        );

        assertThat(run.results().getFirst().unifiedDiff()).hasValueSatisfying(diff -> assertThat(diff)
                    .contains("@@ -1 +1,4 @@        ⋮ 20")
                    .contains("-class Changed{int va⋮lue;}")
                    .contains("                     ⋮+5")
                    .contains("+class Changed {     ⋮")
        );
    }

    @Test
    void writesChangedFilesAndContinuesAfterFailures(@TempDir Path dir) throws IOException {
        Path changed = write(dir.resolve("src/Changed.java"), "class Changed{int value;}");
        Path broken = write(dir.resolve("src/Broken.java"), "class {");

        FormatRunResult run = FormatterRunner.write(
            dir,
            List.of(changed, broken),
            FormatterOptions.defaults(),
            state -> {}
        );
        List<FormatFileResult> results = run.results();

        assertThat(results)
                .extracting(FormatFileResult::status)
                .containsExactly(FormatFileStatus.FAILED, FormatFileStatus.WRITTEN);
        assertThat(run.hasChanges()).isTrue();
        assertThat(run.hasFailures()).isTrue();
        assertThat(run.changedCount()).isEqualTo(1);
        assertThat(run.failureCount()).isEqualTo(1);
        assertThat(run.firstFailure()).isPresent();
        assertThat(Files.readString(changed, StandardCharsets.UTF_8)).isEqualTo(
            """
                class Changed {

                    int value;
                }
                """
        );
    }

    @Test
    void verifyWriteStillFormatsCorrectlyFormattableFiles(@TempDir Path dir) throws IOException {
        // The opt-in verify path must not change behavior for correctly-formattable input: the file is still written and
        // its contents still match the formatter output. (A refusal cannot be triggered while the formatter is correct;
        // the seam test in frmtr-core covers the refusal and its non-internal failure type.)
        Path changed = write(dir.resolve("src/Changed.java"), "class Changed{int value;}");

        FormatRunResult run = FormatterRunner.writeVerified(
            dir,
            List.of(changed),
            FormatterOptions.defaults(),
            state -> {}
        );

        assertThat(run.results())
                .singleElement()
                .satisfies(result -> {
                    assertThat(result.status()).isEqualTo(FormatFileStatus.WRITTEN);
                    assertThat(result.failed()).isFalse();
                });
        assertThat(run.hasFailures()).isFalse();
        assertThat(Files.readString(changed, StandardCharsets.UTF_8)).isEqualTo(
            """
                class Changed {

                    int value;
                }
                """
        );
    }

    @Test
    void reportsPartialWriteWhenChangedFileCannotBeWritten(@TempDir Path dir) throws IOException {
        // Staged writes replace the target through its directory, so a read-only file in a writable directory may still
        // be reformatted. A non-writable directory exercises the write-step failure contract.
        Path subDir = dir.resolve("src");
        Path readOnly = write(subDir.resolve("ReadOnly.java"), "class ReadOnly{int value;}");
        String before = Files.readString(readOnly, StandardCharsets.UTF_8);
        assertThat(subDir.toFile().setWritable(false)).isTrue();

        try {
            FormatRunResult run = FormatterRunner.write(dir, List.of(readOnly), FormatterOptions.defaults(), state -> {});

            assertThat(run.results())
                    .singleElement()
                    .satisfies(result -> {
                        assertThat(result.status()).isEqualTo(FormatFileStatus.WRITTEN_PARTIALLY);
                        assertThat(result.changed()).isTrue();
                        assertThat(result.failed()).isTrue();
                        assertThat(result.failureException()).isPresent();
                    });
            assertThat(run.changedCount()).isEqualTo(1);
            assertThat(run.failureCount()).isEqualTo(1);
            assertThat(Files.readString(readOnly, StandardCharsets.UTF_8)).isEqualTo(before);
        } finally {
            subDir.toFile().setWritable(true);
        }
    }

    @Test
    void emitsProgressFromTheCallingThreadWithOrderedFinalResults(@TempDir Path dir) throws IOException {
        Path unchanged = write(
            dir.resolve("src/Unchanged.java"),
            """
                class Unchanged {

                    int value;
                }
                """
        );
        Path changed = write(dir.resolve("src/Changed.java"), "class Changed{int value;}");
        Path broken = write(dir.resolve("src/Broken.java"), "class {");
        Thread callingThread = Thread.currentThread();
        List<ProgressSnapshot> snapshots = new ArrayList<>();
        List<Thread> callbackThreads = new ArrayList<>();

        FormatRunResult run = FormatterRunner.check(
            dir,
            List.of(unchanged, changed, broken),
            FormatterOptions.defaults(),
            false,
            state -> {
                callbackThreads.add(Thread.currentThread());
                snapshots.add(state);
            }
        );

        assertThat(run.results())
                .extracting(FormatFileResult::displayPath)
                .containsExactly(
                    Path.of("src/Broken.java"),
                    Path.of("src/Changed.java"),
                    Path.of("src/Unchanged.java")
                );
        assertThat(callbackThreads).containsOnly(callingThread);
        assertProgressLifecycle(run, snapshots, 3);
    }

    @Test
    void emitsStartAndFinishProgressForEmptyInput(@TempDir Path dir) {
        List<ProgressSnapshot> snapshots = new ArrayList<>();

        FormatRunResult run = FormatterRunner.check(
            dir,
            List.of(),
            FormatterOptions.defaults(),
            false,
            snapshots::add
        );

        assertThat(run.results()).isEmpty();
        assertThat(snapshots)
                .hasSize(2)
                .first()
                .isInstanceOf(ProgressSnapshot.Started.class);
        assertThat(snapshots.getLast()).isInstanceOf(ProgressSnapshot.Finished.class);
        assertProgressLifecycle(run, snapshots, 0);
    }

    @Test
    void rejectsInvalidProgressSnapshotState() {
        assertThatThrownBy(() -> ProgressSnapshot.started(1, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires workers");
        assertThatThrownBy(() -> ProgressSnapshot.running(2, 2, 1, 0, 2, List.of(Path.of("src/A.java"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("remaining files");
        assertThatThrownBy(() -> ProgressSnapshot.running(2, 1, 1, 0, 2, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("active files");
        assertThatThrownBy(() -> ProgressSnapshot.running(
                2,
                1,
                1,
                0,
                2,
                List.of(Path.of("src/A.java"), Path.of("src/B.java"))
        ))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("remaining files");
        assertThatThrownBy(() -> ProgressSnapshot.finished(1, 2, 0, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("processed files");
    }

    private static void assertProgressLifecycle(
            FormatRunResult run,
            List<ProgressSnapshot> snapshots,
            int expectedTotal
    ) {
        assertThat(snapshots).hasSizeGreaterThanOrEqualTo(2);
        assertThat(snapshots.getFirst())
                .extracting(
                    ProgressSnapshot::totalFiles,
                    ProgressSnapshot::processedFiles,
                    ProgressSnapshot::changedFiles,
                    ProgressSnapshot::failedFiles,
                    ProgressSnapshot::workerCount,
                    ProgressSnapshot::activeFiles
                )
                .containsExactly(
                    expectedTotal,
                    0,
                    0,
                    0,
                    expectedTotal == 0 ? 0 : snapshots.getFirst().workerCount(),
                    0
                );
        assertThat(snapshots.getFirst()).isInstanceOf(ProgressSnapshot.Started.class);
        assertThat(snapshots.getLast()).isInstanceOf(ProgressSnapshot.Finished.class);
        assertThat(snapshots.subList(1, snapshots.size() - 1)).allMatch(ProgressSnapshot.Running.class::isInstance);

        int workerCount = snapshots.getFirst().workerCount();
        assertThat(workerCount).isEqualTo(expectedTotal == 0 ? 0 : snapshots.getLast().workerCount());
        assertThat(workerCount).isBetween(expectedTotal == 0 ? 0 : 1, expectedTotal);

        int processed = 0;
        int changed = 0;
        int failed = 0;
        for (ProgressSnapshot snapshot : snapshots) {
            assertThat(snapshot.totalFiles()).isEqualTo(expectedTotal);
            assertThat(snapshot.workerCount()).isEqualTo(workerCount);
            assertThat(snapshot.processedFiles()).isBetween(processed, expectedTotal);
            assertThat(snapshot.changedFiles()).isBetween(changed, snapshot.processedFiles());
            assertThat(snapshot.failedFiles()).isBetween(failed, snapshot.processedFiles());

            if (snapshot instanceof ProgressSnapshot.Running) {
                assertThat(snapshot.processedFiles()).isLessThan(expectedTotal);
                assertThat(snapshot.activeFiles()).isBetween(
                    1,
                    Math.min(workerCount, expectedTotal - snapshot.processedFiles())
                );
                assertThat(snapshot.activeDisplayPaths()).allMatch(Predicate.not(Path::isAbsolute));
            } else {
                assertThat(snapshot.activeDisplayPaths()).isEmpty();
            }

            processed = snapshot.processedFiles();
            changed = snapshot.changedFiles();
            failed = snapshot.failedFiles();
        }

        assertThat(snapshots.getLast())
                .extracting(
                    ProgressSnapshot::totalFiles,
                    ProgressSnapshot::processedFiles,
                    ProgressSnapshot::changedFiles,
                    ProgressSnapshot::failedFiles,
                    ProgressSnapshot::workerCount,
                    ProgressSnapshot::activeFiles
                )
                .containsExactly(
                    expectedTotal,
                    expectedTotal,
                    Math.toIntExact(run.changedCount()),
                    Math.toIntExact(run.failureCount()),
                    workerCount,
                    0
                );
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
