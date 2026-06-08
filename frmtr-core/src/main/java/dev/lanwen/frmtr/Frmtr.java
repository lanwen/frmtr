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
