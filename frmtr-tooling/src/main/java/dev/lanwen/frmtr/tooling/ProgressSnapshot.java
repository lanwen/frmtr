package dev.lanwen.frmtr.tooling;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Immutable progress state emitted by the formatter runner coordinator.
 */
public sealed interface ProgressSnapshot
    permits ProgressSnapshot.Started, ProgressSnapshot.Running, ProgressSnapshot.Finished {
    static Started started(int totalFiles, int workerCount) {
        return new Started(totalFiles, workerCount);
    }

    static Running running(
            int totalFiles,
            int processedFiles,
            int changedFiles,
            int failedFiles,
            int workerCount,
            List<Path> activeDisplayPaths
    ) {
        return new Running(totalFiles, processedFiles, changedFiles, failedFiles, workerCount, activeDisplayPaths);
    }

    static Finished finished(int totalFiles, int changedFiles, int failedFiles, int workerCount) {
        return new Finished(totalFiles, changedFiles, failedFiles, workerCount);
    }

    int totalFiles();

    int processedFiles();

    int changedFiles();

    int failedFiles();

    int workerCount();

    List<Path> activeDisplayPaths();

    default int activeFiles() {
        return activeDisplayPaths().size();
    }

    record Started(int totalFiles, int workerCount) implements ProgressSnapshot {
        public Started {
            validateTotalAndWorkerCount(totalFiles, workerCount);
        }

        @Override
        public int processedFiles() {
            return 0;
        }

        @Override
        public int changedFiles() {
            return 0;
        }

        @Override
        public int failedFiles() {
            return 0;
        }

        @Override
        public List<Path> activeDisplayPaths() {
            return List.of();
        }
    }

    record Running(
        int totalFiles,
        int processedFiles,
        int changedFiles,
        int failedFiles,
        int workerCount,
        List<Path> activeDisplayPaths
    ) implements ProgressSnapshot {
        public Running {
            validateCounters(totalFiles, processedFiles, changedFiles, failedFiles, workerCount);
            activeDisplayPaths = copyActiveDisplayPaths(activeDisplayPaths);
            if (processedFiles == totalFiles) {
                throw new IllegalArgumentException("running progress requires remaining files");
            }
            if (activeDisplayPaths.isEmpty()) {
                throw new IllegalArgumentException("running progress requires active files");
            }
            if (activeDisplayPaths.size() > workerCount) {
                throw new IllegalArgumentException("active files cannot exceed worker count");
            }
            if (activeDisplayPaths.size() > totalFiles - processedFiles) {
                throw new IllegalArgumentException("active files cannot exceed remaining files");
            }
        }
    }

    record Finished(int totalFiles, int changedFiles, int failedFiles, int workerCount) implements ProgressSnapshot {
        public Finished {
            validateCounters(totalFiles, totalFiles, changedFiles, failedFiles, workerCount);
        }

        @Override
        public int processedFiles() {
            return totalFiles;
        }

        @Override
        public List<Path> activeDisplayPaths() {
            return List.of();
        }
    }

    private static void validateCounters(
            int totalFiles,
            int processedFiles,
            int changedFiles,
            int failedFiles,
            int workerCount
    ) {
        validateTotalAndWorkerCount(totalFiles, workerCount);
        if (processedFiles < 0 || changedFiles < 0 || failedFiles < 0) {
            throw new IllegalArgumentException("progress counters must not be negative");
        }
        if (processedFiles > totalFiles) {
            throw new IllegalArgumentException("processed files cannot exceed total files");
        }
        if (changedFiles > processedFiles || failedFiles > processedFiles) {
            throw new IllegalArgumentException("result counters cannot exceed processed files");
        }
    }

    private static void validateTotalAndWorkerCount(int totalFiles, int workerCount) {
        if (totalFiles < 0 || workerCount < 0) {
            throw new IllegalArgumentException("progress counters must not be negative");
        }
        if (workerCount > totalFiles && totalFiles > 0) {
            throw new IllegalArgumentException("worker count cannot exceed total files");
        }
        if (workerCount > 0 && totalFiles == 0) {
            throw new IllegalArgumentException("empty progress cannot have workers");
        }
        if (workerCount == 0 && totalFiles > 0) {
            throw new IllegalArgumentException("non-empty progress requires workers");
        }
    }

    private static List<Path> copyActiveDisplayPaths(List<Path> activeDisplayPaths) {
        return List.copyOf(Objects.requireNonNull(activeDisplayPaths, "activeDisplayPaths"));
    }
}
