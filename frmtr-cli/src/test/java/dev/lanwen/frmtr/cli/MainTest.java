package dev.lanwen.frmtr.cli;

import static dev.lanwen.frmtr.cli.MainTestSupport.run;
import static dev.lanwen.frmtr.cli.MainTestSupport.stripAnsi;
import static dev.lanwen.frmtr.cli.MainTestSupport.write;
import static org.assertj.core.api.Assertions.assertThat;

import dev.lanwen.frmtr.FormatterException;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.cli.MainTestSupport.Result;
import dev.lanwen.frmtr.tooling.FormatFileResult;
import dev.lanwen.frmtr.tooling.FormatFileStatus;
import dev.lanwen.frmtr.tooling.FormatRunResult;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MainTest {

    @Test
    void formatsStdinToStdoutWithStdinOption() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        Main main = new Main(new PrintWriter(out, true), new PrintWriter(err, true), "class Demo{int value;}");

        int exitCode = Main.commandLine(main).execute("--stdin");

        assertThat(exitCode).isZero();
        assertThat(out.toString()).isEqualTo(
            """
                class Demo {

                    int value;
                }
                """
        );
        assertThat(err.toString()).isEmpty();
    }

    @Test
    void colorAlwaysDoesNotColorFormattedSourceOutput() {
        Result result = run(Path.of("."), "class Demo{int value;}", "--stdin", "--color", "always");

        assertThat(result.exitCode()).isZero();
        assertThat(result.out()).isEqualTo(
            """
                class Demo {

                    int value;
                }
                """
        );
        assertThat(result.out()).doesNotContain("\u001B[");
        assertThat(result.err()).isEmpty();
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
            "--check"
        );

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
                .contains("diff --git origin frmtr\n")
                .contains("--- origin\n+++ frmtr\n")
                .contains("-class Demo{int value;}\n")
                .contains(
                    """
                        +class Demo {
                        +
                        +    int value;
                        +}
                        """
                );
        assertThat(result.err()).isEmpty();
    }

    @Test
    void colorAlwaysColorsStatusAndDiffOutput() {
        Result result = run(Path.of("."), "class Demo{int value;}", "--stdin", "--diff", "--color", "always");

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.out())
                .contains("\u001B[")
                .contains("✗")
                .contains("-class Demo{int value;}")
                .contains("+class Demo {");
        assertThat(stripAnsi(result.out())).isEqualTo(plainChangedStdinDiff());
        assertThat(result.err()).isEmpty();
    }

    @Test
    void colorAlwaysColorsLineWidthBorderGray() {
        Result result = run(
            Path.of("."),
            "class Demo{int value;}",
            "--stdin",
            "--render-line-width",
            "--line-width",
            "20",
            "--color",
            "always"
        );

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.out())
                .contains("\u001B[38;5;8m⋮ 20\u001B[0m")
                .contains("\u001B[38;5;8m⋮\u001B[0m\u001B[31m;}\u001B[0m")
                .contains("\u001B[38;5;8m⋮+2\u001B[0m");
        assertThat(stripAnsi(result.out()))
                .contains("@@ -1 +1,4 @@        ⋮ 20\n")
                .contains("-class Demo{int value⋮;}\n                     ⋮+2\n")
                .contains("+class Demo {        ⋮\n");
        assertThat(result.err()).isEmpty();
    }

    @Test
    void colorAlwaysColorsCheckSummaryStats(@TempDir Path dir) throws IOException {
        write(
            dir.resolve("src/AFormatted.java"),
            """
                class AFormatted {

                    int value;
                }
                """
        );
        write(dir.resolve("src/BChanged.java"), "class BChanged{int value;}");
        write(dir.resolve("src/ZBroken.java"), "class {");

        Result result = run(dir, null, "--check", "--color", "always", "src");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.out())
                .contains("\u001B[32m1 unchanged\u001B[0m")
                .contains("\u001B[33m1 would change\u001B[0m")
                .contains("\u001B[31m1 failed\u001B[0m");
        assertThat(stripAnsi(result.out())).endsWith("Checked 3 files: 1 unchanged, 1 would change, 1 failed.\n");
    }

    @Test
    void colorAlwaysColorsFailureDiagnosticSpans(@TempDir Path dir) throws IOException {
        write(dir.resolve("src/Broken.java"), "class {");

        Result plain = run(dir, null, "--check", "--color", "never", "src");
        Result colored = run(dir, null, "--check", "--color", "always", "src");

        assertThat(colored.exitCode()).isEqualTo(2);
        assertThat(stripAnsi(colored.out())).isEqualTo(plain.out());
        assertThat(plain.out())
                .startsWith(
                    """
                        ! src/Broken.java
                        ┌─ Unable to parse Java source:
                        │ 1  class {
                        """
                )
                .endsWith("Checked 1 file: 1 failed.\n");
        assertThat(colored.out())
                .contains("\u001B[31m!\u001B[0m src/Broken.java\n")
                .contains("\u001B[38;5;8m┌─ \u001B[0m\u001B[31mUnable to parse Java source:\u001B[0m")
                .contains("\u001B[38;5;8m│ \u001B[0m\u001B[38;5;8m1\u001B[0m\u001B[38;5;8m  \u001B[0mclass {\n")
                .containsPattern("\u001B\\[31m[^\\n]*\\^\u001B\\[0m");
        assertThat(plain.out()).doesNotContain("\u001B[");
        assertThat(colored.err()).isEmpty();
    }

    @Test
    void colorNeverPreservesPlainStatusAndDiffOutput() {
        Result result = run(Path.of("."), "class Demo{int value;}", "--stdin", "--diff", "--color", "never");

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.out()).isEqualTo(plainChangedStdinDiff());
        assertThat(result.out()).doesNotContain("\u001B[");
        assertThat(result.err()).isEmpty();
    }

    @Test
    void renderLineWidthStdinPrintsDecoratedDiffAtConfiguredLineWidth() {
        Result result = run(
            Path.of("."),
            "class Demo{int value;}",
            "--stdin",
            "--render-line-width",
            "--line-width",
            "20"
        );

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.out())
                .startsWith("✗ stdin\n")
                .contains("diff --git origin frmtr\n")
                .contains("@@ -1 +1,4 @@        ⋮ 20\n")
                .contains("-class Demo{int value⋮;}\n                     ⋮+2\n")
                .contains("+class Demo {        ⋮\n")
                .doesNotContain("frmtr: line width")
                .doesNotContain("source columns:");
        assertThat(result.err()).isEmpty();
    }

    @Test
    void omittedLineWidthUsesCoreFormatterDefaultWithoutMaterializingAdapterValue() {
        StringWriter out = new StringWriter();
        Main main = new Main(
            new PrintWriter(out, true),
            new PrintWriter(new StringWriter(), true),
            "class Demo{int value;}"
        );

        int exitCode = Main.commandLine(main).execute("--stdin");

        assertThat(exitCode).isZero();
        assertThat(out.toString()).isEqualTo(
            """
                class Demo {

                    int value;
                }
                """
        );
        assertThat(main.lineWidth).isNull();
    }

    @Test
    void explicitDefaultLineWidthRemainsExplicit() {
        StringWriter out = new StringWriter();
        Main main = new Main(
            new PrintWriter(out, true),
            new PrintWriter(new StringWriter(), true),
            "class Demo{int value;}"
        );

        int exitCode = Main.commandLine(main)
                .execute("--stdin", "--line-width", Integer.toString(FormatterOptions.DEFAULT_LINE_WIDTH));

        assertThat(exitCode).isZero();
        assertThat(out.toString()).isEqualTo(
            """
                class Demo {

                    int value;
                }
                """
        );
        assertThat(main.lineWidth).isEqualTo(FormatterOptions.DEFAULT_LINE_WIDTH);
    }

    @Test
    void indentWidthFormatsStdinWithConfiguredSpaceIndentation() {
        StringWriter out = new StringWriter();
        Main main = new Main(
            new PrintWriter(out, true),
            new PrintWriter(new StringWriter(), true),
            "class Demo{int value;}"
        );

        int exitCode = Main.commandLine(main).execute("--stdin", "--indent-width", "2");

        assertThat(exitCode).isZero();
        assertThat(out.toString()).isEqualTo(
            """
                class Demo {

                  int value;
                }
                """
        );
        assertThat(main.indentWidth).isEqualTo(2);
    }

    @Test
    void diffUsesDefaultCheckWhenSelectorsAreEmpty(@TempDir Path dir) {
        write(dir.resolve("src/Main.java"), "class Main{int value;}");

        Result result = run(dir, null, "--diff");

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.out())
                .startsWith("✗ src/Main.java\n")
                .contains("diff --git origin frmtr\n");
        assertThat(result.err()).isEmpty();
    }

    @Test
    void rejectsStdinWithWriteSelectorsOrExcludes() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        Main main = new Main(new PrintWriter(out, true), new PrintWriter(err, true), "class Demo{}");

        int exitCode = Main.commandLine(main).execute("--stdin", "--exclude", "generated", "src");

        assertThat(exitCode).isEqualTo(2);
        assertThat(out.toString()).isEmpty();
        assertThat(err.toString()).isEqualTo("--stdin cannot be combined with --write, selectors, or --exclude\n");
    }

    @Test
    void rejectsVerifyWithStdinBecauseVerifyRequiresWrite() {
        Result result = run(Path.of("."), "class Demo{}", "--stdin", "--verify");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.out()).isEmpty();
        assertThat(result.err()).isEqualTo("--verify requires --write or --check\n");
    }

    @Test
    void rejectsStandaloneVerifyWithoutWriteOrCheck() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        Main main = new Main(new PrintWriter(out, true), new PrintWriter(err, true), "");

        int exitCode = Main.commandLine(main).execute("--verify", "src");

        assertThat(exitCode).isEqualTo(2);
        assertThat(out.toString()).isEmpty();
        assertThat(err.toString()).isEqualTo("--verify requires --write or --check\n");
    }

    @Test
    void rejectsStdinCheckVerifyBecauseVerifyNeedsFiles() {
        Result result = run(Path.of("."), "class Demo{}", "--stdin", "--check", "--verify");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.out()).isEmpty();
        assertThat(result.err()).isEqualTo("--verify requires --write or --check\n");
    }

    @Test
    void checkVerifyOnAlreadyFormattedFilesReturnsZeroAndWritesNothing(@TempDir Path dir) throws IOException {
        String formatted = """
                class Main {

                    int value;
                }
                """;
        write(dir.resolve("src/Main.java"), formatted);

        Result result = run(dir, null, "--check", "--verify", "src");

        assertThat(result.exitCode()).isZero();
        assertThat(result.out()).isEqualTo(
            """
                ✓ src/Main.java
                Checked 1 file: 1 unchanged.
                """
        );
        assertThat(result.err()).isEmpty();
        assertThat(Files.readString(dir.resolve("src/Main.java"))).isEqualTo(formatted);
    }

    @Test
    void checkVerifyOnMisformattedFilesReturnsOneAndLeavesFilesUnchanged(@TempDir Path dir) throws IOException {
        String original = "class Main{int value;}";
        write(dir.resolve("src/Main.java"), original);

        Result result = run(dir, null, "--check", "--verify", "src");

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.out()).isEqualTo(
            """
                ✗ src/Main.java
                Checked 1 file: 1 would change.
                """
        );
        assertThat(result.err()).isEmpty();
        // Read-only: --check --verify reports would-change but never rewrites the file.
        assertThat(Files.readString(dir.resolve("src/Main.java"))).isEqualTo(original);
    }

    @Test
    void readOnlyVerifyViolationMapsToExitThreeWithoutWriting(@TempDir Path dir) throws IOException {
        // The real formatter never emits non-AST-equivalent output, so the read-only verify violation is exercised at
        // the production decision seam: a FAILED result carrying a verify-violation FormatterException must map to
        // EXIT_VERIFY (3), distinct from an ordinary parse/IO failure which maps to EXIT_FAILED (2). The file is never
        // touched because check+verify formats in memory only.
        Path file = dir.resolve("src/Main.java");
        write(file, "class Main{int value;}");
        String before = Files.readString(file);

        FormatFileResult verifyFailure = new FormatFileResult(
            file,
            Path.of("src/Main.java"),
            FormatFileStatus.FAILED,
            "",
            FormatterException.verifyViolation("frmtr verify: formatted output is not AST-equivalent to the input — x")
        );
        FormatFileResult parseFailure = new FormatFileResult(
            file,
            Path.of("src/Other.java"),
            FormatFileStatus.FAILED,
            "",
            new FormatterException("Unable to parse Java source")
        );

        assertThat(Main.failureExit(new FormatRunResult(List.of(verifyFailure)))).isEqualTo(3);
        assertThat(Main.failureExit(new FormatRunResult(List.of(parseFailure)))).isEqualTo(2);
        // A verify violation mixed with a plain parse failure still wins (3 > 2).
        assertThat(Main.failureExit(new FormatRunResult(List.of(parseFailure, verifyFailure)))).isEqualTo(3);
        assertThat(Files.readString(file)).isEqualTo(before);
    }

    @Test
    void writeVerifyParseFailureMapsToExitTwoNotThree(@TempDir Path dir) throws IOException {
        // A parse failure under --write --verify is an ordinary failure, not a verify violation: it must stay exit 2.
        // (The exit-3 verify-violation branch shares failureExit and is covered by the read-only seam test above.)
        write(dir.resolve("src/Broken.java"), "class {");

        Result result = run(dir, null, "--write", "--verify", "src");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(Files.readString(dir.resolve("src/Broken.java"))).isEqualTo("class {");
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
    void rejectsRenderLineWidthWithoutCheck() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        Main main = new Main(new PrintWriter(out, true), new PrintWriter(err, true), "");

        int exitCode = Main.commandLine(main).execute("--render-line-width", "src");

        assertThat(exitCode).isEqualTo(2);
        assertThat(out.toString()).isEmpty();
        assertThat(err.toString()).isEqualTo("--render-line-width requires --check\n");
    }

    @Test
    void writeUsesDefaultSelectorsWhenSelectorsAreEmpty(@TempDir Path dir) throws IOException {
        write(dir.resolve("src/Main.java"), "class Main{int value;}");
        write(dir.resolve("README.md"), "# ignored\n");

        Result result = run(dir, null, "--write");

        assertThat(result.exitCode()).isZero();
        assertThat(result.out()).isEqualTo("Processed 1 file: 1 formatted.\n");
        assertThat(result.err()).isEmpty();
        assertThat(Files.readString(dir.resolve("src/Main.java"))).isEqualTo(
            """
                class Main {

                    int value;
                }
                """
        );
    }

    @Test
    void printsSingleMatchedFileToStdout(@TempDir Path dir) throws IOException {
        write(dir.resolve("src/Main.java"), "class Main{int value;}");

        Result result = run(dir, null, "src/Main.java");

        assertThat(result.exitCode()).isZero();
        assertThat(result.out()).isEqualTo(
            """
                class Main {

                    int value;
                }
                """
        );
        assertThat(result.err()).isEqualTo("Processed 1 file: 1 printed.\n");
    }

    @Test
    void printsMultipleMatchedFilesWithFilenameHeaders(@TempDir Path dir) throws IOException {
        write(dir.resolve("a/A.java"), "class A{int value;}");
        write(dir.resolve("b/B.java"), "class B{int value;}");

        Result result = run(dir, null, "a/*.java,b/*.java");

        assertThat(result.exitCode()).isZero();
        assertThat(result.out()).isEqualTo(
            """
                ==> a/A.java <==
                class A {

                    int value;
                }

                ==> b/B.java <==
                class B {

                    int value;
                }
                """
        );
        assertThat(result.err()).isEqualTo("Processed 2 files: 2 printed.\n");
    }

    @Test
    void printModeReportsFailedFilesWithGroupedFailureRenderer(@TempDir Path dir) throws IOException {
        write(dir.resolve("src/Broken.java"), "class {");

        Result result = run(dir, null, "src/Broken.java");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.out()).isEmpty();
        assertThat(result.err())
                .startsWith(
                    """
                        ┌─ Unable to parse Java source:
                        │ 1  class {
                        """
                )
                .contains("Parse error")
                .contains("│    │")
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
                """
        );
        write(dir.resolve("src/Main.java"), "class Main{int value;}");

        Result result = run(dir, null, "--check", "src");

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.out()).isEqualTo(
            """
                ✓ src/Formatted.java
                ✗ src/Main.java
                Checked 2 files: 1 unchanged, 1 would change.
                """
        );
    }

    @Test
    void checkDiffPrintsUnifiedDiffForChangedFilesOnly(@TempDir Path dir) throws IOException {
        write(
            dir.resolve("src/Formatted.java"),
            """
                class Formatted {

                    int value;
                }
                """
        );
        write(dir.resolve("src/Main.java"), "class Main{int value;}");

        Result result = run(dir, null, "--check", "--diff", "src");

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.out())
                .startsWith(
                    """
                        ✓ src/Formatted.java
                        ✗ src/Main.java
                        """
                )
                .contains("diff --git origin frmtr\n")
                .contains("--- origin\n+++ frmtr\n")
                .contains("-class Main{int value;}\n")
                .contains(
                    """
                        +class Main {
                        +
                        +    int value;
                        +}
                        """
                )
                .doesNotContain("a/src/Main.java")
                .doesNotContain("b/src/Main.java");
        int formattedIndex = result.out().indexOf("✓ src/Formatted.java\n");
        int changedIndex = result.out().indexOf("✗ src/Main.java\n");
        int diffIndex = result.out().indexOf("diff --git origin frmtr\n");
        assertThat(formattedIndex).isLessThan(changedIndex);
        assertThat(changedIndex).isLessThan(diffIndex);
    }

    @Test
    void checkDiffPrintsFailureDiagnosticsNextToFailedFiles(@TempDir Path dir) throws IOException {
        write(dir.resolve("src/AChanged.java"), "class AChanged{int value;}");
        write(dir.resolve("src/ZBroken.java"), "class {");

        Result result = run(dir, null, "--check", "--diff", "src");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.out())
                .startsWith("✗ src/AChanged.java\n")
                .contains("diff --git origin frmtr\n")
                .contains(
                    """
                        +class AChanged {
                        +
                        +    int value;
                        +}
                        """
                )
                .contains(
                    """
                        ! src/ZBroken.java
                        ┌─ Unable to parse Java source:
                        │ 1  class {
                        """
                )
                .endsWith("Checked 2 files: 1 would change, 1 failed.\n");
        int diffIndex = result.out().indexOf("diff --git origin frmtr\n");
        int failureIndex = result.out().indexOf("! src/ZBroken.java\n┌─ Unable to parse Java source:\n");
        int summaryIndex = result.out().indexOf("Checked 2 files: 1 would change, 1 failed.\n");
        assertThat(diffIndex).isLessThan(failureIndex);
        assertThat(failureIndex).isLessThan(summaryIndex);
    }

    @Test
    void checkReportsFailedFilesWithoutStacktraceByDefault(@TempDir Path dir) throws IOException {
        write(dir.resolve("src/Broken.java"), "class {");

        Result result = run(dir, null, "--check", "src");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.out())
                .startsWith(
                    """
                        ! src/Broken.java
                        ┌─ Unable to parse Java source:
                        │ 1  class {
                        """
                )
                .contains("Parse-error recovery is configured")
                .contains("Parse error")
                .contains("│    │")
                .contains("^")
                .endsWith("Checked 1 file: 1 failed.\n");
        assertThat(result.err()).isEmpty();
    }

    @Test
    void parseErrorBehaviorFailReportsStrictParseErrors(@TempDir Path dir) throws IOException {
        write(dir.resolve("src/Broken.java"), "class {");

        Result result = run(dir, null, "--check", "--parse-error-behavior", "fail", "src");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.out())
                .startsWith(
                    """
                        ! src/Broken.java
                        ┌─ Unable to parse Java source:
                        │ 1  class {
                        """
                )
                .contains("Parse error")
                .endsWith("Checked 1 file: 1 failed.\n");
        assertThat(result.err()).isEmpty();
    }

    @Test
    void writeReportsLexicalErrorsWithContextAndProcessedSummary(@TempDir Path dir) {
        write(
            dir.resolve("src/TemplateExpression.java"),
            """
                class TemplateExpression {

                  String info = STR."My name is \\{name}";
                }
                """
        );

        Result result = run(dir, null, "--write", "src");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.out()).isEqualTo("Processed 1 file: 0 formatted, 1 failed.\n");
        assertThat(result.err())
                .startsWith(
                    """
                        ┌─ Unable to parse Java source:
                        │ 1  class TemplateExpression {
                        """
                )
                .contains("│ 1  class TemplateExpression {")
                .contains("│ 3    String info = STR.\"My name is \\{name}\";")
                .contains("│    │")
                .contains("^")
                .contains("Lexical error at line 3, column 34");
    }

    @Test
    void stacktraceOptionReportsFailureStacktrace(@TempDir Path dir) throws IOException {
        write(dir.resolve("src/Broken.java"), "class {");

        Result result = run(dir, null, "--stacktrace", "--check", "src");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.out()).isEqualTo(
            """
                ! src/Broken.java
                Checked 1 file: 1 failed.
                """
        );
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
        assertThat(result.out())
                .startsWith(
                    """
                        ! src/Switch.java
                        ┌─ Unable to parse Java source:
                        │ 1  class Switch {
                        """
                )
                .contains("yield")
                .contains("│ 4              case CreateCommand cmd -> {")
                .contains("│    │")
                .contains("^")
                .endsWith("Checked 1 file: 1 failed.\n");
        assertThat(result.err()).isEmpty();
    }

    @Test
    void javaLevelAcceptsPlainVersionNumber(@TempDir Path dir) throws IOException {
        write(dir.resolve("src/Switch.java"), switchExpressionYieldSource());

        Result result = run(dir, null, "--check", "--java-level", "25", "src");

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.out()).isEqualTo(
            """
                ✗ src/Switch.java
                Checked 1 file: 1 would change.
                """
        );
        assertThat(result.err()).isEmpty();
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

    private static String plainChangedStdinDiff() {
        return """
                ✗ stdin
                diff --git origin frmtr
                --- origin
                +++ frmtr
                @@ -1 +1,4 @@
                -class Demo{int value;}
                \\ No newline at end of file
                +class Demo {
                +
                +    int value;
                +}
                """;
    }
}
