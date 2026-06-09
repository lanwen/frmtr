package dev.lanwen.frmtr;

import java.io.Serial;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Raised when source cannot be parsed or formatted.
 *
 * <p>Parse failures may carry structured {@link SourceProblem} entries with source locations and context lines. API
 * consumers should prefer {@link #sourceProblems()} for diagnostics and reserve the exception message for a concise
 * human summary.
 */
public final class FormatterException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    private final boolean internal;
    private final transient List<SourceProblem> sourceProblems;

    public FormatterException(String message) {
        this(message, null, false, List.of());
    }

    public FormatterException(String message, Throwable cause) {
        this(message, cause, false, List.of());
    }

    public FormatterException(String message, Throwable cause, List<SourceProblem> sourceProblems) {
        this(message, cause, false, sourceProblems);
    }

    private FormatterException(String message, Throwable cause, boolean internal, List<SourceProblem> sourceProblems) {
        super(message, cause);
        this.internal = internal;
        this.sourceProblems = List.copyOf(Objects.requireNonNull(sourceProblems, "sourceProblems"));
    }

    public static FormatterException internal(Throwable cause) {
        Objects.requireNonNull(cause, "cause");
        return new FormatterException(
                "Internal formatter error. This is a bug in frmtr or one of its parser dependencies: "
                        + failureSummary(cause),
                cause,
                true,
                List.of());
    }

    public boolean internal() {
        return internal;
    }

    /**
     * Returns structured source diagnostics reported for this failure.
     *
     * <p>The list is empty when no structured diagnostics were provided. Individual {@link SourceProblem} entries may
     * omit location and context when that metadata is unavailable. Renderers and adapters should use this metadata
     * instead of parsing {@link #getMessage()}, because the message is not a stable source-context contract.
     */
    public List<SourceProblem> sourceProblems() {
        return sourceProblems == null ? List.of() : sourceProblems;
    }

    /**
     * Source-oriented context for one formatter failure, separated from the exception message so adapters can choose
     * their own display format.
     *
     * <p>{@code location} is the parser's exact position when available, {@code enclosingUnitLine} is the closest
     * enclosing declaration or source unit line used as orientation, and {@code contextLines} is the cropped source
     * window intended for human diagnostics.
     */
    public record SourceProblem(
            String message,
            Optional<SourceLocation> location,
            Optional<SourceLine> enclosingUnitLine,
            List<SourceLine> contextLines) {
        public SourceProblem {
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(location, "location");
            Objects.requireNonNull(enclosingUnitLine, "enclosingUnitLine");
            contextLines = List.copyOf(Objects.requireNonNull(contextLines, "contextLines"));
        }
    }

    /**
     * One-based source position reported by the parser.
     */
    public record SourceLocation(int line, int column) {
        public SourceLocation {
            if (line < 1) {
                throw new IllegalArgumentException("line must be positive");
            }
            if (column < 1) {
                throw new IllegalArgumentException("column must be positive");
            }
        }
    }

    /**
     * One displayed source line. {@code startColumn} is one-based and records where {@code text} starts in the original
     * line when a long line has been cropped.
     */
    public record SourceLine(int lineNumber, int startColumn, String text) {
        public SourceLine {
            if (lineNumber < 1) {
                throw new IllegalArgumentException("lineNumber must be positive");
            }
            if (startColumn < 1) {
                throw new IllegalArgumentException("startColumn must be positive");
            }
            Objects.requireNonNull(text, "text");
        }
    }

    private static String failureSummary(Throwable cause) {
        String message = cause.getMessage();
        if (message == null || message.isBlank()) {
            return cause.getClass().getSimpleName();
        }
        return cause.getClass().getSimpleName() + ": " + message;
    }
}
