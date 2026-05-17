package dev.lanwen.frmtr;

/**
 * Raised when source cannot be parsed or formatted.
 */
public final class FormatterException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public FormatterException(String message) {
        super(message);
    }

    public FormatterException(String message, Throwable cause) {
        super(message, cause);
    }
}
