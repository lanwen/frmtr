package dev.lanwen.frmtr;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class PrettierJavaCorpusTest {
    private static final String CORPUS_ROOT = "upstream/prettier-java";

    @Test
    void prettierJavaCorpusIsPresentWithLicenseAndNotice() throws Exception {
        assertThat(resource(CORPUS_ROOT + "/LICENSE")).isNotNull();
        assertThat(resource(CORPUS_ROOT + "/NOTICE")).isNotNull();

        Path unitTests = Path.of(resource(CORPUS_ROOT + "/unit-test").toURI());

        assertThat(count(unitTests, "_input.java")).isEqualTo(84);
        assertThat(count(unitTests, "_output.java")).isEqualTo(84);
    }

    private static java.net.URL resource(String name) {
        return PrettierJavaCorpusTest.class.getClassLoader().getResource(name);
    }

    private static long count(Path root, String fileName) throws IOException, URISyntaxException {
        try (var stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals(fileName))
                    .count();
        }
    }
}
