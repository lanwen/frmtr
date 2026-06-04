package dev.lanwen.frmtr;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParseStart;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Providers;
import com.github.javaparser.ast.CompilationUnit;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

final class PrettierJavaFixtureTest {
    private static final String FIXTURE_ROOT = "format/prettier-java/unit-test";
    private static final FormatterOptions PRETTIER_COMPATIBILITY_OPTIONS = new FormatterOptions(
            80,
            FormatterOptions.IndentStyle.SPACE,
            2,
            FormatterOptions.LineEnding.LF,
            true,
            true,
            FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE);
    private static final FormatterOptions PRETTIER_REQUIRE_PRAGMA_OPTIONS = new FormatterOptions(
            80,
            FormatterOptions.IndentStyle.SPACE,
            2,
            FormatterOptions.LineEnding.LF,
            true,
            true,
            true,
            FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE);
    private static final FormatterOptions PRETTIER_WIDE_COMPATIBILITY_OPTIONS = new FormatterOptions(
            320,
            FormatterOptions.IndentStyle.SPACE,
            2,
            FormatterOptions.LineEnding.LF,
            true,
            true,
            FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE);
    private static final FormatterOptions PRETTIER_ARROW_PARENS_AVOID_OPTIONS = new FormatterOptions(
            80,
            FormatterOptions.IndentStyle.SPACE,
            2,
            FormatterOptions.LineEnding.LF,
            true,
            true,
            false,
            FormatterOptions.LambdaArrowParens.AVOID,
            FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE);
    private static final FormatterOptions PRETTIER_ARROW_PARENS_ALWAYS_OPTIONS = new FormatterOptions(
            80,
            FormatterOptions.IndentStyle.SPACE,
            2,
            FormatterOptions.LineEnding.LF,
            true,
            true,
            false,
            FormatterOptions.LambdaArrowParens.ALWAYS,
            FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE);
    private static final FormatterOptions PRETTIER_BINARY_OPERATOR_POSITION_START_OPTIONS = new FormatterOptions(
            80,
            FormatterOptions.IndentStyle.SPACE,
            2,
            FormatterOptions.LineEnding.LF,
            true,
            true,
            false,
            FormatterOptions.LambdaArrowParens.PRESERVE,
            FormatterOptions.BinaryOperatorPosition.START,
            FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE);
    private static final Set<String> PRETTIER_COMPATIBLE_FIXTURES = Set.of(
            "args",
            "arrays",
            "annotation_interface_declaration",
            "assert",
            "binary_expressions/operator-position-end",
            "binary_expressions/operator-position-start",
            "blank_lines",
            "bug-fixes",
            "cast",
            "char_literal",
            "classes",
            "comments/bug-fixes",
            "comments/class",
            "comments/comments-blocks-and-statements/complex",
            "comments/comments-blocks-and-statements/end-of-block",
            "comments/comments-blocks-and-statements/if-statement",
            "comments/comments-blocks-and-statements/labeled-statement",
            "comments/edge",
            "comments/expression",
            "comments/comments-only",
            "comments/interface",
            "comments/package",
            "complex_generic_class",
            "constructors",
            "empty_statement",
            "enum",
            "expressions",
            "extends_abstract_class",
            "extends_abstract_class_and_implements_interfaces",
            "formatter-on-off/begin_with_on",
            "formatter-on-off/class",
            "formatter-on-off/end_with_off",
            "formatter-on-off/inside_block",
            "formatter-on-off/method",
            "formatter-on-off/multiple",
            "for",
            "generic_class",
            "generic_questionmark",
            "hello-world",
            "if",
            "indent",
            "instantiation",
            "interface",
            "member_chain",
            "method_reference",
            "marker_annotations",
            "modifiers",
            "modules",
            "package_and_imports/classWithMixedImports",
            "package_and_imports/classWithMixedCaseImports",
            "package_and_imports/classWithNoImports",
            "package_and_imports/classWithOnlyNonStaticImports",
            "package_and_imports/classWithOnlyStaticImports",
            "package_and_imports/moduleWithMixedImports",
            "package_and_imports/moduleWithNoImports",
            "package_and_imports/moduleWithOnlyNonStaticImports",
            "package_and_imports/moduleWithOnlyStaticImports",
            "pattern-matching",
            "prettier-ignore/block",
            "prettier-ignore/classDeclaration",
            "prettier-ignore/method",
            "prettier-ignore/multiple-ignore",
            "require-pragma/format-pragma",
            "require-pragma/invalid-pragma",
            "require-pragma/prettier-pragma",
            "records",
            "return",
            "sealed",
            "synchronized",
            "switch",
            "template-expression",
            "text-blocks",
            "throws",
            "try_catch",
            "types",
            "unnamed-class-compilation-unit",
            "unnamed-variables-and-patterns",
            "variables",
            "while",
            "yield-statement");
    private static final JavaParser PARSER = new JavaParser(new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
            .setStoreTokens(true)
            .setAttributeComments(true));

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void adoptedPrettierJavaFixtureInputsAndReferenceOutputsAreParseable(Fixture fixture) throws IOException {
        assertParseable(fixture, fixture.input());
        assertParseable(fixture, fixture.prettierOutput());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void currentFormatterOutputMatchesCheckedInSnapshot(Fixture fixture) throws IOException {
        String input = read(fixture.input());

        String formatted = Frmtr.format(input);

        assertThat(formatted).isEqualTo(read(fixture.frmtrOutput()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("prettierCompatibleFixtures")
    void currentFormatterOutputMatchesPrettierReference(Fixture fixture) throws IOException {
        String input = read(fixture.input());

        String formatted = Frmtr.format(input, prettierCompatibilityOptions(fixture));

        assertThat(formatted).isEqualTo(read(fixture.prettierOutput()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void adoptedPrettierJavaFixtureCompanionsArePresent(Fixture fixture) {
        assertThat(fixture.input()).isRegularFile();
        assertThat(fixture.prettierOutput()).isRegularFile();
        assertThat(fixture.frmtrOutput()).isRegularFile();
    }

    private static Stream<Fixture> fixtures() throws IOException, URISyntaxException {
        Path root = fixtureRoot();
        try (var stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals("input.java"))
                    .sorted()
                    .map(input -> fixture(root, input))
                    .toList()
                    .stream();
        }
    }

    private static Stream<Fixture> prettierCompatibleFixtures() throws IOException, URISyntaxException {
        return fixtures().filter(fixture -> PRETTIER_COMPATIBLE_FIXTURES.contains(fixture.name()));
    }

    private static FormatterOptions prettierCompatibilityOptions(Fixture fixture) {
        if (fixture.name().startsWith("require-pragma/")) {
            return PRETTIER_REQUIRE_PRAGMA_OPTIONS;
        }
        if (fixture.name().equals("unnamed-variables-and-patterns")) {
            return PRETTIER_WIDE_COMPATIBILITY_OPTIONS;
        }
        if (fixture.name().equals("lambda/arrow-parens-avoid")) {
            return PRETTIER_ARROW_PARENS_AVOID_OPTIONS;
        }
        if (fixture.name().equals("lambda/arrow-parens-always")) {
            return PRETTIER_ARROW_PARENS_ALWAYS_OPTIONS;
        }
        if (fixture.name().equals("binary_expressions/operator-position-start")) {
            return PRETTIER_BINARY_OPERATOR_POSITION_START_OPTIONS;
        }
        return PRETTIER_COMPATIBILITY_OPTIONS;
    }

    private static Fixture fixture(Path root, Path input) {
        Path directory = input.getParent();
        return new Fixture(
                root.relativize(directory).toString(),
                input,
                directory.resolve("prettier.output.java"),
                directory.resolve("frmtr.output.java"));
    }

    private static Path fixtureRoot() throws URISyntaxException {
        return Path.of(Objects.requireNonNull(
                                PrettierJavaFixtureTest.class.getClassLoader().getResource(FIXTURE_ROOT),
                                "Missing fixture root " + FIXTURE_ROOT)
                        .toURI())
                .toAbsolutePath()
                .normalize();
    }

    private static void assertParseable(Fixture fixture, Path file) throws IOException {
        ParseResult<CompilationUnit> result = PARSER.parse(ParseStart.COMPILATION_UNIT, Providers.provider(read(file)));

        assertThat(result.getProblems()).describedAs("%s in %s", file.getFileName(), fixture).isEmpty();
        assertThat(result.getResult()).describedAs("%s in %s", file.getFileName(), fixture).isPresent();
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private record Fixture(String name, Path input, Path prettierOutput, Path frmtrOutput) {
        @Override
        public String toString() {
            return name;
        }
    }
}
