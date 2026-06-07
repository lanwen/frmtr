package dev.lanwen.frmtr;

import java.util.Objects;

/**
 * Public formatting configuration used by the Java formatter, CLI, and Gradle adapter.
 *
 * <p>The options in this record are intentionally formatter-wide. They affect parsing, document rendering, or syntax
 * decisions that must stay stable across all Java constructs. Node-specific layout rules, such as how method chains or
 * switch entries break, remain internal formatter policy rather than public options.
 *
 * <p>The default configuration is returned by {@link #defaults()}: 140 columns, four-space indentation, LF line
 * endings, a trailing newline, no raw-trailing-whitespace preservation, no require-pragma gate, source-preserving
 * single-parameter lambda parentheses, binary operators at the end of broken continuation lines, and the latest Java
 * language level exposed by the bundled JavaParser dependency.
 *
 * @param lineWidth target maximum rendered line width. The renderer and Java-specific width gates use this value to
 *     decide whether grouped docs can stay flat or should break across lines. The value is a formatting target rather
 *     than an absolute hard cap because comments, raw-preserved regions, text blocks, and source-only syntax may still
 *     produce longer physical lines. Values below 20 are rejected.
 * @param indentStyle whether nested indentation uses spaces or tab characters. With {@link IndentStyle#SPACE}, each
 *     indentation level uses {@code indentWidth} spaces; with {@link IndentStyle#TAB}, each level uses one tab and
 *     {@code indentWidth} does not change the rendered indentation unit.
 * @param indentWidth number of spaces per indentation level when {@code indentStyle} is {@link IndentStyle#SPACE}.
 *     Values below 1 are rejected. For example, {@code SPACE, 2} renders a block body two spaces deeper than its
 *     braces, while {@code SPACE, 4} renders the same body four spaces deeper.
 * @param lineEnding line separator emitted by {@link dev.lanwen.frmtr.doc.DocRenderer} for every formatter-created
 *     line break. Use {@link LineEnding#LF} for Unix-style output and {@link LineEnding#CRLF} when generated files
 *     should use Windows-style line separators.
 * @param trailingNewline whether rendered output should end with one final line ending. When {@code true}, formatting
 *     {@code class A {}} produces output ending in {@code \n} or {@code \r\n}; when {@code false}, the formatter does
 *     not add a final line ending after the last rendered token.
 * @param preserveRawTrailingWhitespace whether raw-preserved source regions keep horizontal whitespace at line ends.
 *     This only affects paths that intentionally print raw token text, such as formatter-ignore regions and some
 *     source-sensitive fallback paths. Normal structured formatting still trims trailing horizontal whitespace when
 *     rendering line breaks.
 * @param requirePragma whether the formatter should require a leading Javadoc pragma before formatting a file. When
 *     {@code true}, only sources whose leading Javadoc comment contains {@code @format} or {@code @prettier} are
 *     formatted; sources without that pragma are returned unchanged. When {@code false}, every parseable source is
 *     eligible for formatting.
 * @param lambdaArrowParens policy for single-parameter lambda parentheses. See {@link LambdaArrowParens} for examples
 *     of how {@code value -> value}, {@code (value) -> value}, and typed parameters are handled.
 * @param binaryOperatorPosition where binary operators appear when a binary expression breaks across continuation
 *     lines. See {@link BinaryOperatorPosition} for examples of end-position and start-position output.
 * @param javaLanguageLevel JavaParser language level used while parsing source before formatting. This controls which
 *     Java syntax the parser accepts; it does not otherwise select a different formatter style.
 */
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

    /**
     * Default target line width used by {@link #defaults()}.
     *
     * <p>This width is intentionally wider than the Prettier-compatible fixture mode used in tests. Adapters may expose
     * this as a user setting when projects prefer narrower or wider Java output.
     */
    public static final int DEFAULT_LINE_WIDTH = 140;

    /**
     * Default number of spaces per indentation level used by {@link #defaults()}.
     */
    public static final int DEFAULT_INDENT_WIDTH = 4;

    /**
     * Compatibility constructor for callers that only configure renderer-level options.
     *
     * <p>Raw trailing whitespace is not preserved, require-pragma is disabled, lambda parentheses preserve source
     * spelling, broken binary operators stay at the end of continuation lines, and the Java language level defaults to
     * {@link JavaLanguageLevel#LATEST_AVAILABLE}.
     */
    public FormatterOptions(
            int lineWidth,
            IndentStyle indentStyle,
            int indentWidth,
            LineEnding lineEnding,
            boolean trailingNewline) {
        this(lineWidth, indentStyle, indentWidth, lineEnding, trailingNewline, JavaLanguageLevel.LATEST_AVAILABLE);
    }

    /**
     * Compatibility constructor for callers that configure parser language level but not raw/pragma/style options.
     *
     * <p>Raw trailing whitespace is not preserved, require-pragma is disabled, lambda parentheses preserve source
     * spelling, and broken binary operators stay at the end of continuation lines.
     */
    public FormatterOptions(
            int lineWidth,
            IndentStyle indentStyle,
            int indentWidth,
            LineEnding lineEnding,
            boolean trailingNewline,
            JavaLanguageLevel javaLanguageLevel) {
        this(lineWidth, indentStyle, indentWidth, lineEnding, trailingNewline, false, javaLanguageLevel);
    }

    /**
     * Compatibility constructor for callers that need raw trailing-whitespace preservation in formatter-ignore paths.
     *
     * <p>Require-pragma is disabled, lambda parentheses preserve source spelling, and broken binary operators stay at
     * the end of continuation lines.
     */
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

    /**
     * Compatibility constructor for callers that configure raw preservation and require-pragma behavior.
     *
     * <p>Lambda parentheses preserve source spelling and broken binary operators stay at the end of continuation lines.
     */
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

    /**
     * Compatibility constructor for callers that configure lambda parentheses but use the default binary-operator
     * continuation style.
     */
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

    /**
     * Validates option values before a formatter run uses them.
     *
     * <p>The width and indentation lower bounds catch configuration mistakes early. Enum options are required because
     * the formatter treats each enum as an explicit style or parser-policy choice and does not use {@code null} as a
     * hidden default.
     */
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

    /**
     * Returns the default formatter options used by {@link Frmtr#format(String)}.
     */
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

    /**
     * Returns the concrete indentation text emitted for one indentation level.
     *
     * <p>For space indentation this is {@code indentWidth} spaces. For tab indentation this is a single tab character,
     * independent of {@code indentWidth}.
     */
    public String indentUnit() {
        return indentStyle == IndentStyle.TAB ? "\t" : " ".repeat(indentWidth);
    }

    /**
     * Selects the characters used for one formatter indentation level.
     */
    public enum IndentStyle {
        /**
         * Indents nested blocks with spaces using the configured indent width.
         *
         * <p>For example, with {@code indentWidth = 2}, a block body is rendered as:
         *
         * <pre>{@code
         * class Example {
         *   void method() {}
         * }
         * }</pre>
         *
         * <p>With {@code indentWidth = 4}, the same body is rendered four spaces deeper than the class body opening
         * brace.
         */
        SPACE,
        /**
         * Indents nested blocks with tab characters instead of spaces.
         *
         * <p>Each indentation level is one {@code \t}. The configured {@code indentWidth} remains validated for API
         * consistency but does not change the rendered tab indentation unit.
         */
        TAB
    }

    /**
     * Selects the line separator emitted for formatter-created line breaks.
     */
    public enum LineEnding {
        /**
         * Emits Unix-style line feeds for every rendered line break.
         *
         * <p>For example, formatting two lines emits {@code "first\nsecond"} before the optional trailing newline is
         * applied.
         */
        LF("\n"),
        /**
         * Emits Windows-style carriage-return line feeds for every rendered line break.
         *
         * <p>For example, formatting two lines emits {@code "first\r\nsecond"} before the optional trailing newline is
         * applied.
         */
        CRLF("\r\n");

        private final String value;

        LineEnding(String value) {
            this.value = value;
        }

        /**
         * Returns the literal line-ending sequence emitted by the renderer.
         */
        public String value() {
            return value;
        }
    }

    /**
     * Controls whether single-parameter lambdas keep, avoid, or always use parentheses around the parameter list.
     *
     * <p>The option only applies when Java syntax permits a choice. Typed parameters, annotations, modifiers, multiple
     * parameters, and other syntax that requires parentheses keep them regardless of this option.
     */
    public enum LambdaArrowParens {
        /**
         * Keeps single-parameter lambda parentheses according to the parsed source.
         *
         * <p>For example, source written as {@code value -> value} stays unparenthesized, while source written as
         * {@code (value) -> value} stays parenthesized.
         */
        PRESERVE,
        /**
         * Removes parentheses from single untyped lambda parameters when Java syntax allows it.
         *
         * <p>For example, {@code (value) -> value} is rendered as {@code value -> value}. A typed lambda such as
         * {@code (String value) -> value} remains parenthesized because Java requires the parentheses.
         */
        AVOID,
        /**
         * Adds parentheses around single lambda parameters to match styles that always require them.
         *
         * <p>For example, {@code value -> value} is rendered as {@code (value) -> value}. Multi-parameter lambdas are
         * already parenthesized by Java syntax, so this option mainly affects single untyped parameters.
         */
        ALWAYS
    }

    /**
     * Controls where binary operators appear when binary expressions break across multiple lines.
     */
    public enum BinaryOperatorPosition {
        /**
         * Keeps binary operators at the end of broken continuation lines.
         *
         * <p>For example, a broken logical expression is rendered in this shape:
         *
         * <pre>{@code
         * firstCondition &&
         * secondCondition
         * }</pre>
         */
        END,
        /**
         * Moves binary operators to the start of broken continuation lines.
         *
         * <p>For example, a broken logical expression is rendered in this shape:
         *
         * <pre>{@code
         * firstCondition
         * && secondCondition
         * }</pre>
         */
        START
    }

    /**
     * Selects the Java language level passed to JavaParser before formatting.
     *
     * <p>The formatter can only format source that the configured parser level accepts. Choosing a lower release can be
     * useful when a project wants parser failures for newer syntax; choosing {@link #LATEST_AVAILABLE} tracks the
     * bundled JavaParser dependency's newest stable support. The value does not otherwise change formatter style.
     */
    public enum JavaLanguageLevel {
        /**
         * Leaves JavaParser's language level unset, which selects raw parser mode without release-specific feature gates.
         *
         * <p>Use this when callers intentionally want JavaParser's unpinned parser behavior rather than a release-specific
         * gate. It is not the default because it makes accepted syntax depend more directly on JavaParser internals.
         */
        UNSET,
        /**
         * Uses the newest stable Java language level exposed by the bundled JavaParser dependency.
         *
         * <p>This is the default because it keeps the formatter accepting modern Java as JavaParser adds support.
         */
        LATEST_AVAILABLE,

        /** Parses source using Java 8 syntax rules. */
        JAVA_8,

        /** Parses source using Java 9 syntax rules. */
        JAVA_9,

        /** Parses source using Java 10 syntax rules. */
        JAVA_10,

        /** Parses source using Java 11 syntax rules. */
        JAVA_11,

        /** Parses source using Java 12 syntax rules. */
        JAVA_12,

        /** Parses source using Java 13 syntax rules. */
        JAVA_13,

        /** Parses source using Java 14 syntax rules. */
        JAVA_14,

        /** Parses source using Java 15 syntax rules. */
        JAVA_15,

        /** Parses source using Java 16 syntax rules. */
        JAVA_16,

        /** Parses source using Java 17 syntax rules. */
        JAVA_17,

        /** Parses source using Java 18 syntax rules. */
        JAVA_18,

        /** Parses source using Java 19 syntax rules. */
        JAVA_19,

        /** Parses source using Java 20 syntax rules. */
        JAVA_20,

        /** Parses source using Java 21 syntax rules. */
        JAVA_21,

        /** Parses source using Java 22 syntax rules. */
        JAVA_22,

        /** Parses source using Java 23 syntax rules. */
        JAVA_23,

        /** Parses source using Java 24 syntax rules. */
        JAVA_24,

        /** Parses source using Java 25 syntax rules. */
        JAVA_25
    }
}
