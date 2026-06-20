package dev.lanwen.frmtr.tooling;

import dev.lanwen.frmtr.FormatterException;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.FrmtrSession;
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
        return new FormatRunResult(
            formatSelectedFiles(
                displayRoot,
                files,
                (formatter, file) -> checkFile(displayRoot, file, formatter, options, includeDiffs, diffRenderMode),
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
        return new FormatRunResult(
            formatSelectedFiles(
                displayRoot,
                files,
                (formatter, file) -> writeFile(displayRoot, file, formatter),
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
            UnifiedDiffRenderer.RenderMode diffRenderMode
    ) {
        Path displayPath = displayPath(displayRoot, file);
        try {
            String original = Files.readString(file, StandardCharsets.UTF_8);
            String formatted = formatter.get().format(original);
            if (formatted.equals(original)) {
                return new FormatFileResult(file, displayPath, FormatFileStatus.UNCHANGED, "", null);
            }
            String diff = includeDiffs
                ? UnifiedDiffRenderer.render(displayPath, original, formatted, options.lineWidth(), diffRenderMode)
                : "";
            return new FormatFileResult(file, displayPath, FormatFileStatus.CHANGED, diff, null);
        } catch (FormatterException | IOException exception) {
            return new FormatFileResult(file, displayPath, FormatFileStatus.FAILED, "", exception);
        }
    }

    private static FormatFileResult writeFile(Path displayRoot, Path file, Supplier<FrmtrSession> formatter) {
        Path displayPath = displayPath(displayRoot, file);
        try {
            String original = Files.readString(file, StandardCharsets.UTF_8);
            String formatted = formatter.get().format(original);
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
