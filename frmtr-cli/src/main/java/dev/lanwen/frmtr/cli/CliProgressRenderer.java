package dev.lanwen.frmtr.cli;

import dev.lanwen.frmtr.tooling.FormatRunProgress;
import dev.lanwen.frmtr.tooling.ProgressSnapshot;
import java.io.PrintWriter;
import java.util.List;

final class CliProgressRenderer implements FormatRunProgress {

    private static final List<String> SPINNER = List.of(
        "⠋",
        "⠙",
        "⠹",
        "⠸",
        "⠼",
        "⠴",
        "⠦",
        "⠧",
        "⠇",
        "⠏"
    );

    private final PrintWriter err;

    private final String changedLabel;

    private int spinnerIndex;

    CliProgressRenderer(PrintWriter err, String changedLabel) {
        this.err = err;
        this.changedLabel = changedLabel;
    }

    @Override
    public void progress(ProgressSnapshot snapshot) {
        err.println(
            "Processed ["
                + snapshot.processedFiles()
                + "/"
                + snapshot.totalFiles()
                + " files, "
                + snapshot.changedFiles()
                + " "
                + changedLabel
                + ", "
                + snapshot.failedFiles()
                + " failed]."
        );
        if (!snapshot.activeDisplayPaths().isEmpty()) {
            err.println("(" + nextSpinner() + ") " + snapshot.activeDisplayPaths().getFirst());
        }
        err.flush();
    }

    private String nextSpinner() {
        String frame = SPINNER.get(spinnerIndex);
        spinnerIndex = (spinnerIndex + 1) % SPINNER.size();
        return frame;
    }
}
