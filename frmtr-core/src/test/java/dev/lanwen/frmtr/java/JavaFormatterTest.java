package dev.lanwen.frmtr.java;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseStart;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Providers;
import com.github.javaparser.ast.CompilationUnit;
import dev.lanwen.frmtr.FormatterException;
import dev.lanwen.frmtr.FormatterOptions;
import org.junit.jupiter.api.Test;

/**
 * Exercises the {@code --verify} safety valve's decision seam on {@link JavaFormatter}.
 *
 * <p>The real formatter never produces non-equivalent output on its own, so the negative path — the refusal — can only
 * be driven by feeding {@link JavaFormatter#assertOutputEquivalentOrThrow} a deliberately divergent input tree. These
 * tests prove that the seam reports such a divergence as a <em>non-internal</em> {@link FormatterException} (a clean
 * refusal, not an internal bug) and that the happy path stays silent, including for a recovered input that
 * {@code formatVerified} must skip rather than false-fail.
 */
final class JavaFormatterTest {

    @Test
    void verifySeamThrowsNonInternalFormatterExceptionOnDivergentTree() {
        JavaFormatter formatter = new JavaFormatter(FormatterOptions.defaults());
        // The formatter's own output for a full enum parses cleanly; the mismatch is forced by comparing it against a
        // deliberately different input tree (an enum missing one constant), modelling AstEquivalenceTest's divergent
        // pairs. This isolates the refusal logic from the formatter, which would not diverge on its own.
        String formatted = formatter.format("enum Color { RED, GREEN, BLUE }");
        CompilationUnit divergentInput = parse("enum Color { RED, BLUE }");

        FormatterException exception = catchThrowableOfType(
            FormatterException.class,
            () -> formatter.assertOutputEquivalentOrThrow(divergentInput, formatted)
        );

        assertThat(exception).isNotNull();
        assertThat(exception.internal()).isFalse();
        assertThat(exception.verifyViolation()).isTrue();
        assertThat(exception.getMessage())
                .contains("frmtr verify")
                .contains("not AST-equivalent")
                .contains("GREEN");
    }

    @Test
    void verifySeamMarksNonParsingOutputAsVerifyViolation() {
        JavaFormatter formatter = new JavaFormatter(FormatterOptions.defaults());
        // Feed the seam output that cannot re-parse under the input's configuration: the refusal must be reported as a
        // verify violation (a formatter bug), distinct from an ordinary parse failure of the input.
        CompilationUnit input = parse("class Demo {}");

        FormatterException exception = catchThrowableOfType(
            FormatterException.class,
            () -> formatter.assertOutputEquivalentOrThrow(input, "class Demo {")
        );

        assertThat(exception).isNotNull();
        assertThat(exception.internal()).isFalse();
        assertThat(exception.verifyViolation()).isTrue();
        assertThat(exception.getMessage()).contains("frmtr verify").contains("did not parse");
    }

    @Test
    void ordinaryParseFailureIsNotMarkedAsVerifyViolation() {
        JavaFormatter formatter = new JavaFormatter(
            FormatterOptions.defaults().withParseErrorBehavior(FormatterOptions.ParseErrorBehavior.FAIL)
        );

        FormatterException exception = catchThrowableOfType(
            FormatterException.class,
            () -> formatter.format("class {")
        );

        assertThat(exception).isNotNull();
        assertThat(exception.verifyViolation()).isFalse();
    }

    @Test
    void verifySeamAcceptsEquivalentOutput() {
        JavaFormatter formatter = new JavaFormatter(FormatterOptions.defaults());
        String source = "enum Color { RED, GREEN, BLUE }";
        String formatted = formatter.format(source);

        assertThatCode(() -> formatter.assertOutputEquivalentOrThrow(parse(source), formatted))
                .doesNotThrowAnyException();
    }

    @Test
    void formatVerifiedReturnsSameOutputAsFormatForCleanInput() {
        JavaFormatter formatter = new JavaFormatter(FormatterOptions.defaults());
        for (String source : new String[] { "class Demo{void call(){target.first().second().third();}}", "record Point(int x, int y) {}", "enum Color { RED, GREEN, BLUE }", "// header\nclass Commented {\n int value; // trailing\n}\n", }) {
            assertThat(formatter.formatVerified(source)).isEqualTo(formatter.format(source));
        }
    }

    @Test
    void formatVerifiedSkipsVerificationForRecoveredInput() {
        FormatterOptions recover = FormatterOptions.defaults()
                .withParseErrorBehavior(FormatterOptions.ParseErrorBehavior.RECOVER);
        JavaFormatter formatter = new JavaFormatter(recover);
        // A malformed member initializer is a supported recovery slice: the formatter round-trips a best-effort tree,
        // so AST-equivalence is ill-defined. formatVerified must return the recovered output without false-failing.
        String recoveredSource = """
                class RecoveryMembers {
                    int before = 1;
                    {
                        var broken = ; // keep raw
                    }
                    int after = 2;
                }
                """;

        assertThatCode(() -> formatter.formatVerified(recoveredSource)).doesNotThrowAnyException();
        assertThat(formatter.formatVerified(recoveredSource)).isEqualTo(formatter.format(recoveredSource));
    }

    private static CompilationUnit parse(String source) {
        JavaParser parser = new JavaParser(
            new ParserConfiguration()
                    .setStoreTokens(true)
                    .setAttributeComments(true)
        );
        return parser.parse(ParseStart.COMPILATION_UNIT, Providers.provider(source))
                .getResult()
                .orElseThrow();
    }
}
