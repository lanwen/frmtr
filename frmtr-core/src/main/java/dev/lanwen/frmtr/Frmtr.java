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
}
