package dev.lanwen.frmtr;

import java.util.Objects;

public record FormatterOptions(
        int lineWidth,
        IndentStyle indentStyle,
        int indentWidth,
        LineEnding lineEnding,
        boolean trailingNewline,
        boolean preserveRawTrailingWhitespace,
        boolean requirePragma,
        LambdaArrowParens lambdaArrowParens,
        BinaryOperatorPosition binaryOperatorPosition,
        JavaLanguageLevel javaLanguageLevel) {

    public static final int DEFAULT_LINE_WIDTH = 140;

    public static final int DEFAULT_INDENT_WIDTH = 4;

    public FormatterOptions(
            int lineWidth,
            IndentStyle indentStyle,
            int indentWidth,
            LineEnding lineEnding,
            boolean trailingNewline) {
        this(lineWidth, indentStyle, indentWidth, lineEnding, trailingNewline, JavaLanguageLevel.LATEST_AVAILABLE);
    }

    public FormatterOptions(
            int lineWidth,
            IndentStyle indentStyle,
            int indentWidth,
            LineEnding lineEnding,
            boolean trailingNewline,
            JavaLanguageLevel javaLanguageLevel) {
        this(lineWidth, indentStyle, indentWidth, lineEnding, trailingNewline, false, javaLanguageLevel);
    }

    public FormatterOptions(
            int lineWidth,
            IndentStyle indentStyle,
            int indentWidth,
            LineEnding lineEnding,
            boolean trailingNewline,
            boolean preserveRawTrailingWhitespace,
            JavaLanguageLevel javaLanguageLevel) {
        this(
                lineWidth,
                indentStyle,
                indentWidth,
                lineEnding,
                trailingNewline,
                preserveRawTrailingWhitespace,
                false,
                LambdaArrowParens.PRESERVE,
                BinaryOperatorPosition.END,
                javaLanguageLevel);
    }

    public FormatterOptions(
            int lineWidth,
            IndentStyle indentStyle,
            int indentWidth,
            LineEnding lineEnding,
            boolean trailingNewline,
            boolean preserveRawTrailingWhitespace,
            boolean requirePragma,
            JavaLanguageLevel javaLanguageLevel) {
        this(
                lineWidth,
                indentStyle,
                indentWidth,
                lineEnding,
                trailingNewline,
                preserveRawTrailingWhitespace,
                requirePragma,
                LambdaArrowParens.PRESERVE,
                BinaryOperatorPosition.END,
                javaLanguageLevel);
    }

    public FormatterOptions(
            int lineWidth,
            IndentStyle indentStyle,
            int indentWidth,
            LineEnding lineEnding,
            boolean trailingNewline,
            boolean preserveRawTrailingWhitespace,
            boolean requirePragma,
            LambdaArrowParens lambdaArrowParens,
            JavaLanguageLevel javaLanguageLevel) {
        this(
                lineWidth,
                indentStyle,
                indentWidth,
                lineEnding,
                trailingNewline,
                preserveRawTrailingWhitespace,
                requirePragma,
                lambdaArrowParens,
                BinaryOperatorPosition.END,
                javaLanguageLevel);
    }

    public FormatterOptions {
        if (lineWidth < 20) {
            throw new IllegalArgumentException("lineWidth must be at least 20");
        }
        if (indentWidth < 1) {
            throw new IllegalArgumentException("indentWidth must be at least 1");
        }
        Objects.requireNonNull(indentStyle, "indentStyle");
        Objects.requireNonNull(lineEnding, "lineEnding");
        Objects.requireNonNull(lambdaArrowParens, "lambdaArrowParens");
        Objects.requireNonNull(binaryOperatorPosition, "binaryOperatorPosition");
        Objects.requireNonNull(javaLanguageLevel, "javaLanguageLevel");
    }

    public static FormatterOptions defaults() {
        return new FormatterOptions(
                DEFAULT_LINE_WIDTH,
                IndentStyle.SPACE,
                DEFAULT_INDENT_WIDTH,
                LineEnding.LF,
                true,
                false,
                false,
                LambdaArrowParens.PRESERVE,
                BinaryOperatorPosition.END,
                JavaLanguageLevel.LATEST_AVAILABLE);
    }

    public String indentUnit() {
        return indentStyle == IndentStyle.TAB ? "\t" : " ".repeat(indentWidth);
    }

    public enum IndentStyle {
        /**
         * Indents nested blocks with spaces using the configured indent width.
         */
        SPACE,
        /**
         * Indents nested blocks with tab characters instead of spaces.
         */
        TAB
    }

    public enum LineEnding {
        /**
         * Emits Unix-style line feeds for every rendered line break.
         */
        LF("\n"),
        /**
         * Emits Windows-style carriage-return line feeds for every rendered line break.
         */
        CRLF("\r\n");

        private final String value;

        LineEnding(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    public enum LambdaArrowParens {
        /**
         * Keeps single-parameter lambda parentheses according to the parsed source.
         */
        PRESERVE,
        /**
         * Removes parentheses from single untyped lambda parameters when Java syntax allows it.
         */
        AVOID,
        /**
         * Adds parentheses around single lambda parameters to match styles that always require them.
         */
        ALWAYS
    }

    public enum BinaryOperatorPosition {
        /**
         * Keeps binary operators at the end of broken continuation lines.
         */
        END,
        /**
         * Moves binary operators to the start of broken continuation lines.
         */
        START
    }

    public enum JavaLanguageLevel {
        /**
         * Leaves JavaParser's language level unset, which selects raw parser mode without release-specific feature gates.
         */
        UNSET,
        /**
         * Uses the newest stable Java language level exposed by the bundled JavaParser dependency.
         */
        LATEST_AVAILABLE,

        JAVA_8,

        JAVA_9,

        JAVA_10,

        JAVA_11,

        JAVA_12,

        JAVA_13,

        JAVA_14,

        JAVA_15,

        JAVA_16,

        JAVA_17,

        JAVA_18,

        JAVA_19,

        JAVA_20,

        JAVA_21,

        JAVA_22,

        JAVA_23,

        JAVA_24,

        JAVA_25
    }
}
