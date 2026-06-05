package dev.lanwen.frmtr;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class PrettierJavaCorpusTest {
    private static final String ADOPTED_ROOT = "format/prettier-java";
    private static final String EXAMPLES_ROOT = ADOPTED_ROOT + "/frmtr-output-examples";

    @Test
    void prettierJavaCorpusAndAdoptedFixturesArePresentWithLicenseAndNotice() throws Exception {
        assertThat(resource(ADOPTED_ROOT + "/LICENSE")).isNotNull();
        assertThat(resource(ADOPTED_ROOT + "/NOTICE")).isNotNull();

        Path adoptedUnitTests = Path.of(resource(ADOPTED_ROOT + "/unit-test").toURI());
        Path exampleUnitTests = Path.of(resource(EXAMPLES_ROOT + "/unit-test").toURI());

        assertThat(count(adoptedUnitTests, "input.java")).isEqualTo(84);
        assertThat(count(adoptedUnitTests, "prettier.output.java")).isEqualTo(84);
        assertThat(count(adoptedUnitTests, "frmtr.output.java")).isEqualTo(73);
        assertThat(count(exampleUnitTests, "frmtr.output.java")).isEqualTo(11);
    }

    private static java.net.URL resource(String name) {
        return PrettierJavaCorpusTest.class.getClassLoader().getResource(name);
    }

    private static long count(Path root, String fileName) throws IOException {
        try (var stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals(fileName))
                    .count();
        }
    }
}
