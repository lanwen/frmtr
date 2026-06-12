package dev.lanwen.frmtr;

import java.util.Objects;

/**
 * Public formatting configuration used by the Java formatter, CLI, and Gradle adapter.
 *
 * <p>The options in this record are intentionally formatter-wide. They affect parsing, document rendering, or syntax
 * decisions that must stay stable across all Java constructs. Node-specific layout rules, such as how method chains or
 * switch entries break, remain internal formatter policy rather than public options.
 *
 * <p>The default configuration is returned by {@link #defaults()}: the default target line width, four-space
 * indentation, LF line endings, a trailing newline, no raw-trailing-whitespace preservation, no require-pragma gate,
 * source-preserving single-parameter lambda parentheses, binary operators at the end of broken continuation lines,
 * parse-error recovery, and the latest Java language level exposed by the bundled JavaParser dependency's bleeding-edge
 * parser mode.
 *
 * <p>Use the canonical record constructor when every option is intentionally selected. Use {@link #defaults()} when the
 * remaining formatter policy should stay at defaults, then use focused instance withers such as {@link
 * #withLineWidth(int)} and {@link #withParseErrorBehavior(ParseErrorBehavior)} for one-policy changes from that preset.
 * Adapters that expose parser language level alongside layout options can use {@link #withJavaLanguageLevel(int,
 * IndentStyle, int, LineEnding, boolean, JavaLanguageLevel)}.
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
 *     {@code true}, the public opt-in marker is {@code @format} in the leading Javadoc comment; sources without a
 *     recognized opt-in marker are returned unchanged. When {@code false}, every parseable source is eligible for
 *     formatting.
 * @param lambdaArrowParens policy for single-parameter lambda parentheses. See {@link LambdaArrowParens} for examples
 *     of how {@code value -> value}, {@code (value) -> value}, and typed parameters are handled.
 * @param binaryOperatorPosition where binary operators appear when a binary expression breaks across continuation
 *     lines. See {@link BinaryOperatorPosition} for examples of end-position and start-position output.
 * @param parseErrorBehavior whether JavaParser parse problems should enter formatter recovery or fail immediately.
 *     Recovery is the default public behavior, while {@link ParseErrorBehavior#FAIL} keeps strict fail-on-problem
 *     behavior for callers that need parse errors to stop formatting.
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
    ParseErrorBehavior parseErrorBehavior,
    JavaLanguageLevel javaLanguageLevel
) {
    /**
     * Default target line width used by {@link #defaults()}.
     *
     * <p>This width is intentionally wider than the compatibility fixture mode used in tests. Adapters may expose this
     * as a user setting when projects prefer narrower or wider Java output.
     */
    public static final int DEFAULT_LINE_WIDTH = 120;

    /**
     * Default number of spaces per indentation level used by {@link #defaults()}.
     */
    public static final int DEFAULT_INDENT_WIDTH = 4;

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
        Objects.requireNonNull(parseErrorBehavior, "parseErrorBehavior");
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
            ParseErrorBehavior.RECOVER,
            JavaLanguageLevel.LATEST_AVAILABLE
        );
    }

    /**
     * Creates options for callers that configure document-rendering shape and parser language level.
     *
     * <p>Raw trailing whitespace is not preserved, require-pragma is disabled, lambda parentheses preserve source
     * spelling, broken binary operators stay at the end of continuation lines, and parse-error behavior defaults to
     * {@link ParseErrorBehavior#RECOVER}.
     */
    public static FormatterOptions withJavaLanguageLevel(
        int lineWidth,
        IndentStyle indentStyle,
        int indentWidth,
        LineEnding lineEnding,
        boolean trailingNewline,
        JavaLanguageLevel javaLanguageLevel
    ) {
        return new FormatterOptions(
            lineWidth,
            indentStyle,
            indentWidth,
            lineEnding,
            trailingNewline,
            false,
            false,
            LambdaArrowParens.PRESERVE,
            BinaryOperatorPosition.END,
            ParseErrorBehavior.RECOVER,
            javaLanguageLevel
        );
    }

    /**
     * Returns options that keep this instance's existing formatter policy while changing the target line width.
     */
    public FormatterOptions withLineWidth(int lineWidth) {
        return new FormatterOptions(
            lineWidth,
            indentStyle,
            indentWidth,
            lineEnding,
            trailingNewline,
            preserveRawTrailingWhitespace,
            requirePragma,
            lambdaArrowParens,
            binaryOperatorPosition,
            parseErrorBehavior,
            javaLanguageLevel
        );
    }

    /**
     * Returns options that keep this instance's existing formatter policy while changing indentation style.
     */
    public FormatterOptions withIndentStyle(IndentStyle indentStyle) {
        return new FormatterOptions(
            lineWidth,
            indentStyle,
            indentWidth,
            lineEnding,
            trailingNewline,
            preserveRawTrailingWhitespace,
            requirePragma,
            lambdaArrowParens,
            binaryOperatorPosition,
            parseErrorBehavior,
            javaLanguageLevel
        );
    }

    /**
     * Returns options that keep this instance's existing formatter policy while changing space indentation width.
     */
    public FormatterOptions withIndentWidth(int indentWidth) {
        return new FormatterOptions(
            lineWidth,
            indentStyle,
            indentWidth,
            lineEnding,
            trailingNewline,
            preserveRawTrailingWhitespace,
            requirePragma,
            lambdaArrowParens,
            binaryOperatorPosition,
            parseErrorBehavior,
            javaLanguageLevel
        );
    }

    /**
     * Returns options that keep this instance's existing formatter policy while changing emitted line endings.
     */
    public FormatterOptions withLineEnding(LineEnding lineEnding) {
        return new FormatterOptions(
            lineWidth,
            indentStyle,
            indentWidth,
            lineEnding,
            trailingNewline,
            preserveRawTrailingWhitespace,
            requirePragma,
            lambdaArrowParens,
            binaryOperatorPosition,
            parseErrorBehavior,
            javaLanguageLevel
        );
    }

    /**
     * Returns options that keep this instance's existing formatter policy while changing final newline behavior.
     */
    public FormatterOptions withTrailingNewline(boolean trailingNewline) {
        return new FormatterOptions(
            lineWidth,
            indentStyle,
            indentWidth,
            lineEnding,
            trailingNewline,
            preserveRawTrailingWhitespace,
            requirePragma,
            lambdaArrowParens,
            binaryOperatorPosition,
            parseErrorBehavior,
            javaLanguageLevel
        );
    }

    /**
     * Returns options that keep this instance's existing formatter policy while changing raw whitespace preservation.
     */
    public FormatterOptions withPreserveRawTrailingWhitespace(boolean preserveRawTrailingWhitespace) {
        return new FormatterOptions(
            lineWidth,
            indentStyle,
            indentWidth,
            lineEnding,
            trailingNewline,
            preserveRawTrailingWhitespace,
            requirePragma,
            lambdaArrowParens,
            binaryOperatorPosition,
            parseErrorBehavior,
            javaLanguageLevel
        );
    }

    /**
     * Returns options that keep this instance's existing formatter policy while changing pragma-gated formatting.
     */
    public FormatterOptions withRequirePragma(boolean requirePragma) {
        return new FormatterOptions(
            lineWidth,
            indentStyle,
            indentWidth,
            lineEnding,
            trailingNewline,
            preserveRawTrailingWhitespace,
            requirePragma,
            lambdaArrowParens,
            binaryOperatorPosition,
            parseErrorBehavior,
            javaLanguageLevel
        );
    }

    /**
     * Returns options that keep this instance's existing formatter policy while changing lambda-parentheses style.
     */
    public FormatterOptions withLambdaArrowParens(LambdaArrowParens lambdaArrowParens) {
        return new FormatterOptions(
            lineWidth,
            indentStyle,
            indentWidth,
            lineEnding,
            trailingNewline,
            preserveRawTrailingWhitespace,
            requirePragma,
            lambdaArrowParens,
            binaryOperatorPosition,
            parseErrorBehavior,
            javaLanguageLevel
        );
    }

    /**
     * Returns options that keep this instance's existing formatter policy while changing binary-operator placement.
     */
    public FormatterOptions withBinaryOperatorPosition(BinaryOperatorPosition binaryOperatorPosition) {
        return new FormatterOptions(
            lineWidth,
            indentStyle,
            indentWidth,
            lineEnding,
            trailingNewline,
            preserveRawTrailingWhitespace,
            requirePragma,
            lambdaArrowParens,
            binaryOperatorPosition,
            parseErrorBehavior,
            javaLanguageLevel
        );
    }

    /**
     * Returns options that keep this instance's existing formatter policy while changing parse-error behavior.
     *
     * <p>Use this after {@link #defaults()} or {@link #withJavaLanguageLevel(int, IndentStyle, int, LineEnding,
     * boolean, JavaLanguageLevel)} when only parse-problem handling should differ from that preset.
     */
    public FormatterOptions withParseErrorBehavior(ParseErrorBehavior parseErrorBehavior) {
        return new FormatterOptions(
            lineWidth,
            indentStyle,
            indentWidth,
            lineEnding,
            trailingNewline,
            preserveRawTrailingWhitespace,
            requirePragma,
            lambdaArrowParens,
            binaryOperatorPosition,
            parseErrorBehavior,
            javaLanguageLevel
        );
    }

    /**
     * Returns options that keep this instance's existing formatter policy while changing parser language level.
     */
    public FormatterOptions withJavaLanguageLevel(JavaLanguageLevel javaLanguageLevel) {
        return new FormatterOptions(
            lineWidth,
            indentStyle,
            indentWidth,
            lineEnding,
            trailingNewline,
            preserveRawTrailingWhitespace,
            requirePragma,
            lambdaArrowParens,
            binaryOperatorPosition,
            parseErrorBehavior,
            javaLanguageLevel
        );
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
        TAB,
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
        ALWAYS,
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
        START,
    }

    /**
     * Selects how formatter entry points respond when JavaParser reports parse problems.
     *
     * <p>This option controls the boundary between parsing and printing. It does not repair Java syntax; recovery, when
     * supported by the formatter pipeline, preserves unparsed source regions while formatting safe parsed siblings.
     */
    public enum ParseErrorBehavior {
        /**
         * Attempts formatter recovery when JavaParser returns a partial compilation unit with parse problems.
         *
         * <p>Use this for editor and local formatting flows where valid surrounding code should still be formatted while
         * broken source is preserved. If JavaParser cannot provide a usable compilation unit, or if the current printers
         * cannot safely preserve the recovered region, formatting still fails with a parse-error exception.
         */
        RECOVER,
        /**
         * Fails formatting as soon as JavaParser reports any parse problem.
         *
         * <p>Use this for strict automation and compatibility flows that require the previous all-or-nothing parse
         * behavior. No recovered compilation unit is passed to the printer in this mode.
         */
        FAIL,
    }

    /**
     * Selects the Java language level passed to JavaParser before formatting.
     *
     * <p>The formatter can only format source that the configured parser level accepts. Choosing a lower release can be
     * useful when a project wants parser failures for newer syntax; choosing {@link #LATEST_AVAILABLE} uses the bundled
     * JavaParser dependency's bleeding-edge parser mode. The value does not otherwise change formatter style.
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
         * Uses the newest Java language level exposed by the bundled JavaParser dependency's bleeding-edge parser mode.
         *
         * <p>This is the default because it keeps the formatter accepting modern Java as JavaParser adds support. It can
         * accept syntax before JavaParser exposes a release-specific stable enum for that syntax, so callers that need a
         * strict release gate should choose a concrete {@code JAVA_*} value instead.
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

        JAVA_25,
    }
}
