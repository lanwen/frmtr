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
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

final class PrettierJavaFixtureTest {
    private static final String FIXTURE_ROOT = "format/prettier-java/unit-test";
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
