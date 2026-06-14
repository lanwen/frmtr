package dev.lanwen.frmtr.java;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseStart;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Providers;
import com.github.javaparser.ast.CompilationUnit;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.Frmtr;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

/**
 * Exercises the AST-equivalence comparator (roadmap B3, layer 1) and the {@code dev.lanwen.frmtr.debug.verify} toggle.
 *
 * <p>The point of this layer is the negative direction: it must catch a program-meaning change the golden fixtures would
 * miss. These tests therefore assert both that pure reformatting (including the deliberate import reorder) verifies
 * clean and that genuine semantic divergences — a dropped member, a renamed member, a dropped or duplicated import — are
 * reported as differences.
 */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
final class AstEquivalenceTest {

    @Test
    void treatsPureReformattingAsEquivalent() {
        CompilationUnit dense = parse("class Demo{int value;void run(){int x=1;}}");
        CompilationUnit spaced = parse("""
                class Demo {
                    int value;

                    void run() {
                        int x = 1;
                    }
                }
                """);

        assertThat(AstEquivalence.equivalent(dense, spaced)).isTrue();
    }

    @Test
    void ignoresComments() {
        CompilationUnit withComments = parse("""
                // type comment
                class Demo {
                    int value; // trailing
                }
                """);
        CompilationUnit withoutComments = parse("""
                class Demo {
                    int value;
                }
                """);

        assertThat(AstEquivalence.equivalent(withComments, withoutComments)).isTrue();
    }

    @Test
    void reportsDroppedMemberAsDifference() {
        CompilationUnit complete = parse("""
                class Demo {
                    int kept;
                    int dropped;
                }
                """);
        CompilationUnit missingMember = parse("""
                class Demo {
                    int kept;
                }
                """);

        Optional<String> difference = AstEquivalence.describeDifference(complete, missingMember);

        assertThat(difference).isPresent();
        assertThat(difference.orElseThrow()).contains("not AST-equivalent");
        assertThat(AstEquivalence.equivalent(complete, missingMember)).isFalse();
    }

    @Test
    void reportsRenamedMemberAsDifference() {
        CompilationUnit original = parse("class Demo { void run() {} }");
        CompilationUnit renamed = parse("class Demo { void walk() {} }");

        Optional<String> difference = AstEquivalence.describeDifference(original, renamed);

        assertThat(difference).isPresent();
        assertThat(difference.orElseThrow()).contains("run").contains("walk");
    }

    @Test
    void reportsDroppedEnumConstantAsDifference() {
        CompilationUnit allConstants = parse("enum Color { RED, GREEN, BLUE }");
        CompilationUnit fewerConstants = parse("enum Color { RED, BLUE }");

        Optional<String> difference = AstEquivalence.describeDifference(allConstants, fewerConstants);

        assertThat(difference).isPresent();
        assertThat(difference.orElseThrow()).contains("GREEN");
    }

    @Test
    void treatsImportReorderAsEquivalent() {
        CompilationUnit sourceOrder = parse("""
                import b.Beta;
                import a.Alpha;
                import static x.Util.help;

                class Demo {}
                """);
        CompilationUnit formatterOrder = parse("""
                import static x.Util.help;

                import a.Alpha;
                import b.Beta;

                class Demo {}
                """);

        assertThat(AstEquivalence.equivalent(sourceOrder, formatterOrder)).isTrue();
    }

    @Test
    void reportsDroppedImportAsDifference() {
        CompilationUnit complete = parse("""
                import a.Alpha;
                import b.Beta;

                class Demo {}
                """);
        CompilationUnit missingImport = parse("""
                import a.Alpha;

                class Demo {}
                """);

        Optional<String> difference = AstEquivalence.describeDifference(complete, missingImport);

        assertThat(difference).isPresent();
        assertThat(difference.orElseThrow())
                .contains("import dropped")
                .contains("b.Beta");
    }

    @Test
    void reportsDuplicatedImportAsDifference() {
        CompilationUnit single = parse("""
                import a.Alpha;

                class Demo {}
                """);
        CompilationUnit duplicated = parse("""
                import a.Alpha;
                import a.Alpha;

                class Demo {}
                """);

        Optional<String> difference = AstEquivalence.describeDifference(single, duplicated);

        assertThat(difference).isPresent();
        assertThat(difference.orElseThrow())
                .contains("import added or duplicated")
                .contains("a.Alpha");
    }

    @Test
    void treatsModifierReorderAsEquivalent() {
        CompilationUnit oneOrder = parse("abstract sealed class Parent permits Child {}\nfinal class Child extends Parent {}");
        CompilationUnit otherOrder = parse("sealed abstract class Parent permits Child {}\nfinal class Child extends Parent {}");

        assertThat(AstEquivalence.equivalent(oneOrder, otherOrder)).isTrue();
    }

    @Test
    void reportsChangedModifierAsDifference() {
        CompilationUnit publicField = parse("class Demo { public int value; }");
        CompilationUnit privateField = parse("class Demo { private int value; }");

        assertThat(AstEquivalence.equivalent(publicField, privateField)).isFalse();
    }

    @Test
    void treatsClarifyingParenthesesAsEquivalent() {
        // The formatter adds precedence-preserving parentheses to mixed-precedence expressions; that must verify clean.
        CompilationUnit bare = parse("class Demo { boolean v(boolean a, boolean b, boolean c) { return a && b || c; } }");
        CompilationUnit clarified = parse("class Demo { boolean v(boolean a, boolean b, boolean c) { return (a && b) || c; } }");

        assertThat(AstEquivalence.equivalent(bare, clarified)).isTrue();
    }

    @Test
    void respectsOperatorPrecedence() {
        // Stripping parentheses must NOT collapse expressions whose precedence genuinely differs.
        CompilationUnit grouped = parse("class Demo { int v = (1 + 2) * 3; }");
        CompilationUnit ungrouped = parse("class Demo { int v = 1 + 2 * 3; }");

        assertThat(AstEquivalence.equivalent(grouped, ungrouped)).isFalse();
    }

    @Test
    void distinguishesLiteralLexemes() {
        CompilationUnit hex = parse("class Demo { double value = 0x1p-1; }");
        CompilationUnit decimal = parse("class Demo { double value = 0.5; }");

        assertThat(AstEquivalence.equivalent(hex, decimal)).isFalse();
    }

    @Test
    void treatsReindentedTextBlockAsEquivalent() {
        // The formatter re-indents text blocks to match surrounding code. Re-indentation changes only incidental
        // leading whitespace (which the JLS strips), so it must verify clean rather than read as a value change.
        CompilationUnit shallowIndent = parse("""
                class Demo {
                    String s = \"""
                        line one
                        line two
                        \""";
                }
                """);
        CompilationUnit deeperIndent = parse("""
                class Demo {
                    String s = \"""
                              line one
                              line two
                              \""";
                }
                """);

        assertThat(AstEquivalence.equivalent(shallowIndent, deeperIndent)).isTrue();
    }

    @Test
    void reportsTextBlockContentChangeAsDifference() {
        // The formatter is value-preserving for text blocks (TextBlockPrinter renders the source verbatim), so layer 1
        // compares text-block content by its JLS String value. A real change to that value — including one confined to
        // significant interior whitespace — must be reported as a difference.
        CompilationUnit original = parse("""
                class Demo {
                    String s = \"""
                        hello world
                        \""";
                }
                """);
        CompilationUnit contentChanged = parse("""
                class Demo {
                    String s = \"""
                        goodbye    cruel  world
                        \""";
                }
                """);

        assertThat(AstEquivalence.equivalent(original, contentChanged)).isFalse();
    }

    @Test
    void reportsTextBlockReplacedByNonTextBlockAsDifference() {
        // Scoping out text-block *content* must not blind layer 1 to the *presence* of a text block: replacing it with a
        // different expression is a genuine structural change and must still be reported.
        CompilationUnit textBlock = parse("""
                class Demo {
                    String s = \"""
                        hello
                        \""";
                }
                """);
        CompilationUnit ordinaryString = parse("class Demo { String s = \"hello\"; }");

        assertThat(AstEquivalence.equivalent(textBlock, ordinaryString)).isFalse();
    }

    @Test
    void verifyTogglePassesForNormalFormatting() {
        withVerify("true", () -> assertThatCode(() -> Frmtr.format("""
                        import b.Beta;
                        import a.Alpha;

                        enum Color { RED, GREEN, BLUE }
                        """))
                .doesNotThrowAnyException());
    }

    @Test
    void verifyToggleRejectsCorruptedOutput() {
        withVerify("true", () -> {
            CompilationUnit input = parse("enum Color { RED, GREEN, BLUE }");
            CompilationUnit corruptedOutput = parse("enum Color { RED, BLUE }");

            assertThatThrownBy(() -> FormatterGuardrails.assertAstEquivalent(input, corruptedOutput))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("AST-equivalence verify failed")
                    .hasMessageContaining("GREEN");
        });
    }

    @Test
    void verifyToggleIsNoOpWhenDisabled() {
        withVerify(null, () -> {
            CompilationUnit input = parse("enum Color { RED, GREEN, BLUE }");
            CompilationUnit corruptedOutput = parse("enum Color { RED, BLUE }");

            assertThatCode(() -> FormatterGuardrails.assertAstEquivalent(input, corruptedOutput))
                    .doesNotThrowAnyException();
        });
    }

    @Test
    void recoveredInputDoesNotFalseFailUnderVerify() {
        withVerify("true", () -> {
            FormatterOptions recover = FormatterOptions.defaults()
                    .withParseErrorBehavior(FormatterOptions.ParseErrorBehavior.RECOVER);

            // A malformed member initializer is a supported recovery slice: the formatter round-trips a best-effort
            // tree, so AST-equivalence is ill-defined and verification must be skipped rather than false-failing.
            String recoveredSource = """
                    class RecoveryMembers {
                        int before = 1;
                        {
                            var broken = ; // keep raw
                        }
                        int after = 2;
                    }
                    """;

            assertThatCode(() -> Frmtr.format(recoveredSource, recover)).doesNotThrowAnyException();
        });
    }

    private static void withVerify(String value, Runnable action) {
        String previous = System.getProperty(FormatterGuardrails.VERIFY_PROPERTY);
        try {
            if (value == null) {
                System.clearProperty(FormatterGuardrails.VERIFY_PROPERTY);
            } else {
                System.setProperty(FormatterGuardrails.VERIFY_PROPERTY, value);
            }
            action.run();
        } finally {
            if (previous == null) {
                System.clearProperty(FormatterGuardrails.VERIFY_PROPERTY);
            } else {
                System.setProperty(FormatterGuardrails.VERIFY_PROPERTY, previous);
            }
        }
    }

    private static CompilationUnit parse(String source) {
        JavaParser parser = new JavaParser(new ParserConfiguration()
                .setStoreTokens(true)
                .setAttributeComments(true));
        return parser.parse(ParseStart.COMPILATION_UNIT, Providers.provider(source))
                .getResult()
                .orElseThrow();
    }
}
