package dev.lanwen.frmtr.cli;

import static org.assertj.core.api.Assertions.assertThat;

import dev.lanwen.frmtr.FormatterOptions;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MainTest {
    private static final Pattern ANSI_ESCAPE = Pattern.compile("\\u001B\\[[;\\d]*m");

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
    void colorAlwaysDoesNotColorFormattedSourceOutput() {
        Result result = run(Path.of("."), "class Demo{int value;}", "--stdin", "--color", "always");

        assertThat(result.exitCode()).isZero();
        assertThat(result.out()).isEqualTo("""
                class Demo {

                    int value;
                }
                """);
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
                .contains("diff --git origin frmtr\n")
                .contains("--- origin\n+++ frmtr\n")
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
    void explainStdinShowsFormattedOutputWhyAndLegend() {
        Result result = run(
                Path.of("."),
                "class A{void m(){foo().bar().baz().qux().quux().corge().grault().garply().waldo().fred();}}",
                "--stdin",
                "--explain",
                "--line-width",
                "40",
                "--color",
                "never");

        assertThat(result.exitCode()).isZero();
        assertThat(result.out())
                .contains("Formatted")
                .contains(".grault()")
                .contains("Why it wrapped")
                .contains("Decision tree")
                .contains("Legend");
        // The wrap reason reads as a method chain with real width arithmetic, with no structural noise or raw statement
        // label in the "why" section (the raw label may still appear in the decision tree below).
        assertThat(whySection(result.out()))
                .contains("method chain")
                .contains("flat width")
                .contains("available")
                .doesNotContain("java.statement:ExpressionStmt")
                .doesNotContain("java.bodyDeclaration")
                .doesNotContain("laid out across lines by rule");
        assertThat(result.err()).isEmpty();
    }

    @Test
    void explainReportsRealWidthArithmeticForWrappingMethodChainThroughTheCli() {
        Result result = run(
                Path.of("."),
                "class A{void m(){var x=builder().alpha().beta().gamma().delta().epsilon().zeta().eta().theta();}}",
                "--stdin",
                "--explain",
                "--line-width",
                "40",
                "--color",
                "never");

        assertThat(result.exitCode()).isZero();
        // End-to-end: a chain that overflows a narrow width produces the width-arithmetic "why", surfaced from the
        // printer's own decision rather than reported as an opaque forced break.
        String why = whySection(result.out());
        assertThat(why)
                .contains("method chain")
                .contains("flat width")
                .contains("> 40 available")
                .contains("segments, one per line")
                .doesNotContain("not a renderer width fit")
                .doesNotContain("java.bodyDeclaration");
        assertThat(result.err()).isEmpty();
    }

    @Test
    void explainReportsWidthDrivenTernaryBreakWithArithmetic() {
        Result result = run(
                Path.of("."),
                "class A{int m(boolean c){int r=c?computeTheFirstValue():computeTheSecondAlternativeValue();return r;}}",
                "--stdin",
                "--explain",
                "--line-width",
                "40",
                "--color",
                "never");

        assertThat(result.exitCode()).isZero();
        // The ternary reports the real width it measured against the budget, named as a ternary.
        String why = whySection(result.out());
        assertThat(why).contains("ternary").contains("flat width").contains("> 40 available");
        assertThat(result.err()).isEmpty();
    }

    @Test
    void explainReportsWidthDrivenArgumentListBreakWithArithmetic() {
        Result result = run(
                Path.of("."),
                "class A{void m(){process(alphaValue,betaValue,gammaValue,deltaValue,epsilonValue,zeta);}}",
                "--stdin",
                "--explain",
                "--line-width",
                "40",
                "--color",
                "never");

        assertThat(result.exitCode()).isZero();
        String why = whySection(result.out());
        assertThat(why).contains("argument list").contains("flat width").contains("> 40 available");
        assertThat(result.err()).isEmpty();
    }

    @Test
    void explainSurfacesBothAWidthWrapAndASeparateSameTypedNonWidthWrap() {
        // The method holds a width-wrapping chain (recorded with real arithmetic) AND a separate call that wraps for a
        // trailing line comment, not width. Both are MethodCallExpr, so a label-only suppression would hide the second.
        // The chain must report width arithmetic; the comment-driven call must still appear as a rule-driven break.
        Result result = run(
                Path.of("."),
                "class A{\n void m(){\n  foo().bar().baz().qux().quux().corge().grault().garply().waldo().fred();\n"
                        + "  note(value, // keep\n   other);\n }\n}\n",
                "--stdin",
                "--explain",
                "--line-width",
                "40",
                "--color",
                "never");

        assertThat(result.exitCode()).isZero();
        String why = whySection(result.out());
        // The chain wrap keeps its real width arithmetic.
        assertThat(why).contains("flat width").contains("> 40 available").contains("segments, one per line");
        // The comment-driven call is not dropped: it surfaces as a rule-driven break with no width measurement.
        assertThat(why).contains("laid out across lines by rule");
        assertThat(result.err()).isEmpty();
    }

    /**
     * Extracts the "Why it wrapped" section so assertions about wrap reasons are not confused by the formatted source
     * (which legitimately echoes the same construct names and the original code) above it or the legend below it.
     */
    private static String whySection(String output) {
        int start = output.indexOf("Why it wrapped");
        int end = output.indexOf("Decision tree", start);
        return output.substring(start, end < 0 ? output.length() : end);
    }

    @Test
    void explainReportsNothingWrappedWhenSourceFitsOnOneLine() {
        Result result = run(Path.of("."), "class A{}", "--stdin", "--explain", "--color", "never");

        assertThat(result.exitCode()).isZero();
        assertThat(result.out()).contains("Nothing wrapped");
        assertThat(result.err()).isEmpty();
    }

    @Test
    void explainOnFileSelectorExplainsThatFile(@TempDir Path directory) throws IOException {
        Path file = directory.resolve("Demo.java");
        Files.writeString(file, "class Demo{int value;}", StandardCharsets.UTF_8);

        Result result = run(directory, null, "--explain", "Demo.java", "--color", "never");

        assertThat(result.exitCode()).isZero();
        assertThat(result.out()).contains("Formatted").contains("class Demo {").contains("Legend");
        assertThat(result.err()).isEmpty();
    }

    @Test
    void explainRejectsCombinationWithCheck() {
        Result result = run(Path.of("."), "class A{}", "--stdin", "--explain", "--check");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.err()).contains("--explain is its own mode");
        assertThat(result.out()).isEmpty();
    }

    @Test
    void explainRejectsMoreThanOneFileSelector() {
        Result result = run(Path.of("."), null, "--explain", "a.java", "b.java");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.err()).contains("--explain expects exactly one file");
        assertThat(result.out()).isEmpty();
    }

    @Test
    void explainColorAlwaysColorsTheReportButKeepsPlainTextStable() {
        Result colored = run(
                Path.of("."),
                "class A{int x = 1;}",
                "--stdin",
                "--explain",
                "--color",
                "always");
        Result plain = run(
                Path.of("."),
                "class A{int x = 1;}",
                "--stdin",
                "--explain",
                "--color",
                "never");

        assertThat(colored.out()).contains("\u001B[");
        assertThat(stripAnsi(colored.out())).isEqualTo(plain.out());
        assertThat(colored.err()).isEmpty();
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
                "always");

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
                """);
        write(dir.resolve("src/BChanged.java"), "class BChanged{int value;}");
        write(dir.resolve("src/ZBroken.java"), "class {");

        Result result = run(dir, null, "--check", "--color", "always", "src");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.out())
                .contains("\u001B[32m1 unchanged\u001B[0m")
                .contains("\u001B[33m1 would change\u001B[0m")
                .contains("\u001B[31m1 failed\u001B[0m");
        assertThat(stripAnsi(result.out())).endsWith("Checked 3 files: 1 unchanged, 1 would change, 1 failed.\n");
        assertThat(result.err()).isEmpty();
    }

    @Test
    void colorAlwaysColorsFailureDiagnosticSpans(@TempDir Path dir) throws IOException {
        write(dir.resolve("src/Broken.java"), "class {");

        Result plain = run(dir, null, "--check", "--color", "never", "src");
        Result colored = run(dir, null, "--check", "--color", "always", "src");

        assertThat(colored.exitCode()).isEqualTo(2);
        assertThat(stripAnsi(colored.out())).isEqualTo(plain.out());
        assertThat(plain.out())
                .startsWith("""
                        ! src/Broken.java
                        ┌─ Unable to parse Java source:
                        │ 1  class {
                        """)
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
        Result result = run(Path.of("."), "class Demo{int value;}", "--stdin", "--render-line-width", "--line-width", "20");

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
    void lineWidthOptionDefaultsToCoreFormatterDefault() {
        Main main = new Main(
                new PrintWriter(new StringWriter(), true),
                new PrintWriter(new StringWriter(), true),
                "class Demo{int value;}");

        int exitCode = Main.commandLine(main).execute("--stdin");

        assertThat(exitCode).isZero();
        assertThat(main.lineWidth).isEqualTo(FormatterOptions.DEFAULT_LINE_WIDTH);
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
                        ┌─ Unable to parse Java source:
                        │ 1  class {
                        """)
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
                .contains("diff --git origin frmtr\n")
                .contains("--- origin\n+++ frmtr\n")
                .contains("-class Main{int value;}\n")
                .contains("""
                        +class Main {
                        +
                        +    int value;
                        +}
                        """)
                .doesNotContain("a/src/Main.java")
                .doesNotContain("b/src/Main.java");
        int formattedIndex = result.out().indexOf("✓ src/Formatted.java\n");
        int changedIndex = result.out().indexOf("✗ src/Main.java\n");
        int diffIndex = result.out().indexOf("diff --git origin frmtr\n");
        assertThat(formattedIndex).isLessThan(changedIndex);
        assertThat(changedIndex).isLessThan(diffIndex);
        assertThat(result.err()).isEmpty();
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
                .contains("""
                        +class AChanged {
                        +
                        +    int value;
                        +}
                        """)
                .contains("""
                        ! src/ZBroken.java
                        ┌─ Unable to parse Java source:
                        │ 1  class {
                        """)
                .endsWith("Checked 2 files: 1 would change, 1 failed.\n");
        int diffIndex = result.out().indexOf("diff --git origin frmtr\n");
        int failureIndex = result.out().indexOf("! src/ZBroken.java\n┌─ Unable to parse Java source:\n");
        int summaryIndex = result.out().indexOf("Checked 2 files: 1 would change, 1 failed.\n");
        assertThat(diffIndex).isLessThan(failureIndex);
        assertThat(failureIndex).isLessThan(summaryIndex);
        assertThat(result.err()).isEmpty();
    }

    @Test
    void checkReportsFailedFilesWithoutStacktraceByDefault(@TempDir Path dir) throws IOException {
        write(dir.resolve("src/Broken.java"), "class {");

        Result result = run(dir, null, "--check", "src");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.out())
                .startsWith("""
                        ! src/Broken.java
                        ┌─ Unable to parse Java source:
                        │ 1  class {
                        """)
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
                .startsWith("""
                        ! src/Broken.java
                        ┌─ Unable to parse Java source:
                        │ 1  class {
                        """)
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
                """);

        Result result = run(dir, null, "--write", "src");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.out()).isEqualTo("Processed 1 file: 0 formatted, 1 failed.\n");
        assertThat(result.err())
                .startsWith("""
                        ┌─ Unable to parse Java source:
                        │ 1  class TemplateExpression {
                        """)
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
        assertThat(result.out())
                .startsWith("""
                        ! src/Switch.java
                        ┌─ Unable to parse Java source:
                        │ 1  class Switch {
                        """)
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
        assertThat(result.out()).isEqualTo("""
                ✗ src/Switch.java
                Checked 1 file: 1 would change.
                """);
        assertThat(result.err()).isEmpty();
    }

    @Test
    void excludesPathsAndGlobsDuringDiscovery(@TempDir Path dir) throws IOException {
        write(dir.resolve("src/Kept.java"), "class Kept{int value;}");
        write(dir.resolve("src/generated/Generated.java"), "class Generated{int value;}");
        write(dir.resolve("fixtures/Fixture.java"), "class Fixture{int value;}");

        Result result = run(dir, null, "--check", "--exclude", "src/generated, fixtures/**/*.java", ".");

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.out()).isEqualTo("""
                ✗ src/Kept.java
                Checked 1 file: 1 would change, 2 excluded.
                """);
        assertThat(result.err()).isEmpty();
    }

    @Test
    void writeSummaryCountsExcludedJavaFiles(@TempDir Path dir) throws IOException {
        write(dir.resolve("src/Kept.java"), "class Kept{int value;}");
        write(dir.resolve("src/generated/Generated.java"), "class Generated{int value;}");

        Result result = run(dir, null, "--write", "--exclude", "src/generated", ".");

        assertThat(result.exitCode()).isZero();
        assertThat(result.out()).isEqualTo("Processed 2 files: 1 formatted, 1 excluded.\n");
        assertThat(result.err()).isEmpty();
        assertThat(Files.readString(dir.resolve("src/Kept.java"))).isEqualTo("""
                class Kept {

                    int value;
                }
                """);
        assertThat(Files.readString(dir.resolve("src/generated/Generated.java"))).isEqualTo("class Generated{int value;}");
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

    @Test
    void explicitFileSelectorLoadsSameDirectoryGitignore(@TempDir Path dir) throws IOException {
        write(dir.resolve("src/.gitignore"), "Ignored.java\n");
        write(dir.resolve("src/Ignored.java"), "class Ignored{int value;}");

        Result result = run(dir, null, "--write", "src/Ignored.java");

        assertThat(result.exitCode()).isZero();
        assertThat(result.out()).isEqualTo("Processed 1 file: 0 formatted, 1 ignored.\n");
        assertThat(result.err()).isEmpty();
        assertThat(Files.readString(dir.resolve("src/Ignored.java"))).isEqualTo("class Ignored{int value;}");
    }

    @Test
    void directorySelectorLoadsParentGitignoreRules(@TempDir Path dir) throws IOException {
        write(dir.resolve(".gitignore"), "/selected/Ignored.java\n");
        write(dir.resolve("selected/Kept.java"), "class Kept{int value;}");
        write(dir.resolve("selected/Ignored.java"), "class Ignored{int value;}");

        Result result = run(dir, null, "--write", "selected");

        assertThat(result.exitCode()).isZero();
        assertThat(result.out()).isEqualTo("Processed 2 files: 1 formatted, 1 ignored.\n");
        assertThat(result.err()).isEmpty();
        assertThat(Files.readString(dir.resolve("selected/Kept.java"))).isEqualTo("""
                class Kept {

                    int value;
                }
                """);
        assertThat(Files.readString(dir.resolve("selected/Ignored.java"))).isEqualTo("class Ignored{int value;}");
    }

    @Test
    void directorySelectorLoadsNestedGitignoreRulesWithDirectoryLocalScope(@TempDir Path dir) throws IOException {
        write(dir.resolve("selected/nested/.gitignore"), """
                /*.java
                !/Keep.java
                """);
        write(dir.resolve("selected/nested/Drop.java"), "class Drop{int value;}");
        write(dir.resolve("selected/nested/Keep.java"), "class Keep{int value;}");
        write(dir.resolve("selected/nested/deep/Drop.java"), "class DeepDrop{int value;}");

        Result result = run(dir, null, "--write", "selected");

        assertThat(result.exitCode()).isZero();
        assertThat(result.out()).isEqualTo("Processed 3 files: 2 formatted, 1 ignored.\n");
        assertThat(result.err()).isEmpty();
        assertThat(Files.readString(dir.resolve("selected/nested/Drop.java"))).isEqualTo("class Drop{int value;}");
        assertThat(Files.readString(dir.resolve("selected/nested/Keep.java"))).isEqualTo("""
                class Keep {

                    int value;
                }
                """);
        assertThat(Files.readString(dir.resolve("selected/nested/deep/Drop.java"))).isEqualTo("""
                class DeepDrop {

                    int value;
                }
                """);
    }

    @Test
    void globSelectorLoadsParentAndNestedGitignoreRules(@TempDir Path dir) throws IOException {
        write(dir.resolve(".gitignore"), "src/rootIgnored/\n");
        write(dir.resolve("src/nested/.gitignore"), "/Drop.java\n");
        write(dir.resolve("src/rootIgnored/Ignored.java"), "class Ignored{int value;}");
        write(dir.resolve("src/nested/Drop.java"), "class Drop{int value;}");
        write(dir.resolve("src/nested/Keep.java"), "class Keep{int value;}");
        write(dir.resolve("src/Other.java"), "class Other{int value;}");

        Result result = run(dir, null, "--write", "src/**/*.java");

        assertThat(result.exitCode()).isZero();
        assertThat(result.out()).isEqualTo("Processed 4 files: 2 formatted, 2 ignored.\n");
        assertThat(result.err()).isEmpty();
        assertThat(Files.readString(dir.resolve("src/rootIgnored/Ignored.java"))).isEqualTo("class Ignored{int value;}");
        assertThat(Files.readString(dir.resolve("src/nested/Drop.java"))).isEqualTo("class Drop{int value;}");
        assertThat(Files.readString(dir.resolve("src/nested/Keep.java"))).isEqualTo("""
                class Keep {

                    int value;
                }
                """);
        assertThat(Files.readString(dir.resolve("src/Other.java"))).isEqualTo("""
                class Other {

                    int value;
                }
                """);
    }

    @Test
    void excludeTakesPrecedenceOverGitignore(@TempDir Path dir) throws IOException {
        write(dir.resolve(".gitignore"), "src/generated/\n");
        write(dir.resolve("src/Kept.java"), "class Kept{int value;}");
        write(dir.resolve("src/generated/Generated.java"), "class Generated{int value;}");

        Result result = run(dir, null, "--write", "--exclude", "src/generated", ".");

        assertThat(result.exitCode()).isZero();
        assertThat(result.out()).isEqualTo("Processed 2 files: 1 formatted, 1 excluded.\n");
        assertThat(result.err()).isEmpty();
        assertThat(Files.readString(dir.resolve("src/generated/Generated.java"))).isEqualTo("class Generated{int value;}");
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

    private static String stripAnsi(String value) {
        return ANSI_ESCAPE.matcher(value).replaceAll("");
    }

    private record Result(int exitCode, String out, String err) {}
}
