package dev.lanwen.frmtr.check;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

public record FormatFileResult(
        Path file,
        Path displayPath,
        FormatFileStatus status,
        String diffText,
        Exception failure) {
    public FormatFileResult {
        file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        displayPath = Objects.requireNonNull(displayPath, "displayPath");
        Objects.requireNonNull(status, "status");
        if ((status == FormatFileStatus.FAILED || status == FormatFileStatus.WRITTEN_PARTIALLY) && failure == null) {
            throw new IllegalArgumentException("failure is required for failed or partially written file results");
        }
        if (status != FormatFileStatus.FAILED && status != FormatFileStatus.WRITTEN_PARTIALLY && failure != null) {
            throw new IllegalArgumentException("failure is only supported for failed or partially written file results");
        }
        diffText = diffText == null ? "" : diffText;
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
