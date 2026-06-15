package dev.lanwen.frmtr.tooling;

import dev.lanwen.frmtr.FormatterException;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.Frmtr;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;

public final class FormatterRunner {

    private FormatterRunner() {}

    public static FormatRunResult check(
            Path displayRoot,
            List<Path> files,
            FormatterOptions options,
            boolean includeDiffs
    ) {
        return check(displayRoot, files, options, includeDiffs, UnifiedDiffRenderer.RenderMode.PATCH);
    }

    public static FormatRunResult check(
            Path displayRoot,
            List<Path> files,
            FormatterOptions options,
            boolean includeDiffs,
            UnifiedDiffRenderer.RenderMode diffRenderMode
    ) {
        return new FormatRunResult(
            formatSelectedFiles(
                displayRoot,
                files,
                file -> checkFile(displayRoot, file, options, includeDiffs, diffRenderMode)
            )
        );
    }

    public static FormatRunResult write(Path displayRoot, List<Path> files, FormatterOptions options) {
        return new FormatRunResult(
            formatSelectedFiles(
                displayRoot,
                files,
                file -> writeFile(displayRoot, file, options)
            )
        );
    }

    private static List<FormatFileResult> formatSelectedFiles(
            Path displayRoot,
            List<Path> files,
            Function<Path, FormatFileResult> formatter
    ) {
        List<Path> selected = selectedFiles(displayRoot, files);
        return mapInInputOrder(selected, workerCount(selected.size()), formatter);
    }

    private static <T, R> List<R> mapInInputOrder(List<T> inputs, int workers, Function<? super T, R> mapper) {
        if (inputs.isEmpty()) {
            return List.of();
        }
        int workerCount = Math.max(1, Math.min(workers, inputs.size()));
        if (workerCount == 1) {
            return inputs.stream().map(mapper).toList();
        }

        ExecutorService executor = Executors.newFixedThreadPool(workerCount);
        try {
            List<Callable<R>> tasks = inputs.stream()
                    .<Callable<R>>map(input -> () -> mapper.apply(input))
                    .toList();
            return executor.invokeAll(tasks).stream().map(FormatterRunner::awaitResult).toList();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while formatting files", exception);
        } finally {
            executor.shutdownNow();
        }
    }

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
            FormatterOptions options,
            boolean includeDiffs,
            UnifiedDiffRenderer.RenderMode diffRenderMode
    ) {
        Path displayPath = displayPath(displayRoot, file);
        try {
            String original = Files.readString(file, StandardCharsets.UTF_8);
            String formatted = Frmtr.format(original, options);
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

    private static FormatFileResult writeFile(Path displayRoot, Path file, FormatterOptions options) {
        Path displayPath = displayPath(displayRoot, file);
        try {
            String original = Files.readString(file, StandardCharsets.UTF_8);
            String formatted = Frmtr.format(original, options);
            if (formatted.equals(original)) {
                return new FormatFileResult(file, displayPath, FormatFileStatus.UNCHANGED, "", null);
            }
            try {
                Files.writeString(file, formatted, StandardCharsets.UTF_8);
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
