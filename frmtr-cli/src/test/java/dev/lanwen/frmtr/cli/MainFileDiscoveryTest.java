package dev.lanwen.frmtr.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MainFileDiscoveryTest {

    @Test
    void writeSummaryCountsExcludedJavaFiles(@TempDir Path dir) throws IOException {
        write(dir.resolve("src/Kept.java"), "class Kept{int value;}");
        write(dir.resolve("src/generated/Generated.java"), "class Generated{int value;}");

        Result result = run(dir, null, "--write", "--exclude", "src/generated", ".");

        assertThat(result.exitCode()).isZero();
        assertThat(result.out()).isEqualTo("Processed 2 files: 1 formatted, 1 excluded.\n");
        assertThat(result.err()).isEmpty();
        assertThat(Files.readString(dir.resolve("src/Kept.java"))).isEqualTo(
            """
                class Kept {

                    int value;
                }
                """
        );
        assertThat(Files.readString(dir.resolve("src/generated/Generated.java"))).isEqualTo(
            "class Generated{int value;}"
        );
    }

    @Test
    void reportsNoJavaFilesForUnknownExtensionsAndUnmatchedGlobs(@TempDir Path dir) {
        write(dir.resolve("src/notes.txt"), "class Main{int value;}");

        Result result = run(dir, null, "src/**/*.txt,missing/**/*.java");

        assertThat(result.exitCode()).isZero();
        assertThat(result.out()).isEmpty();
        assertThat(result.err()).isEqualTo("No Java files matched.\n");
    }

    @Test
    void writeSummaryCountsIgnoredJavaFiles(@TempDir Path dir) throws IOException {
        write(dir.resolve(".gitignore"), "ignored/\n");
        write(dir.resolve("kept/Kept.java"), "class Kept{int value;}");
        write(dir.resolve("ignored/Ignored.java"), "class Ignored{int value;}");

        Result result = run(dir, null, "--write", ".");

        assertThat(result.exitCode()).isZero();
        assertThat(result.out()).isEqualTo("Processed 2 files: 1 formatted, 1 ignored.\n");
        assertThat(result.err()).isEmpty();
        assertThat(Files.readString(dir.resolve("kept/Kept.java"))).isEqualTo(
            """
                class Kept {

                    int value;
                }
                """
        );
    }

    private static Result run(Path workingDirectory, String stdin, String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        Main main = new Main(new PrintWriter(out, true), new PrintWriter(err, true), workingDirectory, stdin);

        int exitCode = Main.commandLine(main).execute(args);

        return new Result(exitCode, out.toString(), err.toString());
    }

    private static void write(Path path, String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private record Result(int exitCode, String out, String err) {}
}
