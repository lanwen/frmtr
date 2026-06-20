package dev.lanwen.frmtr.cli;

import static dev.lanwen.frmtr.cli.MainTestSupport.run;
import static dev.lanwen.frmtr.cli.MainTestSupport.stripAnsi;
import static org.assertj.core.api.Assertions.assertThat;

import dev.lanwen.frmtr.cli.MainTestSupport.Result;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MainExplainTest {

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
            "never"
        );

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
            "never"
        );

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
            "never"
        );

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
            "never"
        );

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
            "never"
        );

        assertThat(result.exitCode()).isZero();
        String why = whySection(result.out());
        // The chain wrap keeps its real width arithmetic.
        assertThat(why).contains("flat width").contains("> 40 available").contains("segments, one per line");
        // The comment-driven call is not dropped: it surfaces as a rule-driven break with no width measurement.
        assertThat(why).contains("laid out across lines by rule");
        assertThat(result.err()).isEmpty();
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
    void explainRejectsVerifyBecauseVerifyRequiresWrite() {
        Result result = run(Path.of("."), "class A{}", "--stdin", "--explain", "--verify");

        assertThat(result.exitCode()).isEqualTo(2);
        assertThat(result.err()).isEqualTo("--verify requires --write\n");
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
            "always"
        );
        Result plain = run(
            Path.of("."),
            "class A{int x = 1;}",
            "--stdin",
            "--explain",
            "--color",
            "never"
        );

        assertThat(colored.out()).contains("\u001B[");
        assertThat(stripAnsi(colored.out())).isEqualTo(plain.out());
        assertThat(colored.err()).isEmpty();
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
}
