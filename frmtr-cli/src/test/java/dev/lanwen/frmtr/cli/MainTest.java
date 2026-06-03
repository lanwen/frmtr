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

final class MainTest {
    @Test
    void formatsStdinToStdout() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        Main main = new Main(
                new PrintWriter(out, true),
                new PrintWriter(err, true),
                "class Demo{int value;}");

        int exitCode = Main.commandLine(main).execute();

        assertThat(exitCode).isZero();
        assertThat(out.toString()).isEqualTo("""
                class Demo {
                    int value;
                }
                """);
        assertThat(err.toString()).isEmpty();
    }

    @Test
    void rejectsCheckAndWriteTogether() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        Main main = new Main(new PrintWriter(out, true), new PrintWriter(err, true), "");

        int exitCode = Main.commandLine(main).execute("--check", "--write", "src");

        assertThat(exitCode).isEqualTo(2);
        assertThat(err.toString()).isEqualTo("--check and --write cannot be used together\n");
    }

    @Test
    void rejectsDiffWithoutCheck() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        Main main = new Main(new PrintWriter(out, true), new PrintWriter(err, true), "");

        int exitCode = Main.commandLine(main).execute("--diff", "src");

        assertThat(exitCode).isEqualTo(2);
        assertThat(out.toString()).isEmpty();
        assertThat(err.toString()).isEqualTo("--diff requires --check\n");
    }

    @Test
    void rejectsFileOrientedOptionsWithoutSelectors() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        Main main = new Main(new PrintWriter(out, true), new PrintWriter(err, true), "");

        int exitCode = Main.commandLine(main).execute("--check", "--diff");

        assertThat(exitCode).isEqualTo(2);
        assertThat(out.toString()).isEmpty();
        assertThat(err.toString()).isEqualTo("file-oriented options require at least one file or directory\n");
    }

    @Test
    void writesCommaSeparatedGlobMatchesInPlace(@TempDir Path dir) throws IOException {
        write(dir.resolve("src/Main.java"), "class Main{int value;}");
        write(dir.resolve("examples/Example.java"), "class Example{int value;}");
        write(dir.resolve("README.md"), "# ignored\n");

        Result result = run(dir, null, "--write", "src/**/*.java, examples/*.java");

        assertThat(result.exitCode()).isZero();
        assertThat(result.out()).isEmpty();
        assertThat(result.err()).isEmpty();
        assertThat(Files.readString(dir.resolve("src/Main.java"))).isEqualTo("""
                class Main {
                    int value;
                }
                """);
        assertThat(Files.readString(dir.resolve("examples/Example.java"))).isEqualTo("""
                class Example {
                    int value;
                }
                """);
    }

    @Test
    void printsSingleMatchedFileToStdout(@TempDir Path dir) throws IOException {
        write(dir.resolve("src/Main.java"), "class Main{int value;}");

        Result result = run(dir, null, "src/Main.java");

        assertThat(result.exitCode()).isZero();
        assertThat(result.out()).isEqualTo("""
                class Main {
                    int value;
                }
                """);
        assertThat(result.err()).isEmpty();
    }

    @Test
    void printsMultipleMatchedFilesWithFilenameHeaders(@TempDir Path dir) throws IOException {
        write(dir.resolve("a/A.java"), "class A{int value;}");
        write(dir.resolve("b/B.java"), "class B{int value;}");

        Result result = run(dir, null, "a/*.java,b/*.java");

        assertThat(result.exitCode()).isZero();
        assertThat(result.out()).isEqualTo("""
                ==> a/A.java <==
                class A {
                    int value;
                }

                ==> b/B.java <==
                class B {
                    int value;
                }
                """);
        assertThat(result.err()).isEmpty();
    }

    @Test
    void checkReportsPassedAndChangedFilesAndReturnsOne(@TempDir Path dir) throws IOException {
        write(
                dir.resolve("src/Formatted.java"),
                """
                class Formatted {
                    int value;
                }
                """);
        write(dir.resolve("src/Main.java"), "class Main{int value;}");

        Result result = run(dir, null, "--check", "src");

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.out()).isEqualTo("""
                ✓ src/Formatted.java
                ✗ src/Main.java
                """);
        assertThat(result.err()).isEmpty();
    }

    @Test
    void checkDiffPrintsUnifiedDiffForChangedFilesOnly(@TempDir Path dir) throws IOException {
        write(
                dir.resolve("src/Formatted.java"),
                """
                class Formatted {
                    int value;
                }
                """);
        write(dir.resolve("src/Main.java"), "class Main{int value;}");

        Result result = run(dir, null, "--check", "--diff", "src");

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.out())
                .startsWith("""
                        ✓ src/Formatted.java
                        ✗ src/Main.java
                        """)
                .contains("diff --git a/src/Main.java b/src/Main.java\n")
                .contains("--- a/src/Main.java\n+++ b/src/Main.java\n")
                .contains("-class Main{int value;}\n")
                .contains("""
                        +class Main {
                        +    int value;
                        +}
                        """)
                .doesNotContain("diff --git a/src/Formatted.java b/src/Formatted.java");
        assertThat(result.err()).isEmpty();
    }

    @Test
    void checkReportsFailedFilesWithoutStacktraceByDefault(@TempDir Path dir) throws IOException {
        write(dir.resolve("src/Broken.java"), "class {");

        Result result = run(dir, null, "--check", "src");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.out()).isEqualTo("! src/Broken.java\n");
        assertThat(result.err())
                .contains("src/Broken.java: Unable to parse Java source:")
                .contains("Parse error")
                .contains("^")
                .doesNotContain("Problem stacktrace")
                .doesNotContain("JavaFormatter.parse");
    }

    @Test
    void stacktraceOptionReportsFailureStacktrace(@TempDir Path dir) throws IOException {
        write(dir.resolve("src/Broken.java"), "class {");

        Result result = run(dir, null, "--stacktrace", "--check", "src");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.out()).isEqualTo("! src/Broken.java\n");
        assertThat(result.err())
                .contains("src/Broken.java: Unable to parse Java source:")
                .contains("Problem stacktrace")
                .contains("dev.lanwen.frmtr.java.JavaFormatter.parse");
    }

    @Test
    void javaLevelUnsetUsesRawParserMode(@TempDir Path dir) throws IOException {
        write(dir.resolve("src/Switch.java"), switchExpressionYieldSource());

        Result result = run(dir, null, "--check", "--java-level", "unset", "src");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.out()).isEqualTo("! src/Switch.java\n");
        assertThat(result.err())
                .contains("src/Switch.java: Unable to parse Java source:")
                .contains("yield")
                .contains("^");
    }

    @Test
    void javaLevelAcceptsPlainVersionNumber(@TempDir Path dir) throws IOException {
        write(dir.resolve("src/Switch.java"), switchExpressionYieldSource());

        Result result = run(dir, null, "--check", "--java-level", "25", "src");

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.out()).isEqualTo("✗ src/Switch.java\n");
        assertThat(result.err()).isEmpty();
    }

    @Test
    void skipsUnknownExtensionsAndUnmatchedSelectorsSilently(@TempDir Path dir) throws IOException {
        write(dir.resolve("src/notes.txt"), "class Main{int value;}");

        Result result = run(dir, null, "src/**/*.txt,missing/**/*.java");

        assertThat(result.exitCode()).isZero();
        assertThat(result.out()).isEmpty();
        assertThat(result.err()).isEmpty();
    }

    @Test
    void respectsGitignoreDuringDiscovery(@TempDir Path dir) throws IOException {
        write(dir.resolve(".gitignore"), "ignored/\n");
        write(dir.resolve("kept/Kept.java"), "class Kept{int value;}");
        write(dir.resolve("ignored/Ignored.java"), "class Ignored{int value;}");

        Result result = run(dir, null, "--check", ".");

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.out()).isEqualTo("✗ kept/Kept.java\n");
        assertThat(result.err()).isEmpty();
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

    private static String switchExpressionYieldSource() {
        return """
                class Switch {
                    Object map(Command command) {
                        return switch (command) {
                            case CreateCommand cmd -> {
                                yield new Created(cmd.id());
                            }
                            case DeleteCommand cmd -> new Deleted(cmd.id());
                        };
                    }
                }""";
    }

    private record Result(int exitCode, String out, String err) {}
}
