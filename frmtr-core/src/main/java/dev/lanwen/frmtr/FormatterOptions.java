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

    public enum JavaLanguageLevel {
        /**
         * Leaves JavaParser's language level unset, which selects raw parser mode without release-specific feature gates.
         */
        UNSET,
        /**
         * Uses the newest stable Java language level exposed by the bundled JavaParser dependency.
         */
        LATEST_AVAILABLE,
        /**
         * Parses source using Java 8 grammar rules for projects that still target Java 8.
         */
        JAVA_8,
        /**
         * Parses source using Java 9 grammar rules for projects that rely on Java 9 language support.
         */
        JAVA_9,
        /**
         * Parses source using Java 10 grammar rules for projects that rely on Java 10 language support.
         */
        JAVA_10,
        /**
         * Parses source using Java 11 grammar rules for projects that rely on Java 11 language support.
         */
        JAVA_11,
        /**
         * Parses source using Java 12 grammar rules for projects that rely on Java 12 language support.
         */
        JAVA_12,
        /**
         * Parses source using Java 13 grammar rules for projects that rely on Java 13 language support.
         */
        JAVA_13,
        /**
         * Parses source using Java 14 grammar rules for projects that rely on Java 14 language support.
         */
        JAVA_14,
        /**
         * Parses source using Java 15 grammar rules for projects that rely on Java 15 language support.
         */
        JAVA_15,
        /**
         * Parses source using Java 16 grammar rules for projects that rely on Java 16 language support.
         */
        JAVA_16,
        /**
         * Parses source using Java 17 grammar rules for projects that rely on Java 17 language support.
         */
        JAVA_17,
        /**
         * Parses source using Java 18 grammar rules for projects that rely on Java 18 language support.
         */
        JAVA_18,
        /**
         * Parses source using Java 19 grammar rules for projects that rely on Java 19 language support.
         */
        JAVA_19,
        /**
         * Parses source using Java 20 grammar rules for projects that rely on Java 20 language support.
         */
        JAVA_20,
        /**
         * Parses source using Java 21 grammar rules for projects that rely on Java 21 language support.
         */
        JAVA_21,
        /**
         * Parses source using Java 22 grammar rules for projects that rely on Java 22 language support.
         */
        JAVA_22,
        /**
         * Parses source using Java 23 grammar rules for projects that rely on Java 23 language support.
         */
        JAVA_23,
        /**
         * Parses source using Java 24 grammar rules for projects that rely on Java 24 language support.
         */
        JAVA_24,
        /**
         * Parses source using Java 25 grammar rules for projects that rely on Java 25 language support.
         */
        JAVA_25
    }
}
