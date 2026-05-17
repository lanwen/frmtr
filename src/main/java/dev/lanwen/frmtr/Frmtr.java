package dev.lanwen.frmtr;

import dev.lanwen.frmtr.java.JavaFormatter;

public final class Frmtr {
    private Frmtr() {}

    public static String format(String source) {
        return format(source, FormatterOptions.defaults());
    }

    public static String format(String source, FormatterOptions options) {
        return new JavaFormatter(options).format(source);
    }
}
