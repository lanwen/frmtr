package dev.lanwen.frmtr;

import static org.assertj.core.api.Assertions.assertThat;

import dev.lanwen.frmtr.OverWidthLines.OverWidthLine;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Verifies the shared "breakable over-width" predicate that both the fixture audit and the {@code --check --verify} CLI
 * warning path consult. The cases focus on the two decisions that make a line suspicious: it must exceed the width
 * <em>and</em> still contain a breakable construct after string/char/text-block literals and comments are masked.
 */
class OverWidthLinesTest {

    @Test
    void flagsOverWidthLineCarryingABinaryOperator() {
        String line = "int x = " + "a".repeat(20) + " + " + "b".repeat(20) + ";";

        assertThat(OverWidthLines.isSuspiciousOverWidth(line, 30)).isTrue();
    }

    @Test
    void doesNotFlagOverWidthLineWhoseOperatorLivesInsideAStringLiteral() {
        // The `+` is inside the literal, so masking removes it: no breakable construct survives.
        String line = "String s = \"" + "a + b ".repeat(20) + "\";";

        assertThat(OverWidthLines.isSuspiciousOverWidth(line, 30)).isFalse();
    }

    @Test
    void doesNotFlagOverWidthLineWhoseOperatorLivesInsideALineComment() {
        String line = "int x = 1; // " + "a + b ".repeat(30);

        assertThat(OverWidthLines.isSuspiciousOverWidth(line, 30)).isFalse();
    }

    @Test
    void doesNotFlagAtomicOverWidthStringLiteral() {
        String line = "String s = \"" + "x".repeat(200) + "\";";

        assertThat(OverWidthLines.isSuspiciousOverWidth(line, 80)).isFalse();
    }

    @Test
    void doesNotFlagBreakableLineThatFitsWithinTheWidth() {
        assertThat(OverWidthLines.isSuspiciousOverWidth("int x = a + b;", 80)).isFalse();
    }

    @Test
    void carriesTextBlockMaskingAcrossLinesSoInteriorOperatorsAreIgnored() {
        // The over-width line sits inside an open text block; its `+` is literal content, and a mid-text-block line
        // cannot be broken further, so the scanner must not flag it.
        String document = String.join(
            "\n",
            "String s = \"\"\"",
            "    " + "a + b ".repeat(40),
            "    \"\"\";",
            ""
        );

        List<OverWidthLine> findings = OverWidthLines.scan(document, 30);

        assertThat(findings).isEmpty();
    }

    @Test
    void reportsLineNumberAndMeasuredWidthForEachFinding() {
        String overWidth = "        return aaaaaaaaaaaa + bbbbbbbbbbbb + cccccccccccc + dddddddddddd + eeeeeeeeeeee;";
        String document = String.join("\n", "class C {", overWidth, "}", "");

        List<OverWidthLine> findings = OverWidthLines.scan(document, 40);

        assertThat(findings).singleElement().satisfies(finding -> {
            assertThat(finding.lineNumber()).isEqualTo(2);
            assertThat(finding.width()).isEqualTo(overWidth.length());
            assertThat(finding.lineWidth()).isEqualTo(40);
            assertThat(finding.line()).isEqualTo(overWidth);
        });
    }

    @Test
    void suppressesBreakableOverWidthLineInsideFormatterOffRange() {
        // The formatter emits this line verbatim from source inside @formatter:off, so warning that it could be broken
        // would contradict the opt-out.
        String document = String.join(
            "\n",
            "// @formatter:off",
            "service.first().second().third().fourth().fifth().sixth().seventh();",
            "// @formatter:on",
            ""
        );

        assertThat(OverWidthLines.scan(document, 30)).isEmpty();
    }

    @Test
    void flagsTheSameBreakableOverWidthLineOutsideAnyPragmaRange() {
        String overWidth = "service.first().second().third().fourth().fifth().sixth().seventh();";
        String inRange = String.join("\n", "// @formatter:off", overWidth, "// @formatter:on", "");
        String outOfRange = String.join("\n", "// @formatter:off", "// @formatter:on", overWidth, "");

        assertThat(OverWidthLines.scan(inRange, 30)).isEmpty();
        assertThat(OverWidthLines.scan(outOfRange, 30))
                .singleElement()
                .satisfies(finding -> assertThat(finding.line()).isEqualTo(overWidth));
    }

    @Test
    void suppressesBreakableOverWidthLineInsideFrmtrIgnoreRange() {
        String document = String.join(
            "\n",
            "// frmtr-ignore-start",
            "service.first().second().third().fourth().fifth().sixth().seventh();",
            "// frmtr-ignore-end",
            "service.first().second().third().fourth().fifth().sixth().seventh();",
            ""
        );

        // The line inside the range is suppressed; the line after frmtr-ignore-end is flagged again.
        assertThat(OverWidthLines.scan(document, 30))
                .singleElement()
                .satisfies(finding -> assertThat(finding.lineNumber()).isEqualTo(4));
    }

    @Test
    void singleFrmtrIgnoreSuppressesOnlyItsOwnMarkedLine() {
        // A bare frmtr-ignore suppresses only the line that carries it; the next line is flagged normally.
        String document = String.join(
            "\n",
            "service.first().second().third().fourth().fifth(); // frmtr-ignore",
            "service.first().second().third().fourth().fifth().sixth().seventh();",
            ""
        );

        assertThat(OverWidthLines.scan(document, 30))
                .singleElement()
                .satisfies(finding -> assertThat(finding.lineNumber()).isEqualTo(2));
    }

    @Test
    void pragmaTokenInsideAStringLiteralDoesNotSuppressFollowingLine() {
        // The marker is masked away as literal content, so it must not toggle the formatter-off range.
        String document = String.join(
            "\n",
            "String token = \"@formatter:off frmtr-ignore-start\";",
            "service.first().second().third().fourth().fifth().sixth().seventh();",
            ""
        );

        assertThat(OverWidthLines.scan(document, 30))
                .singleElement()
                .satisfies(finding -> assertThat(finding.lineNumber()).isEqualTo(2));
    }
}
