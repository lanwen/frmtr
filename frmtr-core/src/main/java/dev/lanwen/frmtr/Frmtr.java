package dev.lanwen.frmtr;

import dev.lanwen.frmtr.java.JavaFormatter;

public final class Frmtr {

    private Frmtr() {}

    public static String format(String source) {
        return format(source, FormatterOptions.defaults());
    }

    public static String format(String source, FormatterOptions options) {
        try {
            return new JavaFormatter(options).format(source);
        } catch (FormatterException exception) {
            throw exception;
        } catch (RuntimeException | LinkageError | AssertionError exception) {
            throw FormatterException.internal(exception);
        }
    }

    public static String debugDoc(String source) {
        return debugDoc(source, FormatterOptions.defaults());
    }

    public static ExplainResult explain(String source) {
        return explain(source, FormatterOptions.defaults());
    }

    /**
     * Formats the source and explains the renderer's per-group break/flat decisions in one pass.
     *
     * <p>This is the developer-facing diagnostic entry point behind the CLI {@code --explain} mode. The returned
     * {@link ExplainResult#formatted()} is identical to {@link #format(String, FormatterOptions)} for the same input, so
     * explaining never changes formatting policy or output; the explanation only observes the same render. Like {@link
     * #debugDoc(String, FormatterOptions)}, this always builds the document even under a require-pragma gate.
     */
    public static ExplainResult explain(String source, FormatterOptions options) {
        try {
            return new JavaFormatter(options).explain(source);
        } catch (FormatterException exception) {
            throw exception;
        } catch (RuntimeException | LinkageError | AssertionError exception) {
            throw FormatterException.internal(exception);
        }
    }

    /**
     * Returns the structural document tree produced by the Java formatter before width-based rendering.
     *
     * <p>This debug path always builds the document tree for the supplied source; pragma gating only controls formatted
     * source output.
     */
    public static String debugDoc(String source, FormatterOptions options) {
        try {
            return new JavaFormatter(options).debugDoc(source);
        } catch (FormatterException exception) {
            throw exception;
        } catch (RuntimeException | LinkageError | AssertionError exception) {
            throw FormatterException.internal(exception);
        }
    }
}
