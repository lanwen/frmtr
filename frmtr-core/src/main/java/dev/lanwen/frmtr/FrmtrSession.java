package dev.lanwen.frmtr;

import dev.lanwen.frmtr.java.JavaFormatter;
import java.util.function.Supplier;

/**
 * Reusable formatter session for sequentially formatting Java sources with one {@link FormatterOptions} value.
 *
 * <p>The session owns a single {@link JavaFormatter}, including that formatter's JavaParser instance. JavaParser is
 * stateful and not thread-safe, so callers must not use one session concurrently from multiple threads. File-oriented
 * callers should create one session per worker thread and reuse it only for that worker's sequential file stream.
 */
public final class FrmtrSession {

    private final JavaFormatter formatter;

    private FrmtrSession(FormatterOptions options) {
        this.formatter = new JavaFormatter(options);
    }

    /**
     * Creates a reusable sequential session with the supplied formatter policy.
     */
    public static FrmtrSession create(FormatterOptions options) {
        return formatterCall(() -> new FrmtrSession(options));
    }

    /**
     * Formats one Java source using this session's reusable parser and formatter setup.
     */
    public String format(String source) {
        return formatterCall(() -> formatter.format(source));
    }

    /**
     * Formats one Java source and, for cleanly-parsed input, verifies the result is AST-equivalent to the input.
     *
     * <p>This is the opt-in write-time safety valve: on a mismatch the underlying formatter throws a non-internal
     * {@link FormatterException}, which {@link #formatterCall} passes through unchanged so callers receive a clean
     * refusal (its {@link FormatterException#internal()} is {@code false}) rather than an internal-error wrapping.
     * Verification is skipped for recovered (partially-parsed) inputs; see {@code JavaFormatter#formatVerified}.
     */
    public String formatVerified(String source) {
        return formatterCall(() -> formatter.formatVerified(source));
    }

    /**
     * Returns the structural document tree produced by this session's formatter setup.
     */
    public String debugDoc(String source) {
        return formatterCall(() -> formatter.debugDoc(source));
    }

    /**
     * Formats the source and explains the renderer's per-group break/flat decisions using this session's setup.
     */
    public ExplainResult explain(String source) {
        return formatterCall(() -> formatter.explain(source));
    }

    private static <T> T formatterCall(Supplier<T> call) {
        try {
            return call.get();
        } catch (FormatterException exception) {
            throw exception;
        } catch (RuntimeException | LinkageError | AssertionError exception) {
            throw FormatterException.internal(exception);
        }
    }
}
