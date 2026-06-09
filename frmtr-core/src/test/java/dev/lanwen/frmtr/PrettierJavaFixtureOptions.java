package dev.lanwen.frmtr;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Properties;
import java.util.Set;

/**
 * Resolves formatter options for adopted Prettier Java fixtures from directory-local metadata.
 *
 * <p>The fixture corpus starts from the Prettier compatibility baseline, then applies
 * {@value #METADATA_FILE_NAME} files from the fixture root down to the concrete fixture directory. This keeps
 * option-matrix choices next to the fixtures that need them and lets broad fixture families, such as pragma-gated
 * cases, inherit one parent-directory override.
 */
final class PrettierJavaFixtureOptions {
    static final String METADATA_FILE_NAME = "frmtr.options.properties";

    private static final String KEY_LINE_WIDTH = "line-width";
    private static final String KEY_REQUIRE_PRAGMA = "require-pragma";
    private static final String KEY_LAMBDA_ARROW_PARENS = "lambda-arrow-parens";
    private static final String KEY_BINARY_OPERATOR_POSITION = "binary-operator-position";
    private static final Set<String> SUPPORTED_KEYS =
            Set.of(KEY_LINE_WIDTH, KEY_REQUIRE_PRAGMA, KEY_LAMBDA_ARROW_PARENS, KEY_BINARY_OPERATOR_POSITION);
    private static final FormatterOptions PRETTIER_COMPATIBILITY_OPTIONS = FormatterOptions.withRawTrailingWhitespace(
            80,
            FormatterOptions.IndentStyle.SPACE,
            2,
            FormatterOptions.LineEnding.LF,
            true,
            true,
            FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE);

    private PrettierJavaFixtureOptions() {}

    static FormatterOptions resolve(Path fixtureRoot, Path fixtureDirectory) {
        Path root = fixtureRoot.toAbsolutePath().normalize();
        Path directory = fixtureDirectory.toAbsolutePath().normalize();
        if (!directory.startsWith(root)) {
            throw new IllegalArgumentException(
                    "Fixture directory %s is outside fixture root %s".formatted(directory, root));
        }

        OptionValues values = OptionValues.from(PRETTIER_COMPATIBILITY_OPTIONS);
        for (Path current : metadataDirectories(root, directory)) {
            Path metadata = current.resolve(METADATA_FILE_NAME);
            if (Files.isRegularFile(metadata)) {
                values = apply(metadata, values);
            }
        }
        return values.toOptions();
    }

    private static Iterable<Path> metadataDirectories(Path root, Path directory) {
        var directories = new ArrayList<Path>();
        Path current = directory;
        while (current != null && current.startsWith(root)) {
            directories.add(current);
            if (current.equals(root)) {
                break;
            }
            current = current.getParent();
        }
        Collections.reverse(directories);
        return directories;
    }

    private static OptionValues apply(Path metadata, OptionValues values) {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(metadata, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException exception) {
            throw new IllegalStateException(
                    "Unable to read fixture formatter options from %s".formatted(metadata), exception);
        }

        OptionValues updated = values;
        for (String key : properties.stringPropertyNames()) {
            String value = properties.getProperty(key).trim();
            if (!SUPPORTED_KEYS.contains(key)) {
                throw new IllegalArgumentException(
                        "Unsupported fixture formatter option `%s` in %s. Supported keys: %s"
                                .formatted(key, metadata, SUPPORTED_KEYS));
            }
            updated = switch (key) {
                case KEY_LINE_WIDTH -> updated.withLineWidth(parseLineWidth(key, value, metadata));
                case KEY_REQUIRE_PRAGMA -> updated.withRequirePragma(parseBoolean(key, value, metadata));
                case KEY_LAMBDA_ARROW_PARENS ->
                    updated.withLambdaArrowParens(parseEnum(
                            FormatterOptions.LambdaArrowParens.class, key, value, metadata));
                case KEY_BINARY_OPERATOR_POSITION ->
                    updated.withBinaryOperatorPosition(parseEnum(
                            FormatterOptions.BinaryOperatorPosition.class, key, value, metadata));
                default -> throw new IllegalStateException(
                        "Unhandled fixture formatter option `%s`".formatted(key));
            };
        }
        return updated;
    }

    private static int parseLineWidth(String key, String value, Path metadata) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw invalidValue(key, value, metadata, exception);
        }
    }

    private static boolean parseBoolean(String key, String value, Path metadata) {
        return switch (value) {
            case "true" -> true;
            case "false" -> false;
            default -> throw invalidValue(key, value, metadata, null);
        };
    }

    private static <T extends Enum<T>> T parseEnum(Class<T> enumType, String key, String value, Path metadata) {
        try {
            return Enum.valueOf(enumType, value);
        } catch (IllegalArgumentException exception) {
            throw invalidValue(key, value, metadata, exception);
        }
    }

    private static IllegalArgumentException invalidValue(
            String key, String value, Path metadata, RuntimeException exception) {
        var invalidValue = new IllegalArgumentException(
                "Invalid value `%s` for fixture formatter option `%s` in %s".formatted(value, key, metadata));
        if (exception != null) {
            invalidValue.initCause(exception);
        }
        return invalidValue;
    }

    private record OptionValues(
            int lineWidth,
            boolean requirePragma,
            FormatterOptions.LambdaArrowParens lambdaArrowParens,
            FormatterOptions.BinaryOperatorPosition binaryOperatorPosition) {
        static OptionValues from(FormatterOptions options) {
            return new OptionValues(
                    options.lineWidth(),
                    options.requirePragma(),
                    options.lambdaArrowParens(),
                    options.binaryOperatorPosition());
        }

        OptionValues withLineWidth(int lineWidth) {
            return new OptionValues(lineWidth, requirePragma, lambdaArrowParens, binaryOperatorPosition);
        }

        OptionValues withRequirePragma(boolean requirePragma) {
            return new OptionValues(lineWidth, requirePragma, lambdaArrowParens, binaryOperatorPosition);
        }

        OptionValues withLambdaArrowParens(FormatterOptions.LambdaArrowParens lambdaArrowParens) {
            return new OptionValues(lineWidth, requirePragma, lambdaArrowParens, binaryOperatorPosition);
        }

        OptionValues withBinaryOperatorPosition(FormatterOptions.BinaryOperatorPosition binaryOperatorPosition) {
            return new OptionValues(lineWidth, requirePragma, lambdaArrowParens, binaryOperatorPosition);
        }

        FormatterOptions toOptions() {
            return new FormatterOptions(
                    lineWidth,
                    FormatterOptions.IndentStyle.SPACE,
                    2,
                    FormatterOptions.LineEnding.LF,
                    true,
                    true,
                    requirePragma,
                    lambdaArrowParens,
                    binaryOperatorPosition,
                    FormatterOptions.ParseErrorBehavior.RECOVER,
                    FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE);
        }
    }
}
