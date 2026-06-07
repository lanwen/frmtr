package dev.lanwen.frmtr;

import java.io.Serial;
import java.util.Objects;

/**
 * Raised when source cannot be parsed or formatted.
 */
public final class FormatterException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    private final boolean internal;

    public FormatterException(String message) {
        this(message, null, false);
    }

    public FormatterException(String message, Throwable cause) {
        this(message, cause, false);
    }

    private FormatterException(String message, Throwable cause, boolean internal) {
        super(message, cause);
        this.internal = internal;
    }

    public static FormatterException internal(Throwable cause) {
        Objects.requireNonNull(cause, "cause");
        return new FormatterException(
                "Internal formatter error. This is a bug in frmtr or one of its parser dependencies: "
                        + failureSummary(cause),
                cause,
                true);
    }

    public boolean internal() {
        return internal;
    }

    private static String failureSummary(Throwable cause) {
        String message = cause.getMessage();
        if (message == null || message.isBlank()) {
            return cause.getClass().getSimpleName();
        }
        return cause.getClass().getSimpleName() + ": " + message;
    }
}
