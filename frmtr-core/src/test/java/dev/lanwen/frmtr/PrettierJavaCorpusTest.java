package dev.lanwen.frmtr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class PrettierJavaCorpusTest {
    private static final String CORPUS_ROOT = "upstream/prettier-java";

    @Test
    void prettierJavaCorpusIsPresentWithLicenseAndNotice() throws Exception {
        assertNotNull(resource(CORPUS_ROOT + "/LICENSE"));
        assertNotNull(resource(CORPUS_ROOT + "/NOTICE"));

        Path unitTests = Path.of(resource(CORPUS_ROOT + "/unit-test").toURI());

        assertEquals(84, count(unitTests, "_input.java"));
        assertEquals(84, count(unitTests, "_output.java"));
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
