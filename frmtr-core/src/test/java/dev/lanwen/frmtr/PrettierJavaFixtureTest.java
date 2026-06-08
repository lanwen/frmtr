package dev.lanwen.frmtr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

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
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

final class PrettierJavaFixtureTest {
    private static final String FIXTURE_ROOT = "format/prettier-java/unit-test";
    private static final Set<String> PRETTIER_COMPATIBLE_FIXTURES = Set.of(
            "args",
            "arrays",
            "annotation_interface_declaration",
            "assert",
            "blank_lines",
            "bug-fixes",
            "cast",
            "char_literal",
            "classes",
            "comments/bug-fixes",
            "comments/class",
            "comments/comments-blocks-and-statements/end-of-block",
            "comments/comments-blocks-and-statements/if-statement",
            "comments/comments-blocks-and-statements/labeled-statement",
            "comments/edge",
            "comments/comments-only",
            "comments/interface",
            "comments/package",
            "complex_generic_class",
            "constructors",
            "empty_statement",
            "enum",
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
            "lambda/arrow-parens-always",
            "lambda/arrow-parens-avoid",
            "member_chain",
            "method_reference",
            "marker_annotations",
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
            "text-blocks",
            "throws",
            "try_catch",
            "types",
            "unnamed-class-compilation-unit",
            "variables",
            "while",
            "yield-statement");
    private static final Map<String, String> JAVA_PARSER_UNSUPPORTED_FIXTURES = Map.ofEntries(
            Map.entry(
                    "binary_expressions/operator-position-end",
                    "contains standalone binary expressions and pattern matching expressions that are not valid Java expression statements"),
            Map.entry(
                    "binary_expressions/operator-position-start",
                    "contains standalone binary expressions and pattern matching expressions that are not valid Java expression statements"),
            Map.entry(
                    "comments/comments-blocks-and-statements/complex",
                    "declares a class field with `var`, which JavaParser rejects because `var` is only valid for local variables"),
            Map.entry("comments/expression", "contains a standalone numeric literal expression statement"),
            Map.entry("conditional-expression/spaces", "contains standalone conditional expression statements"),
            Map.entry("conditional-expression/tabs", "contains standalone conditional expression statements"),
            Map.entry(
                    "expressions",
                    "contains standalone method-reference and array-access expression statements that JavaParser rejects"),
            Map.entry("interface", "declares a top-level private interface"),
            Map.entry("modifiers", "contains invalid top-level and member modifier combinations"),
            Map.entry(
                    "template-expression",
                    "uses Java 21 preview string template syntax, which JavaParser 3.28.1 does not parse"),
            Map.entry(
                    "unnamed-variables-and-patterns",
                    "uses multiple switch pattern labels, which JavaParser 3.28.1 does not parse"));
    private static final JavaParser PARSER = new JavaParser(new ParserConfiguration()
            .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
            .setStoreTokens(true)
            .setAttributeComments(true));

    @ParameterizedTest(name = "{0}")
    @MethodSource("javaParserSupportedFixtures")
    void javaParserSupportedPrettierJavaFixtureInputsAndReferenceOutputsAreParseable(Fixture fixture)
            throws IOException {
        assertParseable(fixture, fixture.input());
        assertParseable(fixture, fixture.prettierOutput());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("javaParserSupportedFixtures")
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
    void adoptedPrettierJavaFixtureUpstreamCompanionsArePresent(Fixture fixture) {
        assertThat(fixture.input()).isRegularFile();
        assertThat(fixture.prettierOutput()).isRegularFile();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("javaParserSupportedFixtures")
    void javaParserSupportedPrettierJavaFixtureSnapshotsArePresent(Fixture fixture) {
        assertThat(fixture.frmtrOutput()).isRegularFile();
    }

    @Test
    void javaParserUnsupportedPrettierJavaFixturesAreExplicitlyEnumerated()
            throws IOException, URISyntaxException {
        var fixtures = fixtures().toList();
        var fixtureNames = fixtures.stream().map(Fixture::name).toList();

        assertThat(JAVA_PARSER_UNSUPPORTED_FIXTURES)
                .allSatisfy((fixtureName, reason) -> {
                    Fixture fixture = fixtures.stream()
                            .filter(candidate -> candidate.name().equals(fixtureName))
                            .findFirst()
                            .orElseThrow();
                    assertThat(fixtureNames).contains(fixture.name());
                    assertThat(PRETTIER_COMPATIBLE_FIXTURES).doesNotContain(fixtureName);
                    assertThat(reason).isNotBlank();
                    assertThat(fixture.frmtrOutput()).doesNotExist();
                    assertThat(fixture.frmtrExampleOutput()).isRegularFile();
                });

        assertThat(fixtures.stream()
                        .filter(fixture -> !isJavaParserSupported(fixture))
                        .map(Fixture::name))
                .containsExactlyInAnyOrderElementsOf(JAVA_PARSER_UNSUPPORTED_FIXTURES.keySet());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("javaParserUnsupportedFixtures")
    void javaParserUnsupportedPrettierJavaFixtureInputsFailWithFormatterParseError(Fixture fixture)
            throws IOException {
        Throwable thrown = catchThrowable(() -> Frmtr.format(read(fixture.input())));

        assertThat(thrown)
                .describedAs("%s should stay skipped until JavaParser can parse its input: %s", fixture, unsupportedReason(fixture))
                .isInstanceOf(FormatterException.class)
                .hasMessageContaining("Unable to parse Java source:");
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

    private static Stream<Fixture> javaParserSupportedFixtures() throws IOException, URISyntaxException {
        return fixtures().filter(fixture -> !JAVA_PARSER_UNSUPPORTED_FIXTURES.containsKey(fixture.name()));
    }

    private static Stream<Fixture> javaParserUnsupportedFixtures() throws IOException, URISyntaxException {
        return fixtures().filter(fixture -> JAVA_PARSER_UNSUPPORTED_FIXTURES.containsKey(fixture.name()));
    }

    private static String unsupportedReason(Fixture fixture) {
        return JAVA_PARSER_UNSUPPORTED_FIXTURES.get(fixture.name());
    }

    private static FormatterOptions prettierCompatibilityOptions(Fixture fixture) {
        return PrettierJavaFixtureOptions.resolve(fixture.fixtureRoot(), fixture.directory());
    }

    private static Fixture fixture(Path root, Path input) {
        Path directory = input.getParent();
        Path name = root.relativize(directory);
        return new Fixture(
                name.toString(),
                root,
                directory,
                input,
                directory.resolve("prettier.output.java"),
                directory.resolve("frmtr.output.java"),
                root.getParent()
                        .resolve("frmtr-output-examples")
                        .resolve("unit-test")
                        .resolve(name)
                        .resolve("frmtr.output.java"));
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

    private static boolean isJavaParserSupported(Fixture fixture) {
        return isParseable(fixture.input()) && isParseable(fixture.prettierOutput());
    }

    private static boolean isParseable(Path file) {
        try {
            ParseResult<CompilationUnit> result =
                    PARSER.parse(ParseStart.COMPILATION_UNIT, Providers.provider(read(file)));
            return result.getProblems().isEmpty() && result.getResult().isPresent();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read fixture " + file, exception);
        }
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private record Fixture(
            String name,
            Path fixtureRoot,
            Path directory,
            Path input,
            Path prettierOutput,
            Path frmtrOutput,
            Path frmtrExampleOutput) {
        @Override
        public String toString() {
            return name;
        }
    }
}
