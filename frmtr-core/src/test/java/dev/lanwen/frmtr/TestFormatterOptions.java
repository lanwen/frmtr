package dev.lanwen.frmtr;

public final class TestFormatterOptions {

    private TestFormatterOptions() {}

    public static FormatterOptions forLayout(
            int lineWidth,
            FormatterOptions.IndentStyle indentStyle,
            int indentWidth,
            FormatterOptions.LineEnding lineEnding,
            boolean trailingNewline
    ) {
        return withRawTrailingWhitespace(
            lineWidth,
            indentStyle,
            indentWidth,
            lineEnding,
            trailingNewline,
            false,
            FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE
        );
    }

    public static FormatterOptions withRawTrailingWhitespace(
            int lineWidth,
            FormatterOptions.IndentStyle indentStyle,
            int indentWidth,
            FormatterOptions.LineEnding lineEnding,
            boolean trailingNewline,
            boolean preserveRawTrailingWhitespace,
            FormatterOptions.JavaLanguageLevel javaLanguageLevel
    ) {
        return withPragmaRequirement(
            lineWidth,
            indentStyle,
            indentWidth,
            lineEnding,
            trailingNewline,
            preserveRawTrailingWhitespace,
            false,
            javaLanguageLevel
        );
    }

    public static FormatterOptions withPragmaRequirement(
            int lineWidth,
            FormatterOptions.IndentStyle indentStyle,
            int indentWidth,
            FormatterOptions.LineEnding lineEnding,
            boolean trailingNewline,
            boolean preserveRawTrailingWhitespace,
            boolean requirePragma,
            FormatterOptions.JavaLanguageLevel javaLanguageLevel
    ) {
        return withLambdaArrowParens(
            lineWidth,
            indentStyle,
            indentWidth,
            lineEnding,
            trailingNewline,
            preserveRawTrailingWhitespace,
            requirePragma,
            FormatterOptions.LambdaArrowParens.PRESERVE,
            javaLanguageLevel
        );
    }

    public static FormatterOptions withLambdaArrowParens(
            int lineWidth,
            FormatterOptions.IndentStyle indentStyle,
            int indentWidth,
            FormatterOptions.LineEnding lineEnding,
            boolean trailingNewline,
            boolean preserveRawTrailingWhitespace,
            boolean requirePragma,
            FormatterOptions.LambdaArrowParens lambdaArrowParens,
            FormatterOptions.JavaLanguageLevel javaLanguageLevel
    ) {
        return new FormatterOptions(
            lineWidth,
            indentStyle,
            indentWidth,
            lineEnding,
            trailingNewline,
            preserveRawTrailingWhitespace,
            requirePragma,
            lambdaArrowParens,
            FormatterOptions.defaults().binaryOperatorPosition(),
            FormatterOptions.ParseErrorBehavior.RECOVER,
            javaLanguageLevel
        );
    }
}
