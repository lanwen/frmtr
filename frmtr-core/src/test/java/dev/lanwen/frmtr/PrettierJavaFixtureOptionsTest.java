package dev.lanwen.frmtr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PrettierJavaFixtureOptionsTest {
    @TempDir
    private Path temporaryDirectory;

    @Test
    void inheritsOptionsFromParentDirectories() throws IOException {
        Path root = temporaryDirectory.resolve("unit-test");
        Path fixture = root.resolve("require-pragma").resolve("format-pragma");
        writeOptions(root.resolve("require-pragma"), "requirePragma=true\n");

        FormatterOptions options = PrettierJavaFixtureOptions.resolve(root, fixture);

        assertThat(options.requirePragma()).isTrue();
        assertThat(options.lineWidth()).isEqualTo(80);
    }

    @Test
    void childDirectoryMetadataOverridesParentMetadata() throws IOException {
        Path root = temporaryDirectory.resolve("unit-test");
        Path parent = root.resolve("lambda");
        Path fixture = parent.resolve("arrow-parens-avoid");
        writeOptions(parent, "lambdaArrowParens=ALWAYS\nbinaryOperatorPosition=END\nlineWidth=100\n");
        writeOptions(fixture, "lambdaArrowParens=AVOID\nbinaryOperatorPosition=START\n");

        FormatterOptions options = PrettierJavaFixtureOptions.resolve(root, fixture);

        assertThat(options.lineWidth()).isEqualTo(100);
        assertThat(options.lambdaArrowParens()).isEqualTo(FormatterOptions.LambdaArrowParens.AVOID);
        assertThat(options.binaryOperatorPosition()).isEqualTo(FormatterOptions.BinaryOperatorPosition.START);
    }

    @Test
    void rejectsUnknownMetadataKeys() throws IOException {
        Path root = temporaryDirectory.resolve("unit-test");
        Path fixture = root.resolve("fixture");
        writeOptions(fixture, "tabWidth=2\n");

        assertThatThrownBy(() -> PrettierJavaFixtureOptions.resolve(root, fixture))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported fixture formatter option `tabWidth`");
    }

    @Test
    void rejectsInvalidMetadataValues() throws IOException {
        Path root = temporaryDirectory.resolve("unit-test");
        Path fixture = root.resolve("fixture");
        writeOptions(fixture, "requirePragma=sometimes\n");

        assertThatThrownBy(() -> PrettierJavaFixtureOptions.resolve(root, fixture))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid value `sometimes` for fixture formatter option `requirePragma`");
    }

    @Test
    void resolvesCheckedInFixtureMetadata() throws Exception {
        Path root = fixtureRoot();

        assertThat(checkedInMetadata(root))
                .isNotEmpty()
                .allSatisfy(metadata ->
                        assertThat(PrettierJavaFixtureOptions.resolve(root, metadata.getParent()))
                                .describedAs("options resolved from %s", metadata)
                                .isNotNull());
    }

    @Test
    void checkedInFixtureMetadataHasExpectedEffectiveValues() throws Exception {
        Path root = fixtureRoot();

        assertThat(PrettierJavaFixtureOptions.resolve(root, root.resolve("require-pragma/format-pragma"))
                        .requirePragma())
                .isTrue();
        assertThat(PrettierJavaFixtureOptions.resolve(root, root.resolve("lambda/arrow-parens-avoid"))
                        .lambdaArrowParens())
                .isEqualTo(FormatterOptions.LambdaArrowParens.AVOID);
        assertThat(PrettierJavaFixtureOptions.resolve(root, root.resolve("lambda/arrow-parens-always"))
                        .lambdaArrowParens())
                .isEqualTo(FormatterOptions.LambdaArrowParens.ALWAYS);
        assertThat(PrettierJavaFixtureOptions.resolve(root, root.resolve("binary_expressions/operator-position-start"))
                        .binaryOperatorPosition())
                .isEqualTo(FormatterOptions.BinaryOperatorPosition.START);
        assertThat(PrettierJavaFixtureOptions.resolve(root, root.resolve("unnamed-variables-and-patterns"))
                        .lineWidth())
                .isEqualTo(320);
    }

    private static Path fixtureRoot() throws Exception {
        return Path.of(Objects.requireNonNull(
                                PrettierJavaFixtureOptionsTest.class
                                        .getClassLoader()
                                        .getResource("format/prettier-java/unit-test"),
                                "Missing Prettier Java fixture root")
                        .toURI())
                .toAbsolutePath()
                .normalize();
    }

    private static Iterable<Path> checkedInMetadata(Path root) throws IOException {
        try (var stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName()
                            .toString()
                            .equals(PrettierJavaFixtureOptions.METADATA_FILE_NAME))
                    .sorted()
                    .toList();
        }
    }

    private static void writeOptions(Path directory, String content) throws IOException {
        Files.createDirectories(directory);
        Files.writeString(directory.resolve(PrettierJavaFixtureOptions.METADATA_FILE_NAME), content);
    }
}
