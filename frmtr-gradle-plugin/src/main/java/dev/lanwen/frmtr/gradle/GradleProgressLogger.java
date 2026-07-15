package dev.lanwen.frmtr.gradle;

import dev.lanwen.frmtr.tooling.FormatRunProgress;
import dev.lanwen.frmtr.tooling.ProgressSnapshot;
import java.util.Objects;
import org.gradle.api.logging.Logger;

/**
 * Maps runner progress onto Gradle's INFO log without mixing it with ordered result diagnostics.
 */
final class GradleProgressLogger implements FormatRunProgress {

    private final Logger logger;

    private final String action;

    private GradleProgressLogger(Logger logger, String action) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.action = Objects.requireNonNull(action, "action");
    }

    static GradleProgressLogger forCheck(Logger logger) {
        return new GradleProgressLogger(logger, "check");
    }

    static GradleProgressLogger forFormat(Logger logger) {
        return new GradleProgressLogger(logger, "format");
    }

    @Override
    public void progress(ProgressSnapshot snapshot) {
        if (!logger.isInfoEnabled()) {
            return;
        }
        String message = switch (snapshot) {
            case ProgressSnapshot.Started started -> render("started", started, "");
            case ProgressSnapshot.Running running -> render(
                "running",
                running,
                ", active=" + running.activeDisplayPaths()
            );
            case ProgressSnapshot.Finished finished -> render("finished", finished, "");
        };
        logger.info(message);
    }

    private String render(String phase, ProgressSnapshot snapshot, String suffix) {
        return "frmtr %s progress %s: processed=%d/%d, changed=%d, failed=%d, workers=%d%s".formatted(
            action,
            phase,
            snapshot.processedFiles(),
            snapshot.totalFiles(),
            snapshot.changedFiles(),
            snapshot.failedFiles(),
            snapshot.workerCount(),
            suffix
        );
    }
}
