package dev.lanwen.frmtr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class SuspiciousLineWidthAuditTest {

    private static final String OUTPUT_RESOURCE = "format/audit-test/frmtr-default.output.java";
    private static final FormatFixture FIXTURE = new FormatFixture(
        "audit-test @ default",
        "",
        "",
        FormatterOptions.defaults().withLineWidth(40),
        OUTPUT_RESOURCE
    );

    @Test
    void ignoresTrailingCommentWidthWhenCodeFits() {
        String formatted = "        call(); // alpha && beta ? gamma : delta, epsilon, zeta, eta, theta\n";

        assertClean(formatted);
    }

    @Test
    void ignoresLiteralContentWidthWhenCodeFits() {
        String formatted = "        String value = \"alpha && beta ? gamma : delta, epsilon, zeta, eta, theta\";\n";

        assertClean(formatted);
    }

    @Test
    void ignoresTextBlockContents() {
        String formatted = """
                class Demo {
                    void method() {
                        String value = ""\"
                            alpha && beta ? gamma : delta, epsilon, zeta, eta, theta
                            ""\";
                    }
                }
                """;

        assertClean(formatted);
    }

    @Test
    void escapedTripleQuotesInsideTextBlockDoNotHideFollowingCode() {
        String formatted = String.join(
                "\n",
                "class Demo {",
                "    void method() {",
                "        String value = \"\"\"",
                "            escaped \\\"\"\" content",
                "            \"\"\";",
                "        service.first().second().third().fourth().fifth();",
                "    }",
                "}",
                ""
        );

        assertThatThrownBy(() -> SuspiciousLineWidthAudit.assertNoUnexpectedFindings(
                FIXTURE,
                formatted,
                SuspiciousLineWidthAudit.Allowlist.empty()
            ))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(OUTPUT_RESOURCE + ":6")
                .hasMessageContaining("service.first().second()");
    }

    @Test
    void auditsBreakableSuffixAfterClosingTextBlockDelimiter() {
        String formatted = "        String value = \"\"\"text\"\"\".formatted(alpha, beta, gamma, delta, epsilon);\n";

        assertThatThrownBy(() -> SuspiciousLineWidthAudit.assertNoUnexpectedFindings(
                FIXTURE,
                formatted,
                SuspiciousLineWidthAudit.Allowlist.empty()
            ))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(OUTPUT_RESOURCE + ":1")
                .hasMessageContaining(".formatted(alpha, beta");
    }

    @Test
    void formatterPragmaTokenInsideStringLiteralDoesNotDisableAudit() {
        String formatted = """
                String token = "@formatter:off frmtr-ignore-start";
                service.first().second().third().fourth().fifth();
                """;

        assertThatThrownBy(() -> SuspiciousLineWidthAudit.assertNoUnexpectedFindings(
                FIXTURE,
                formatted,
                SuspiciousLineWidthAudit.Allowlist.empty()
            ))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(OUTPUT_RESOURCE + ":2")
                .hasMessageContaining("service.first().second()");
    }

    @Test
    void formatterPragmaTokenInsideTextBlockDoesNotDisableAudit() {
        String formatted = """
                String token = ""\"
                    @formatter:off frmtr-ignore-start
                    ""\";
                service.first().second().third().fourth().fifth();
                """;

        assertThatThrownBy(() -> SuspiciousLineWidthAudit.assertNoUnexpectedFindings(
                FIXTURE,
                formatted,
                SuspiciousLineWidthAudit.Allowlist.empty()
            ))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(OUTPUT_RESOURCE + ":4")
                .hasMessageContaining("service.first().second()");
    }

    @Test
    void ignoresFormatterOffRange() {
        String formatted = """
                // @formatter:off
                service.first().second().third().fourth().fifth();
                // @formatter:on
                """;

        assertClean(formatted);
    }

    @Test
    void ignoresFormatterOffRangeOnlyFromCommentPragma() {
        String formatted = """
                // @formatter:off
                service.first().second().third().fourth().fifth();
                // @formatter:on
                service.first().second().third().fourth().fifth();
                """;

        assertThatThrownBy(() -> SuspiciousLineWidthAudit.assertNoUnexpectedFindings(
                FIXTURE,
                formatted,
                SuspiciousLineWidthAudit.Allowlist.empty()
            ))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(OUTPUT_RESOURCE + ":4")
                .hasMessageContaining("service.first().second()");
    }

    @Test
    void ignoresFrmtrIgnoreRangeOnlyFromCommentPragma() {
        String formatted = """
                // frmtr-ignore-start
                service.first().second().third().fourth().fifth();
                // frmtr-ignore-end
                service.first().second().third().fourth().fifth();
                """;

        assertThatThrownBy(() -> SuspiciousLineWidthAudit.assertNoUnexpectedFindings(
                FIXTURE,
                formatted,
                SuspiciousLineWidthAudit.Allowlist.empty()
            ))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(OUTPUT_RESOURCE + ":4")
                .hasMessageContaining("service.first().second()");
    }

    @Test
    void singleFrmtrIgnorePragmaDoesNotSuppressFollowingOutput() {
        String formatted = """
                // frmtr-ignore
                service.first().second().third().fourth().fifth();
                """;

        assertThatThrownBy(() -> SuspiciousLineWidthAudit.assertNoUnexpectedFindings(
                FIXTURE,
                formatted,
                SuspiciousLineWidthAudit.Allowlist.empty()
            ))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(OUTPUT_RESOURCE + ":2")
                .hasMessageContaining("service.first().second()");
    }

    @Test
    void rejectsMalformedAllowlistRows() {
        assertThatThrownBy(() -> SuspiciousLineWidthAudit.Allowlist.parse(List.of("not-enough-columns")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid suspicious line-width allowlist entry");
    }

    @Test
    void rejectsAllowlistRowsWithInvalidLineNumbers() {
        assertThatThrownBy(() -> SuspiciousLineWidthAudit.Allowlist.parse(List.of(
                OUTPUT_RESOURCE + "\tnot-a-number\t" + "0".repeat(64) + "\treason"
            )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid suspicious line-width allowlist line number");
    }

    @Test
    void rejectsAllowlistRowsWithInvalidHashes() {
        assertThatThrownBy(() -> SuspiciousLineWidthAudit.Allowlist.parse(List.of(
            OUTPUT_RESOURCE + "\t1\tnot-a-sha256\treason"
        )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid suspicious line-width allowlist SHA-256");
    }

    @Test
    void expandsAllowlistLineRanges() {
        String first = "service.first().second().third().fourth().fifth();";
        String second = "service.alpha().beta().gamma().delta().epsilon();";
        SuspiciousLineWidthAudit.Allowlist allowlist = SuspiciousLineWidthAudit.Allowlist.parse(List.of(
            OUTPUT_RESOURCE
                + "\t1-2\t"
                + SuspiciousLineWidthAudit.hash(first)
                + ","
                + SuspiciousLineWidthAudit.hash(second)
                + "\tpaired approval"
        ));

        assertThatCode(() -> SuspiciousLineWidthAudit.assertNoUnexpectedFindings(
                FIXTURE,
                first + "\n" + second + "\n",
                allowlist
            ))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsAllowlistRowsWithRangeHashCountMismatch() {
        assertThatThrownBy(() -> SuspiciousLineWidthAudit.Allowlist.parse(List.of(
                OUTPUT_RESOURCE + "\t1-2\t" + "0".repeat(64) + "\treason"
            )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid suspicious line-width allowlist hash count");
    }

    @Test
    void rejectsAllowlistRowsWithDescendingLineRanges() {
        assertThatThrownBy(() -> SuspiciousLineWidthAudit.Allowlist.parse(List.of(
                OUTPUT_RESOURCE + "\t2-1\t" + "0".repeat(64) + "\treason"
            )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Invalid suspicious line-width allowlist line range");
    }

    @Test
    void rejectsStaleAllowlistEntriesForCurrentOutputResource() {
        SuspiciousLineWidthAudit.Allowlist allowlist = SuspiciousLineWidthAudit.Allowlist.parse(List.of(
            OUTPUT_RESOURCE + "\t1\t" + "0".repeat(64) + "\tobsolete approval"
        ));

        assertThatThrownBy(() -> SuspiciousLineWidthAudit.assertNoUnexpectedFindings(FIXTURE, "class Demo {}\n", allowlist))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("stale suspicious line-width allowlist entry")
                .hasMessageContaining("obsolete approval");
    }

    @Test
    void detectsAllowlistEntriesForUnknownOutputResources() {
        SuspiciousLineWidthAudit.Allowlist allowlist = SuspiciousLineWidthAudit.Allowlist.parse(List.of(
            "format/missing/frmtr-default.output.java\t1\t" + "0".repeat(64) + "\tobsolete fixture"
        ));

        assertThat(SuspiciousLineWidthAudit.unknownOutputResources(allowlist, Set.of(OUTPUT_RESOURCE)))
                .containsExactly("format/missing/frmtr-default.output.java");
    }

    @Test
    void allowlistOnlyReferencesDiscoveredFormatterOutputs() {
        List<String> discoveredOutputs = ResourceFixtureSource.Provider.outputResources("format/**/input.java");

        assertThat(SuspiciousLineWidthAudit.unknownOutputResources(
                SuspiciousLineWidthAudit.Allowlist.load(),
                discoveredOutputs
            ))
                .as("unknown suspicious line-width allowlist output resources")
                .isEmpty();
    }

    private static void assertClean(String formatted) {
        assertThatCode(() -> SuspiciousLineWidthAudit.assertNoUnexpectedFindings(
                FIXTURE,
                formatted,
                SuspiciousLineWidthAudit.Allowlist.empty()
            ))
                .doesNotThrowAnyException();
    }
}
