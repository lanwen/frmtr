package dev.lanwen.frmtr.cli;

import dev.lanwen.frmtr.tooling.FormatRunProgress;
import dev.lanwen.frmtr.tooling.ProgressSnapshot;
import java.io.PrintWriter;
import java.util.List;

final class CliProgressRenderer implements FormatRunProgress {

    private static final String CLEAR_LINE = "\u001B[2K";

    private static final String CURSOR_UP = "\u001B[1A";

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

    private int renderedLines;

    CliProgressRenderer(PrintWriter err, String changedLabel) {
        this.err = err;
        this.changedLabel = changedLabel;
    }

    void discovering() {
        render(List.of("Discovering Java files..."));
    }

    void clear() {
        clearPreviousRender();
        renderedLines = 0;
        err.flush();
    }

    @Override
    public void progress(ProgressSnapshot snapshot) {
        List<String> lines = new java.util.ArrayList<>();
        lines.add(
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
            lines.add("(" + nextSpinner() + ") " + snapshot.activeDisplayPaths().getFirst());
        }
        render(lines);
    }

    private void render(List<String> lines) {
        clearPreviousRender();
        for (String line : lines) {
            err.println(line);
        }
        renderedLines = lines.size();
        err.flush();
    }

    private void clearPreviousRender() {
        for (int line = 0; line < renderedLines; line++) {
            err.print(CURSOR_UP);
            err.print(CLEAR_LINE);
        }
    }

    private String nextSpinner() {
        String frame = SPINNER.get(spinnerIndex);
        spinnerIndex = (spinnerIndex + 1) % SPINNER.size();
        return frame;
    }
}
