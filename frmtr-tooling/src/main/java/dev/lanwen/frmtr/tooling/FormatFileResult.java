package dev.lanwen.frmtr.tooling;

import dev.lanwen.frmtr.OverWidthLines.OverWidthLine;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record FormatFileResult(
    Path file,
    Path displayPath,
    FormatFileStatus status,
    String diffText,
    Exception failure,
    List<OverWidthLine> overWidthLines
) {
    public FormatFileResult {
        file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        displayPath = Objects.requireNonNull(displayPath, "displayPath");
        Objects.requireNonNull(status, "status");
        if ((status == FormatFileStatus.FAILED || status == FormatFileStatus.WRITTEN_PARTIALLY) && failure == null) {
            throw new IllegalArgumentException("failure is required for failed or partially written file results");
        }
        if (status != FormatFileStatus.FAILED && status != FormatFileStatus.WRITTEN_PARTIALLY && failure != null) {
            throw new IllegalArgumentException(
                "failure is only supported for failed or partially written file results"
            );
        }
        diffText = diffText == null ? "" : diffText;
        overWidthLines = overWidthLines == null ? List.of() : List.copyOf(overWidthLines);
    }

    /**
     * Back-compatible constructor for the four classic components plus failure. Delegates with an empty over-width
     * findings list, so every pre-existing call site — including {@code FormatterRunner}'s write/non-verify paths,
     * {@code Main}'s print path, and the gradle plugin (which never constructs this record) — keeps compiling and
     * behaving exactly as before. Over-width findings are populated only by the {@code --check --verify} scan.
     */
    public FormatFileResult(Path file, Path displayPath, FormatFileStatus status, String diffText, Exception failure) {
        this(file, displayPath, status, diffText, failure, List.of());
    }

    public boolean changed() {
        return status == FormatFileStatus.CHANGED
            || status == FormatFileStatus.WRITTEN
            || status == FormatFileStatus.WRITTEN_PARTIALLY;
    }

    public boolean failed() {
        return status == FormatFileStatus.FAILED || status == FormatFileStatus.WRITTEN_PARTIALLY;
    }

    public Optional<Exception> failureException() {
        return Optional.ofNullable(failure);
    }

    public Optional<String> unifiedDiff() {
        return diffText.isEmpty() ? Optional.empty() : Optional.of(diffText);
    }
}
