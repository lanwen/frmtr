package dev.lanwen.frmtr.tooling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BestEffortAtomicFileWriterTest {

    @Test
    void writesCompleteContentsThroughStagedReplace(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("Changed.java");
        Files.writeString(target, "class Changed{int value;}", StandardCharsets.UTF_8);

        BestEffortAtomicFileWriter.writeString(target, "class Changed {\n\n    int value;\n}\n");

        assertThat(Files.readString(target, StandardCharsets.UTF_8))
                .isEqualTo("class Changed {\n\n    int value;\n}\n");
        assertThat(tempFilesIn(dir)).isEmpty();
    }

    @Test
    void deletesTempFileWhenReplaceFailsAfterStaging(@TempDir Path dir) throws IOException {
        Path target = dir.resolve("Changed.java");
        Files.createDirectories(target);

        assertThatThrownBy(() -> BestEffortAtomicFileWriter.writeString(target, "class Changed {}\n"))
                .isInstanceOf(IOException.class);

        assertThat(Files.isDirectory(target)).isTrue();
        assertThat(tempFilesIn(dir)).isEmpty();
    }

    private static Iterable<Path> tempFilesIn(Path dir) throws IOException {
        try (var entries = Files.list(dir)) {
            return entries
                    .filter(path -> path.getFileName().toString().endsWith(BestEffortAtomicFileWriter.TEMP_SUFFIX))
                    .toList();
        }
    }
}
