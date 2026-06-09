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
    void formatsStdinToStdoutWithStdinOption() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        Main main = new Main(
                new PrintWriter(out, true),
                new PrintWriter(err, true),
                "class Demo{int value;}");

        int exitCode = Main.commandLine(main).execute("--stdin");

        assertThat(exitCode).isZero();
        assertThat(out.toString()).isEqualTo("""
                class Demo {

                    int value;
                }
                """);
        assertThat(err.toString()).isEmpty();
    }

    @Test
    void printsVersionBuildShaAndTimestamp() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        Main main = new Main(new PrintWriter(out, true), new PrintWriter(err, true), "");

        int exitCode = Main.commandLine(main).execute("--version");

        assertThat(exitCode).isZero();
        assertThat(out.toString())
                .contains("frmtr version ")
                .contains("commit ")
                .contains(BuildInfo.COMMIT_SHA)
                .contains("built ")
                .contains(BuildInfo.BUILD_TIMESTAMP);
        assertThat(err.toString()).isEmpty();
    }

    @Test
    void checkStdinReportsUnchangedSource() {
        Result result = run(
                Path.of("."),
                """
                class Demo {

                    int value;
                }
                """,
                "--stdin",
                "--check");

        assertThat(result.exitCode()).isZero();
        assertThat(result.out()).isEqualTo("✓ stdin\n");
        assertThat(result.err()).isEmpty();
    }

    @Test
    void checkStdinReportsChangedSource() {
        Result result = run(Path.of("."), "class Demo{int value;}", "--stdin", "--check");

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.out()).isEqualTo("✗ stdin\n");
        assertThat(result.err()).isEmpty();
    }

    @Test
    void diffStdinPrintsUnifiedDiff() {
        Result result = run(Path.of("."), "class Demo{int value;}", "--stdin", "--diff");

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.out())
                .startsWith("✗ stdin\n")
                .contains("diff --git a/stdin b/stdin\n")
                .contains("--- a/stdin\n+++ b/stdin\n")
                .contains("-class Demo{int value;}\n")
                .contains("""
                        +class Demo {
                        +
                        +    int value;
                        +}
                        """);
        assertThat(result.err()).isEmpty();
    }

    @Test
    void noArgsChecksJavaFilesByDefault(@TempDir Path dir) throws IOException {
        write(
                dir.resolve("Formatted.java"),
                """
                class Formatted {

                    int value;
                }
                """);
        write(dir.resolve("src/Main.java"), "class Main{int value;}");
        write(dir.resolve("README.md"), "# ignored\n");

        Result result = run(dir, "class Input{int value;}");

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.out()).isEqualTo("""
                ✓ Formatted.java
                ✗ src/Main.java
                Checked 2 files: 1 unchanged, 1 would change.
                """);
        assertThat(result.err()).isEmpty();
    }

    @Test
    void diffUsesDefaultCheckWhenSelectorsAreEmpty(@TempDir Path dir) {
        write(dir.resolve("src/Main.java"), "class Main{int value;}");

        Result result = run(dir, null, "--diff");

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.out())
                .startsWith("✗ src/Main.java\n")
                .contains("diff --git a/src/Main.java b/src/Main.java\n");
        assertThat(result.err()).isEmpty();
    }

    @Test
    void rejectsStdinWithWriteOrSelectors() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        Main main = new Main(new PrintWriter(out, true), new PrintWriter(err, true), "class Demo{}");

        int exitCode = Main.commandLine(main).execute("--stdin", "--write", "src");

        assertThat(exitCode).isEqualTo(2);
        assertThat(out.toString()).isEmpty();
        assertThat(err.toString()).isEqualTo("--stdin cannot be combined with --write or selectors\n");
    }

    @Test
    void missingExplicitJavaFileSelectorIsToolError(@TempDir Path dir) {
        Result result = run(dir, null, "--check", "--diff", "src/Missing.java");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.out()).isEmpty();
        assertThat(result.err()).isEqualTo("File selector does not exist: src/Missing.java\n");
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
    void writeUsesDefaultSelectorsWhenSelectorsAreEmpty(@TempDir Path dir) throws IOException {
        write(dir.resolve("src/Main.java"), "class Main{int value;}");
        write(dir.resolve("README.md"), "# ignored\n");

        Result result = run(dir, null, "--write");

        assertThat(result.exitCode()).isZero();
        assertThat(result.out()).isEqualTo("Processed 1 file: 1 formatted.\n");
        assertThat(result.err()).isEmpty();
        assertThat(Files.readString(dir.resolve("src/Main.java"))).isEqualTo("""
                class Main {

                    int value;
                }
                """);
    }

    @Test
    void writesCommaSeparatedGlobMatchesInPlace(@TempDir Path dir) throws IOException {
        write(dir.resolve("src/Main.java"), "class Main{int value;}");
        write(dir.resolve("examples/Example.java"), "class Example{int value;}");
        write(dir.resolve("README.md"), "# ignored\n");

        Result result = run(dir, null, "--write", "src/**/*.java, examples/*.java");

        assertThat(result.exitCode()).isZero();
        assertThat(result.out()).isEqualTo("Processed 2 files: 2 formatted.\n");
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
        assertThat(result.err()).isEqualTo("Processed 1 file: 1 printed.\n");
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
        assertThat(result.err()).isEqualTo("Processed 2 files: 2 printed.\n");
    }

    @Test
    void printModeReportsFailedFilesWithGroupedFailureRenderer(@TempDir Path dir) throws IOException {
        write(dir.resolve("src/Broken.java"), "class {");

        Result result = run(dir, null, "src/Broken.java");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.out()).isEmpty();
        assertThat(result.err())
                .startsWith("""
                        src/Broken.java
                          Unable to parse Java source:
                        """)
                .contains("Parse error")
                .contains("^")
                .endsWith("Processed 1 file: 0 printed, 1 failed.\n");
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
                Checked 2 files: 1 unchanged, 1 would change.
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
                        +
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
        assertThat(result.out()).isEqualTo("""
                ! src/Broken.java
                Checked 1 file: 1 failed.
                """);
        assertThat(result.err())
                .startsWith("""
                        src/Broken.java
                          Unable to parse Java source:
                        """)
                .contains("Parse-error recovery is configured")
                .contains("Parse error")
                .contains("^");
    }

    @Test
    void parseErrorBehaviorFailReportsStrictParseErrors(@TempDir Path dir) throws IOException {
        write(dir.resolve("src/Broken.java"), "class {");

        Result result = run(dir, null, "--check", "--parse-error-behavior", "fail", "src");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.out()).isEqualTo("""
                ! src/Broken.java
                Checked 1 file: 1 failed.
                """);
        assertThat(result.err())
                .startsWith("""
                        src/Broken.java
                          Unable to parse Java source:
                        """)
                .contains("Parse error");
    }

    @Test
    void writeReportsLexicalErrorsWithContextAndProcessedSummary(@TempDir Path dir) {
        write(
                dir.resolve("src/TemplateExpression.java"),
                """
                class TemplateExpression {

                  String info = STR."My name is \\{name}";
                }
                """);

        Result result = run(dir, null, "--write", "src");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.out()).isEqualTo("Processed 1 file: 0 formatted, 1 failed.\n");
        assertThat(result.err())
                .startsWith("""
                        src/TemplateExpression.java
                          Unable to parse Java source:
                        """)
                .contains("  1  class TemplateExpression {")
                .contains("  3    String info = STR.\"My name is \\{name}\";")
                .contains("^")
                .contains("Lexical error at line 3, column 34");
    }

    @Test
    void stacktraceOptionReportsFailureStacktrace(@TempDir Path dir) throws IOException {
        write(dir.resolve("src/Broken.java"), "class {");

        Result result = run(dir, null, "--stacktrace", "--check", "src");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.out()).isEqualTo("""
                ! src/Broken.java
                Checked 1 file: 1 failed.
                """);
        assertThat(result.err())
                .contains("src/Broken.java: Unable to parse Java source:")
                .contains("Problem stacktrace")
                .contains("dev.lanwen.frmtr.java.JavaFormatter.parse");
    }

    @Test
    void javaLevelUnsetUsesRawParserMode(@TempDir Path dir) throws IOException {
        write(dir.resolve("src/Switch.java"), switchExpressionYieldSource());

        Result result = run(dir, null, "--check", "--parse-error-behavior", "fail", "--java-level", "unset", "src");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.out()).isEqualTo("""
                ! src/Switch.java
                Checked 1 file: 1 failed.
                """);
        assertThat(result.err())
                .startsWith("""
                        src/Switch.java
                          Unable to parse Java source:
                        """)
                .contains("yield")
                .contains("^");
    }

    @Test
    void javaLevelAcceptsPlainVersionNumber(@TempDir Path dir) throws IOException {
        write(dir.resolve("src/Switch.java"), switchExpressionYieldSource());

        Result result = run(dir, null, "--check", "--java-level", "25", "src");

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.out()).isEqualTo("""
                ✗ src/Switch.java
                Checked 1 file: 1 would change.
                """);
        assertThat(result.err()).isEmpty();
    }

    @Test
    void reportsNoJavaFilesForUnknownExtensionsAndUnmatchedGlobs(@TempDir Path dir) throws IOException {
        write(dir.resolve("src/notes.txt"), "class Main{int value;}");

        Result result = run(dir, null, "src/**/*.txt,missing/**/*.java");

        assertThat(result.exitCode()).isZero();
        assertThat(result.out()).isEmpty();
        assertThat(result.err()).isEqualTo("No Java files matched.\n");
    }

    @Test
    void respectsGitignoreDuringDiscovery(@TempDir Path dir) throws IOException {
        write(dir.resolve(".gitignore"), "ignored/\n");
        write(dir.resolve("kept/Kept.java"), "class Kept{int value;}");
        write(dir.resolve("ignored/Ignored.java"), "class Ignored{int value;}");

        Result result = run(dir, null, "--check", ".");

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.out()).isEqualTo("""
                ✗ kept/Kept.java
                Checked 1 file: 1 would change.
                """);
        assertThat(result.err()).isEmpty();
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
        assertThat(Files.readString(dir.resolve("kept/Kept.java"))).isEqualTo("""
                class Kept {

                    int value;
                }
                """);
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
