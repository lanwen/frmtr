package dev.lanwen.frmtr.tooling;

import dev.lanwen.frmtr.FormatterException;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.FrmtrSession;
import dev.lanwen.frmtr.OverWidthLines;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

public final class FormatterRunner {

    private FormatterRunner() {}

    public static FormatRunResult check(
            Path displayRoot,
            List<Path> files,
            FormatterOptions options,
            boolean includeDiffs,
            FormatRunProgress progress
    ) {
        return check(displayRoot, files, options, includeDiffs, UnifiedDiffRenderer.RenderMode.PATCH, progress);
    }

    public static FormatRunResult check(
            Path displayRoot,
            List<Path> files,
            FormatterOptions options,
            boolean includeDiffs,
            UnifiedDiffRenderer.RenderMode diffRenderMode,
            FormatRunProgress progress
    ) {
        return checkFiles(
            displayRoot,
            files,
            options,
            includeDiffs,
            diffRenderMode,
            progress,
            FrmtrSession::format,
            false
        );
    }

    /**
     * Read-only check that also asserts AST-equivalence of each file's formatted output, writing nothing.
     *
     * <p>Parallels {@link #check}, but formats through {@code FrmtrSession#formatVerified} so a cleanly-parsed file whose
     * formatted output is not AST-equivalent to its input (or does not re-parse) surfaces as a {@code FAILED} result
     * carrying the verify-violation {@link FormatterException}. Because the underlying {@code checkFile} never touches
     * disk, this path is inherently read-only: it reports would-change exactly like {@link #check} and writes nothing,
     * making it the read-only counterpart to {@link #writeVerified}.
     *
     * <p>This is also the only path that scans formatted output for breakable over-width lines (see
     * {@link OverWidthLines}) and attaches them to each {@link FormatFileResult}. The findings are purely informational:
     * they never change a result's {@code changed}/{@code failed} status and therefore never affect the CLI exit code.
     */
    public static FormatRunResult checkVerified(
            Path displayRoot,
            List<Path> files,
            FormatterOptions options,
            boolean includeDiffs,
            UnifiedDiffRenderer.RenderMode diffRenderMode,
            FormatRunProgress progress
    ) {
        return checkFiles(
            displayRoot,
            files,
            options,
            includeDiffs,
            diffRenderMode,
            progress,
            FrmtrSession::formatVerified,
            true
        );
    }

    private static FormatRunResult checkFiles(
            Path displayRoot,
            List<Path> files,
            FormatterOptions options,
            boolean includeDiffs,
            UnifiedDiffRenderer.RenderMode diffRenderMode,
            FormatRunProgress progress,
            BiFunction<FrmtrSession, String, String> formatSource,
            boolean reportOverWidth
    ) {
        return new FormatRunResult(
            formatSelectedFiles(
                displayRoot,
                files,
                (formatter, file) -> checkFile(
                    displayRoot,
                    file,
                    formatter,
                    options,
                    includeDiffs,
                    diffRenderMode,
                    formatSource,
                    reportOverWidth
                ),
                options,
                progress
            )
        );
    }

    public static FormatRunResult write(
            Path displayRoot,
            List<Path> files,
            FormatterOptions options,
            FormatRunProgress progress
    ) {
        return writeFiles(displayRoot, files, options, progress, FrmtrSession::format);
    }

    /**
     * Writes changed formatter output back to disk, refusing to overwrite a file whose formatted result is not
     * AST-equivalent to its input.
     *
     * <p>Each file is formatted through {@code FrmtrSession#formatVerified}: a verify mismatch throws a non-internal
     * {@link FormatterException} <em>before</em> any write is attempted, so the original file is left untouched and the
     * file's result is {@code FAILED} carrying that exception, whose message states why the overwrite was declined.
     */
    public static FormatRunResult writeVerified(
            Path displayRoot,
            List<Path> files,
            FormatterOptions options,
            FormatRunProgress progress
    ) {
        return writeFiles(displayRoot, files, options, progress, FrmtrSession::formatVerified);
    }

    private static FormatRunResult writeFiles(
            Path displayRoot,
            List<Path> files,
            FormatterOptions options,
            FormatRunProgress progress,
            BiFunction<FrmtrSession, String, String> formatSource
    ) {
        return new FormatRunResult(
            formatSelectedFiles(
                displayRoot,
                files,
                (formatter, file) -> writeFile(displayRoot, file, formatter, formatSource),
                options,
                progress
            )
        );
    }

    private static List<FormatFileResult> formatSelectedFiles(
            Path displayRoot,
            List<Path> files,
            BiFunction<Supplier<FrmtrSession>, Path, FormatFileResult> formatter,
            FormatterOptions options,
            FormatRunProgress progress
    ) {
        Objects.requireNonNull(progress, "progress");
        List<Path> selected = selectedFiles(displayRoot, files);
        ThreadLocal<FrmtrSession> workerSession = ThreadLocal.withInitial(() -> FrmtrSession.create(options));
        Supplier<FrmtrSession> formatterSession = workerSession::get;
        return mapInInputOrder(
            displayRoot,
            selected,
            workerCount(selected.size()),
            file -> formatter.apply(formatterSession, file),
            progress
        );
    }

    private static List<FormatFileResult> mapInInputOrder(
            Path displayRoot,
            List<Path> inputs,
            int workers,
            Function<? super Path, FormatFileResult> mapper,
            FormatRunProgress progress
    ) {
        if (inputs.isEmpty()) {
            progress.progress(ProgressSnapshot.started(0, 0));
            progress.progress(ProgressSnapshot.finished(0, 0, 0, 0));
            return List.of();
        }
        int workerCount = Math.max(1, Math.min(workers, inputs.size()));
        progress.progress(ProgressSnapshot.started(inputs.size(), workerCount));

        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        try {
            CompletionService<IndexedResult> completion = new ExecutorCompletionService<>(executor);
            List<FormatFileResult> results = new ArrayList<>(Collections.nCopies(inputs.size(), null));
            Map<Integer, Path> active = new LinkedHashMap<>();
            int submitted = 0;
            int processed = 0;
            int changed = 0;
            int failed = 0;

            submitted = submitUntilWorkerLimit(displayRoot, inputs, mapper, completion, active, submitted, workerCount);
            progress.progress(running(inputs.size(), processed, changed, failed, workerCount, active));

            while (processed < inputs.size()) {
                Future<IndexedResult> future = completion.take();
                IndexedResult result = awaitResult(future);
                active.remove(result.index());
                results.set(result.index(), result.result());
                if (result.result().changed()) {
                    changed++;
                }
                if (result.result().failed()) {
                    failed++;
                }
                processed++;

                submitted = submitUntilWorkerLimit(
                    displayRoot,
                    inputs,
                    mapper,
                    completion,
                    active,
                    submitted,
                    workerCount
                );
                if (processed < inputs.size()) {
                    progress.progress(running(inputs.size(), processed, changed, failed, workerCount, active));
                }
            }

            progress.progress(ProgressSnapshot.finished(inputs.size(), changed, failed, workerCount));
            return List.copyOf(results);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while formatting files", exception);
        } finally {
            executor.shutdownNow();
        }
    }

    private static int submitUntilWorkerLimit(
            Path displayRoot,
            List<Path> inputs,
            Function<? super Path, FormatFileResult> mapper,
            CompletionService<IndexedResult> completion,
            Map<Integer, Path> active,
            int submitted,
            int workerCount
    ) {
        int next = submitted;
        while (next < inputs.size() && active.size() < workerCount) {
            int index = next;
            Path input = inputs.get(index);
            active.put(index, displayPath(displayRoot, input));
            completion.submit(indexedTask(index, input, mapper));
            next++;
        }
        return next;
    }

    private static Callable<IndexedResult> indexedTask(
            int index,
            Path input,
            Function<? super Path, FormatFileResult> mapper
    ) {
        return () -> new IndexedResult(index, mapper.apply(input));
    }

    private static ProgressSnapshot.Running running(
            int totalFiles,
            int processedFiles,
            int changedFiles,
            int failedFiles,
            int workerCount,
            Map<Integer, Path> active
    ) {
        return ProgressSnapshot.running(
            totalFiles,
            processedFiles,
            changedFiles,
            failedFiles,
            workerCount,
            List.copyOf(active.values())
        );
    }

    private record IndexedResult(int index, FormatFileResult result) {}

    private static <T> T awaitResult(Future<T> future) {
        try {
            return future.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while formatting files", exception);
        } catch (ExecutionException exception) {
            throw rethrowWorkerFailure(exception.getCause());
        }
    }

    private static RuntimeException rethrowWorkerFailure(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        return new IllegalStateException("File formatter worker failed", failure);
    }

    private static int workerCount(int fileCount) {
        return Math.min(
            Math.max(1, Runtime.getRuntime().availableProcessors()),
            fileCount
        );
    }

    private static FormatFileResult checkFile(
            Path displayRoot,
            Path file,
            Supplier<FrmtrSession> formatter,
            FormatterOptions options,
            boolean includeDiffs,
            UnifiedDiffRenderer.RenderMode diffRenderMode,
            BiFunction<FrmtrSession, String, String> formatSource,
            boolean reportOverWidth
    ) {
        Path displayPath = displayPath(displayRoot, file);
        try {
            String original = Files.readString(file, StandardCharsets.UTF_8);
            String formatted = formatSource.apply(formatter.get(), original);
            // Findings describe the formatter's own rendered output, so scan `formatted` (not `original`) regardless of
            // whether the file would change. Empty unless reportOverWidth (i.e. only the --check --verify path).
            List<OverWidthLines.OverWidthLine> overWidthLines = reportOverWidth
                ? OverWidthLines.scan(formatted, options.lineWidth())
                : List.of();
            if (formatted.equals(original)) {
                return new FormatFileResult(file, displayPath, FormatFileStatus.UNCHANGED, "", null, overWidthLines);
            }
            String diff = includeDiffs
                ? UnifiedDiffRenderer.render(displayPath, original, formatted, options.lineWidth(), diffRenderMode)
                : "";
            return new FormatFileResult(file, displayPath, FormatFileStatus.CHANGED, diff, null, overWidthLines);
        } catch (FormatterException | IOException exception) {
            return new FormatFileResult(file, displayPath, FormatFileStatus.FAILED, "", exception);
        }
    }

    private static FormatFileResult writeFile(
            Path displayRoot,
            Path file,
            Supplier<FrmtrSession> formatter,
            BiFunction<FrmtrSession, String, String> formatSource
    ) {
        Path displayPath = displayPath(displayRoot, file);
        try {
            String original = Files.readString(file, StandardCharsets.UTF_8);
            String formatted = formatSource.apply(formatter.get(), original);
            if (formatted.equals(original)) {
                return new FormatFileResult(file, displayPath, FormatFileStatus.UNCHANGED, "", null);
            }
            try {
                BestEffortAtomicFileWriter.writeString(file, formatted);
            } catch (IOException exception) {
                return new FormatFileResult(file, displayPath, FormatFileStatus.WRITTEN_PARTIALLY, "", exception);
            }
            return new FormatFileResult(file, displayPath, FormatFileStatus.WRITTEN, "", null);
        } catch (FormatterException | IOException exception) {
            return new FormatFileResult(file, displayPath, FormatFileStatus.FAILED, "", exception);
        }
    }

    private static List<Path> selectedFiles(Path displayRoot, List<Path> files) {
        Path root = displayRoot.toAbsolutePath().normalize();
        Set<Path> selected = new LinkedHashSet<>();
        files.stream()
                .map(path -> path.toAbsolutePath().normalize())
                .sorted(Comparator.comparing(path -> displayPath(root, path).toString()))
                .forEach(selected::add);
        return List.copyOf(selected);
    }

    private static Path displayPath(Path displayRoot, Path file) {
        Path root = displayRoot.toAbsolutePath().normalize();
        Path absolute = file.toAbsolutePath().normalize();
        if (absolute.startsWith(root)) {
            return root.relativize(absolute);
        }
        return absolute;
    }
}
