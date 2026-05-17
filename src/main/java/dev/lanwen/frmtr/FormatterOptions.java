package dev.lanwen.frmtr;

import java.util.Objects;

public record FormatterOptions(
        int lineWidth,
        IndentStyle indentStyle,
        int indentWidth,
        LineEnding lineEnding,
        boolean trailingNewline) {
    public static final int DEFAULT_LINE_WIDTH = 100;
    public static final int DEFAULT_INDENT_WIDTH = 4;

    public FormatterOptions {
        if (lineWidth < 20) {
            throw new IllegalArgumentException("lineWidth must be at least 20");
        }
        if (indentWidth < 1) {
            throw new IllegalArgumentException("indentWidth must be at least 1");
        }
        Objects.requireNonNull(indentStyle, "indentStyle");
        Objects.requireNonNull(lineEnding, "lineEnding");
    }

    public static FormatterOptions defaults() {
        return new FormatterOptions(
                DEFAULT_LINE_WIDTH, IndentStyle.SPACE, DEFAULT_INDENT_WIDTH, LineEnding.LF, true);
    }

    public String indentUnit() {
        return indentStyle == IndentStyle.TAB ? "\t" : " ".repeat(indentWidth);
    }

    public enum IndentStyle {
        SPACE,
        TAB
    }

    public enum LineEnding {
        LF("\n"),
        CRLF("\r\n");

        private final String value;

        LineEnding(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }
}
