package dev.lanwen.frmtr.java;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseStart;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Providers;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.comments.BlockComment;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.stmt.Statement;
import dev.lanwen.frmtr.FormatterException;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.Frmtr;
import dev.lanwen.frmtr.doc.DocRenderer;
import org.junit.jupiter.api.Test;

final class EnumDeclarationPrinterTest {
    @Test
    void formatsValidEnumConstantSiblingsAroundRawRecoveredEnumConstantGap() {
        String source = """
                enum Demo {
                    BEFORE,
                    BROKEN {
                        void m() {
                            var x = ; // keep raw
                        }
                    },
                    AFTER;

                    int value;
                }
                """;
        CompilationUnit unit = recoveredParseResult(source);
        EnumDeclaration declaration = onlyEnum(unit);
        Statement recoveredStatement = recoveredStatement(enumConstant(declaration, "BROKEN"));

        String formatted = Frmtr.format(source);

        assertThat(EnumDeclarationPrinter.hasRecoverableEnumConstantListProblem(declaration)).isTrue();
        assertThat(JavaFormatter.isSupportedRecovery(recoveredStatement)).isTrue();
        assertThat(formatted).isEqualTo("""
                enum Demo {
                    BEFORE,
                    BROKEN {
                        void m() {
                            var x = ; // keep raw
                        }
                    },
                    AFTER;

                    int value;
                }
                """);
    }

    @Test
    void keepsTrailingBlockCommentBeforeRecoveredRawGapInsideRawSource() {
        String source = """
                enum Demo {
                    BEFORE /* raw gap owns this */,
                    BROKEN {
                        void m() {
                            int x = 1;
                        }
                    },
                    AFTER;

                    int value;
                }
                """;
        CompilationUnit unit = parse(source);
        EnumDeclaration declaration = onlyEnum(unit);
        enumConstant(declaration, "BROKEN").findAll(Statement.class).getFirst().setParsed(Node.Parsedness.UNPARSABLE);

        String formatted = printRecovered(unit, source);

        assertThat(enumConstant(declaration, "BROKEN").getComment())
                .hasValueSatisfying(comment -> assertThat(comment).isInstanceOf(BlockComment.class));
        assertThat(formatted)
                .isEqualTo(source)
                .containsOnlyOnce("/* raw gap owns this */");
    }

    @Test
    void rejectsMalformedEnumConstantArgumentsWhenJavaParserCollapsesCompilationUnit() {
        String source = """
                enum Demo {
                    BEFORE,
                    BROKEN(, "x"),
                    AFTER;
                }
                """;
        var result = parser().parse(ParseStart.COMPILATION_UNIT, Providers.provider(source));

        Throwable thrown = catchThrowable(() -> Frmtr.format(source));

        assertThat(result.isSuccessful()).isFalse();
        assertThat(result.getResult()).isPresent();
        assertThat(result.getResult().orElseThrow().getParsed()).isEqualTo(Node.Parsedness.UNPARSABLE);
        assertThat(result.getResult().orElseThrow().findAll(EnumDeclaration.class)).isEmpty();
        assertThat(thrown)
                .isInstanceOf(FormatterException.class)
                .hasMessageContaining("Unable to parse Java source:")
                .hasMessageContaining("enum constant lists")
                .hasMessageContaining("Unsupported recovered node: CompilationUnit");
    }

    @Test
    void reportsRawCommentBoundaryFailuresAsRecoverableEnumConstantListFailures() {
        String source = """
                enum Demo {
                    BEFORE,
                    BROKEN,
                    AFTER;
                }
                """;
        CompilationUnit unit = parse(source);
        EnumDeclaration declaration = onlyEnum(unit);
        LineComment rangeLessComment = new LineComment("range-less");
        declaration.addOrphanComment(rangeLessComment);
        enumConstant(declaration, "BROKEN").setParsed(Node.Parsedness.UNPARSABLE);

        Throwable thrown = catchThrowable(() -> printRecovered(unit, source));

        assertThat(rangeLessComment.getRange()).isEmpty();
        assertThat(thrown)
                .isInstanceOf(FormatterException.class)
                .hasMessageContaining("Unable to recover Java parse error inside enum constant list:")
                .hasMessageContaining("cannot safely account LineComment at unknown range")
                .hasCauseInstanceOf(RecoveredSourceRegions.CrossingCommentBoundaryException.class);
        assertThat(((FormatterException) thrown).internal()).isFalse();
    }

    private static String printRecovered(CompilationUnit unit, String source) {
        return new DocRenderer(FormatterOptions.defaults())
                .render(new JavaPrinter(FormatterOptions.defaults(), new SourceText(source), true).print(unit));
    }

    private static Statement recoveredStatement(EnumConstantDeclaration declaration) {
        assertThat(declaration.getParsed()).isEqualTo(Node.Parsedness.PARSED);
        return declaration.findAll(Statement.class).stream()
                .filter(statement -> statement.getParsed() != Node.Parsedness.PARSED)
                .findFirst()
                .orElseThrow();
    }

    private static EnumConstantDeclaration enumConstant(EnumDeclaration declaration, String name) {
        return declaration.getEntries().stream()
                .filter(entry -> entry.getNameAsString().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static EnumDeclaration onlyEnum(CompilationUnit unit) {
        return unit.findFirst(EnumDeclaration.class).orElseThrow();
    }

    private static CompilationUnit recoveredParseResult(String source) {
        var result = parser().parse(ParseStart.COMPILATION_UNIT, Providers.provider(source));
        assertThat(result.isSuccessful()).isFalse();
        assertThat(result.getResult()).isPresent();
        assertThat(result.getResult().orElseThrow().getParsed()).isEqualTo(Node.Parsedness.PARSED);
        return result.getResult().orElseThrow();
    }

    private static CompilationUnit parse(String source) {
        return parser().parse(ParseStart.COMPILATION_UNIT, Providers.provider(source))
                .getResult()
                .orElseThrow();
    }

    private static JavaParser parser() {
        return new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
                .setStoreTokens(true)
                .setAttributeComments(true));
    }
}
