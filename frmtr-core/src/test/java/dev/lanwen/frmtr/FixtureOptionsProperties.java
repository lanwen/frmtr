package dev.lanwen.frmtr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

final class FixtureOptionsProperties {

    private static final String DEFAULT_VARIANT = "default";

    private static final Set<String> OPTION_NAMES = Set.of(
        "line-width",
        "indent-style",
        "indent-width",
        "line-ending",
        "trailing-newline",
        "preserve-raw-trailing-whitespace",
        "require-pragma",
        "lambda-arrow-parens",
        "binary-operator-position",
        "parse-error-behavior",
        "java-language-level"
    );

    private FixtureOptionsProperties() {}

    static FormatterOptions forVariant(String variant, Path optionsFile) {
        if (DEFAULT_VARIANT.equals(variant) && !Files.isRegularFile(optionsFile)) {
            return FormatterOptions.defaults();
        }
        if (!Files.isRegularFile(optionsFile)) {
            throw new IllegalStateException(
                "Missing formatter options sidecar for variant `%s`. Expected %s.".formatted(variant, optionsFile)
            );
        }
        return formatterOptions(optionsFile, properties(optionsFile));
    }

    private static Properties properties(Path optionsFile) {
        Properties properties = new Properties();
        try (var input = Files.newInputStream(optionsFile)) {
            properties.load(input);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read formatter options from " + optionsFile, exception);
        }
        return properties;
    }

    private static FormatterOptions formatterOptions(Path optionsFile, Properties properties) {
        Set<String> unknown = new HashSet<>(properties.stringPropertyNames());
        unknown.removeAll(OPTION_NAMES);
        if (!unknown.isEmpty()) {
            throw new IllegalStateException(
                "Unsupported formatter option(s) in %s: %s.".formatted(optionsFile, unknown)
            );
        }

        FormatterOptions options = FormatterOptions.defaults();
        if (properties.containsKey("line-width")) {
            options = options.withLineWidth(intOption(optionsFile, properties, "line-width"));
        }
        if (properties.containsKey("indent-style")) {
            options = options.withIndentStyle(
                enumOption(optionsFile, properties, "indent-style", FormatterOptions.IndentStyle.class)
            );
        }
        if (properties.containsKey("indent-width")) {
            options = options.withIndentWidth(intOption(optionsFile, properties, "indent-width"));
        }
        if (properties.containsKey("line-ending")) {
            options = options.withLineEnding(
                enumOption(optionsFile, properties, "line-ending", FormatterOptions.LineEnding.class)
            );
        }
        if (properties.containsKey("trailing-newline")) {
            options = options.withTrailingNewline(booleanOption(optionsFile, properties, "trailing-newline"));
        }
        if (properties.containsKey("preserve-raw-trailing-whitespace")) {
            options = options.withPreserveRawTrailingWhitespace(
                booleanOption(optionsFile, properties, "preserve-raw-trailing-whitespace")
            );
        }
        if (properties.containsKey("require-pragma")) {
            options = options.withRequirePragma(booleanOption(optionsFile, properties, "require-pragma"));
        }
        if (properties.containsKey("lambda-arrow-parens")) {
            options = options.withLambdaArrowParens(
                enumOption(optionsFile, properties, "lambda-arrow-parens", FormatterOptions.LambdaArrowParens.class)
            );
        }
        if (properties.containsKey("binary-operator-position")) {
            options = options.withBinaryOperatorPosition(
                enumOption(
                    optionsFile,
                    properties,
                    "binary-operator-position",
                    FormatterOptions.BinaryOperatorPosition.class
                )
            );
        }
        if (properties.containsKey("parse-error-behavior")) {
            options = options.withParseErrorBehavior(
                enumOption(optionsFile, properties, "parse-error-behavior", FormatterOptions.ParseErrorBehavior.class)
            );
        }
        if (properties.containsKey("java-language-level")) {
            options = options.withJavaLanguageLevel(
                enumOption(optionsFile, properties, "java-language-level", FormatterOptions.JavaLanguageLevel.class)
            );
        }
        return options;
    }

    private static int intOption(
            Path optionsFile,
            Properties properties,
            String name
    ) {
        String value = properties.getProperty(name).trim();
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(
                "Invalid integer value `%s` for formatter option `%s` in %s.".formatted(value, name, optionsFile),
                exception
            );
        }
    }

    private static boolean booleanOption(
            Path optionsFile,
            Properties properties,
            String name
    ) {
        String value = properties.getProperty(name);
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (normalized.equals("true")) {
            return true;
        }
        if (normalized.equals("false")) {
            return false;
        }
        throw new IllegalStateException(
            "Invalid boolean value `%s` for formatter option `%s` in %s.".formatted(value, name, optionsFile)
        );
    }

    private static <T extends Enum<T>> T enumOption(
            Path optionsFile,
            Properties properties,
            String name,
            Class<T> enumType
    ) {
        String value = properties.getProperty(name);
        String normalized = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return Enum.valueOf(enumType, normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                "Invalid enum value `%s` for formatter option `%s` in %s.".formatted(value, name, optionsFile),
                exception
            );
        }
    }
}
