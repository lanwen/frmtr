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

    private final boolean verifyViolation;

    private final transient List<SourceProblem> sourceProblems;

    public FormatterException(String message) {
        this(message, null, false, false, List.of());
    }

    public FormatterException(String message, Throwable cause) {
        this(message, cause, false, false, List.of());
    }

    public FormatterException(String message, Throwable cause, List<SourceProblem> sourceProblems) {
        this(message, cause, false, false, sourceProblems);
    }

    private FormatterException(
            String message,
            Throwable cause,
            boolean internal,
            boolean verifyViolation,
            List<SourceProblem> sourceProblems
    ) {
        super(message, cause);
        this.internal = internal;
        this.verifyViolation = verifyViolation;
        this.sourceProblems = List.copyOf(Objects.requireNonNull(sourceProblems, "sourceProblems"));
    }

    public static FormatterException internal(Throwable cause) {
        Objects.requireNonNull(cause, "cause");
        return new FormatterException(
            "Internal formatter error. This is a bug in frmtr or one of its parser dependencies: "
                + failureSummary(cause),
            cause,
            true,
            false,
            List.of()
        );
    }

    /**
     * Builds the verify safety valve's refusal exception: a cleanly-parsed input whose formatted output failed the
     * AST-equivalence re-check (or did not re-parse) under the verify mode.
     *
     * <p>This is the single principled discriminator for that failure kind. {@link #verifyViolation()} is set
     * {@code true} only through this factory, used exclusively at the two {@code JavaFormatter} verify throw sites, so
     * callers (notably the CLI exit-code mapping) can distinguish "formatter produced non-equivalent output — a
     * formatter bug" from an ordinary parse/IO failure without matching on the message string. The failure is not
     * {@link #internal()}: it surfaces as a deliberate refusal, not an internal crash.
     */
    public static FormatterException verifyViolation(String message) {
        return new FormatterException(message, null, false, true, List.of());
    }

    public boolean internal() {
        return internal;
    }

    /**
     * Returns {@code true} when this failure is the verify safety valve's AST-equivalence refusal — a cleanly-parsed
     * file whose formatted output was not AST-equivalent to the input (or did not re-parse). Distinct from
     * {@link #internal()}; both are {@code false} for ordinary parse and IO failures.
     */
    public boolean verifyViolation() {
        return verifyViolation;
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
     * Source-oriented context for one formatter failure, so adapters can choose their own display format.
     * @param message failure description
     * @param location parser's exact position, when available
     * @param enclosingUnitLine nearest enclosing declaration line, for orientation
     * @param contextLines cropped source window for human diagnostics, may be empty
     */
    public record SourceProblem(
        String message,
        Optional<SourceLocation> location,
        Optional<SourceLine> enclosingUnitLine,
        List<SourceLine> contextLines
    ) {
        public SourceProblem {
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(location, "location");
            Objects.requireNonNull(enclosingUnitLine, "enclosingUnitLine");
            contextLines = List.copyOf(Objects.requireNonNull(contextLines, "contextLines"));
        }
    }

    /**
     * One-based source position reported by the parser.
     * @param line one-based line number
     * @param column one-based column number
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
     * One displayed source line, potentially cropped from the original.
     * @param lineNumber one-based line number in the source file
     * @param startColumn one-based offset of {@code text} within the original line
     * @param text the displayed text, potentially a crop of the full source line
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
