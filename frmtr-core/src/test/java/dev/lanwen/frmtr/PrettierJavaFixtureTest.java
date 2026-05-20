package dev.lanwen.frmtr;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.javaparser.StaticJavaParser;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

final class PrettierJavaFixtureTest {
    private static final String FIXTURE_ROOT = "format/prettier-java/unit-test";

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void formatsRepresentativePrettierJavaFixtureInputs(String fixture) throws IOException {
        String input = readFixture(fixture, "_input.java");

        String formatted = assertDoesNotThrow(() -> Frmtr.format(input));

        assertDoesNotThrow(() -> StaticJavaParser.parse(formatted));
        assertEquals(formatted, Frmtr.format(formatted));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void copiedPrettierJavaExpectedOutputsRemainParseableReferences(String fixture) throws IOException {
        String prettierOutput = readFixture(fixture, "_prettier_output.java");

        assertDoesNotThrow(() -> StaticJavaParser.parse(prettierOutput));
    }

    private static Stream<String> fixtures() {
        return Stream.of(
                "args",
                "assert",
                "extends_abstract_class",
                "extends_abstract_class_and_implements_interfaces",
                "for",
                "hello-world",
                "if",
                "instantiation",
                "marker_annotations",
                "package_and_imports/classWithMixedImports",
                "generic_class",
                "return",
                "synchronized",
                "throws",
                "types",
                "while");
    }

    private static String readFixture(String fixture, String file) throws IOException {
        try {
            Path path = Path.of(PrettierJavaFixtureTest.class
                    .getClassLoader()
                    .getResource(FIXTURE_ROOT + "/" + fixture + "/" + file)
                    .toURI());
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (URISyntaxException exception) {
            throw new IOException("Unable to load fixture " + fixture + "/" + file, exception);
        }
    }
}
