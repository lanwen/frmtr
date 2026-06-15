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

final class MainProgressTest {

    private static final String CLEAR_PREVIOUS_LINE = "\u001B[1A\u001B[2K";

    @Test
    void autoProgressChecksDefaultSelectorsWhenConsoleIsPresent(@TempDir Path dir) throws IOException {
        write(
            dir.resolve("Formatted.java"),
            """
                class Formatted {

                    int value;
                }
                """
        );
        write(dir.resolve("src/Main.java"), "class Main{int value;}");
        write(dir.resolve("README.md"), "# ignored\n");

        Result result = runWithConsole(dir);

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.out()).isEqualTo(
            """
                ✓ Formatted.java
                ✗ src/Main.java
                Checked 2 files: 1 unchanged, 1 would change.
                """
        );
        assertThat(result.err()).startsWith(
            "Discovering Java files...\n"
                + CLEAR_PREVIOUS_LINE
                + "Processed [0/2 files, 0 would change, 0 failed].\n"
        );
        assertThat(result.out()).doesNotContain("Processed [");
    }

    @Test
    void progressAlwaysForcesProgressForCapturedDiffRuns(@TempDir Path dir) throws IOException {
        write(
            dir.resolve("src/Formatted.java"),
            """
                class Formatted {

                    int value;
                }
                """
        );
        write(dir.resolve("src/Main.java"), "class Main{int value;}");

        Result result = run(
            dir,
            false,
            "--check",
            "--diff",
            "--render-line-width",
            "--progress",
            "always",
            "src"
        );

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.out())
                .startsWith(
                    """
                        ✓ src/Formatted.java
                        ✗ src/Main.java
                        """
                )
                .contains("diff --git origin frmtr\n")
                .contains("@@ -1 +1,4 @@");
        assertThat(result.err()).startsWith(
            "Discovering Java files...\n"
                + CLEAR_PREVIOUS_LINE
                + "Processed [0/2 files, 0 would change, 0 failed].\n"
        );
        assertThat(result.out()).doesNotContain("Processed [");
    }

    @Test
    void progressNeverSuppressesTerminalProgress(@TempDir Path dir) throws IOException {
        write(dir.resolve("src/Main.java"), "class Main{int value;}");
        write(dir.resolve("src/Other.java"), "class Other{int value;}");

        Result result = run(dir, true, "--check", "--progress", "never", "src");

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.out()).isEqualTo(
            """
                ✗ src/Main.java
                ✗ src/Other.java
                Checked 2 files: 2 would change.
                """
        );
        assertThat(result.err()).isEmpty();
    }

    @Test
    void autoProgressWritesCommaSeparatedGlobMatchesWhenConsoleIsPresent(@TempDir Path dir) throws IOException {
        write(dir.resolve("src/Main.java"), "class Main{int value;}");
        write(dir.resolve("examples/Example.java"), "class Example{int value;}");
        write(dir.resolve("README.md"), "# ignored\n");

        Result result = runWithConsole(dir, "--write", "src/**/*.java, examples/*.java");

        assertThat(result.exitCode()).isZero();
        assertThat(result.out()).isEqualTo("Processed 2 files: 2 formatted.\n");
        assertThat(result.err()).startsWith(
            "Discovering Java files...\n"
                + CLEAR_PREVIOUS_LINE
                + "Processed [0/2 files, 0 formatted, 0 failed].\n"
        );
        assertThat(result.out()).doesNotContain("Processed [");
        assertThat(Files.readString(dir.resolve("src/Main.java"))).isEqualTo(
            """
                class Main {

                    int value;
                }
                """
        );
        assertThat(Files.readString(dir.resolve("examples/Example.java"))).isEqualTo(
            """
                class Example {

                    int value;
                }
                """
        );
    }

    private static Result runWithConsole(Path workingDirectory, String... args) {
        return run(workingDirectory, true, args);
    }

    private static Result run(Path workingDirectory, boolean consolePresent, String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        Main main = new Main(
            new PrintWriter(out, true),
            new PrintWriter(err, true),
            workingDirectory,
            null,
            consolePresent
        );

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
