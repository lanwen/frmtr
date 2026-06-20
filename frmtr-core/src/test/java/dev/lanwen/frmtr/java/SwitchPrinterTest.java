package dev.lanwen.frmtr.java;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseStart;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Providers;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import dev.lanwen.frmtr.FormatterException;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.Frmtr;
import dev.lanwen.frmtr.doc.DocRenderer;
import org.junit.jupiter.api.Test;

final class SwitchPrinterTest {

    @Test
    void formatsValidSwitchStatementGroupSiblingsAroundRawRecoveredStatementGroup() {
        String source = """
                class Demo {
                    void method(int value) {
                        switch (value) {
                            case 1: before();
                            case 2: var broken = ; // keep raw
                            case 3: after();
                        }
                    }
                }
                """;
        CompilationUnit unit = recoveredParseResult(source);
        SwitchStmt statement = onlySwitchStatement(unit);
        Statement recoveredStatement = recoveredStatement(statement.getEntries().get(1));

        String formatted = Frmtr.format(source);

        assertThat(SwitchPrinter.hasRecoverableSwitchEntryListProblem(statement)).isTrue();
        assertThat(JavaFormatter.isSupportedRecovery(recoveredStatement)).isTrue();
        assertThat(formatted).isEqualTo(
            """
                class Demo {

                    void method(int value) {
                        switch (value) {
                            case 1:
                                before();
                            case 2: var broken = ; // keep raw
                            case 3:
                                after();
                        }
                    }
                }
                """
        );
    }

    @Test
    void preservesSwitchCommentsAroundSimulatedRecoveredStatementGroup() {
        String source = """
                class Demo {
                    void method(int value) {
                        /* keep before */ switch (value // selector
                        ) {
                            case 1: before();
                            case 2: broken();
                            case 3: after();
                        }
                    }
                }
                """;
        CompilationUnit unit = parse(source);
        SwitchStmt statement = onlySwitchStatement(unit);
        statement.getEntries().get(1).getStatements().get(0).setParsed(Node.Parsedness.UNPARSABLE);

        String formatted = printRecovered(unit, source);

        assertThat(SwitchPrinter.hasRecoverableSwitchEntryListProblem(statement)).isTrue();
        assertThat(formatted).isEqualTo(
            """
                class Demo {

                    void method(int value) {
                        /* keep before */ switch (
                            value // selector
                        ) {
                            case 1:
                                before();
                            case 2: broken();
                            case 3:
                                after();
                        }
                    }
                }
                """
        );
    }

    @Test
    void preservesSwitchSelectorCommentAroundRawRecoveredStatementGroup() {
        String source = """
                class Demo {
                    void method(int value) {
                        switch (value // selector
                        ) {
                            case 1: before();
                            case 2: var broken = ; // keep raw
                            case 3: after();
                        }
                    }
                }
                """;
        CompilationUnit unit = recoveredParseResult(source);
        SwitchStmt statement = onlySwitchStatement(unit);
        Statement recoveredStatement = recoveredStatement(statement.getEntries().get(1));

        String formatted = Frmtr.format(source);

        assertThat(SwitchPrinter.hasRecoverableSwitchEntryListProblem(statement)).isTrue();
        assertThat(JavaFormatter.isSupportedRecovery(recoveredStatement)).isTrue();
        assertThat(formatted).isEqualTo(
            """
                class Demo {

                    void method(int value) {
                        switch (
                            value // selector
                        ) {
                            case 1:
                                before();
                            case 2: var broken = ; // keep raw
                            case 3:
                                after();
                        }
                    }
                }
                """
        );
    }

    @Test
    void formatsValidSwitchExpressionStatementGroupSiblingsAroundRawRecoveredStatementGroup() {
        String source = """
                class Demo {
                    String method(int value) {
                        return switch (value) {
                            case 1: yield "one";
                            case 2: var broken = ; // keep raw
                            case 3: yield "three";
                            default: yield "default";
                        };
                    }
                }
                """;
        CompilationUnit unit = recoveredParseResult(source);
        SwitchExpr expression = onlySwitchExpression(unit);
        Statement recoveredStatement = recoveredStatement(expression.getEntries().get(1));

        String formatted = Frmtr.format(source);

        assertThat(SwitchPrinter.hasRecoverableSwitchEntryListProblem(expression)).isTrue();
        assertThat(JavaFormatter.isSupportedRecovery(recoveredStatement)).isTrue();
        assertThat(formatted).isEqualTo(
            """
                class Demo {

                    String method(int value) {
                        return switch (value) {
                            case 1: yield "one";
                            case 2: var broken = ; // keep raw
                            case 3: yield "three";
                            default: yield "default";
                        };
                    }
                }
                """
        );
    }

    @Test
    void formatsRecoverableSwitchEntryWhenOuterBlockAlsoContainsMalformedStatement() {
        String source = """
                class Demo {
                    void method(int value) {
                        switch (value) {
                            case 1: before();
                            case 2: var broken = ; // keep switch raw
                            case 3: after();
                        }
                        var other = ; // keep block raw
                        afterBlock();
                    }
                }
                """;
        CompilationUnit unit = recoveredParseResult(source);
        SwitchStmt statement = onlySwitchStatement(unit);

        String formatted = Frmtr.format(source);

        assertThat(SwitchPrinter.hasRecoverableSwitchEntryListProblem(statement)).isTrue();
        assertThat(formatted).isEqualTo(
            """
                class Demo {

                    void method(int value) {
                        switch (value) {
                            case 1:
                                before();
                            case 2: var broken = ; // keep switch raw
                            case 3:
                                after();
                        }
                        var other = ; // keep block raw
                        afterBlock();
                    }
                }
                """
        );
    }

    @Test
    void formatsValidRuleEntrySiblingsAroundRawRecoveredRuleBlockEntry() {
        String source = """
                class Demo {
                    void method(int value) {
                        switch (value) {
                            case 1 -> before();
                            case 2 -> {
                                var broken = ; // keep raw
                            }
                            case 3 -> after();
                        }
                    }
                }
                """;
        CompilationUnit unit = recoveredParseResult(source);
        SwitchStmt statement = onlySwitchStatement(unit);
        Statement recoveredStatement = recoveredStatement(statement.getEntries().get(1));

        String formatted = Frmtr.format(source);

        assertThat(SwitchPrinter.hasRecoverableSwitchEntryListProblem(statement)).isTrue();
        assertThat(JavaFormatter.isSupportedRecovery(recoveredStatement)).isTrue();
        assertThat(formatted).isEqualTo(
            """
                class Demo {

                    void method(int value) {
                        switch (value) {
                            case 1 -> before();
                            case 2 -> {
                                var broken = ; // keep raw
                            }
                            case 3 -> after();
                        }
                    }
                }
                """
        );
    }

    @Test
    void reportsRawCommentBoundaryFailuresAsRecoverableSwitchEntryListFailures() {
        String source = """
                class Demo {
                    void method(int value) {
                        switch (value) {
                            case 1: before();
                            case 2: broken();
                            case 3: after();
                        }
                    }
                }
                """;
        CompilationUnit unit = parse(source);
        SwitchStmt statement = onlySwitchStatement(unit);
        LineComment rangeLessComment = new LineComment("range-less");
        statement.addOrphanComment(rangeLessComment);
        statement.getEntries().get(1).getStatements().get(0).setParsed(Node.Parsedness.UNPARSABLE);

        Throwable thrown = catchThrowable(() -> printRecovered(unit, source));

        assertThat(rangeLessComment.getRange()).isEmpty();
        assertThat(thrown)
                .isInstanceOf(FormatterException.class)
                .hasMessageContaining("Unable to recover Java parse error inside switch entry list:")
                .hasMessageContaining("cannot safely account LineComment at unknown range")
                .hasCauseInstanceOf(RecoveredSourceRegions.CrossingCommentBoundaryException.class);
        assertThat(((FormatterException) thrown).internal()).isFalse();
    }

    @Test
    void rejectsMalformedSwitchSelectorInsteadOfRecoveringWholeSwitchStatementAsBlockGap() {
        String source = """
                class Demo {
                    void method() {
                        switch () {
                            case 1 -> before();
                        }
                    }
                }
                """;
        var result = parser().parse(ParseStart.COMPILATION_UNIT, Providers.provider(source));

        Throwable thrown = catchThrowable(() -> Frmtr.format(source));

        assertThat(result.isSuccessful()).isFalse();
        assertThat(result.getResult()).isPresent();
        assertThat(result.getResult().orElseThrow().findAll(SwitchStmt.class)).isEmpty();
        assertThat(
            result.getResult()
                    .orElseThrow()
                    .findAll(Statement.class)
                    .stream()
                    .filter(statement -> statement.getParsed() != Node.Parsedness.PARSED)
        )
                .singleElement()
                .satisfies(statement -> {
                    assertThat(statement.getTokenRange().orElseThrow().toString()).startsWith("()");
                    assertThat(SwitchPrinter.isCollapsedMalformedSwitchStatement(statement, new SourceText(source))).isTrue();
                });
        assertThat(thrown).isInstanceOfSatisfying(FormatterException.class, exception -> {
            assertThat(exception).hasMessage("Unable to parse Java source");
            assertThat(exception.sourceProblems())
                    .first()
                    .satisfies(problem -> assertThat(problem.message())
                                .contains("switch entry lists")
                                .contains("Unsupported recovered node: UnparsableStmt")
                    );
        });
    }

    @Test
    void rejectsMissingSwitchSelectorInsteadOfRecoveringWholeSwitchStatementAsBlockGap() {
        String source = """
                class Demo {
                    void method() {
                        switch {
                            case 1 -> before();
                        }
                    }
                }
                """;
        var result = parser().parse(ParseStart.COMPILATION_UNIT, Providers.provider(source));

        Throwable thrown = catchThrowable(() -> Frmtr.format(source));

        assertThat(result.isSuccessful()).isFalse();
        assertThat(result.getResult()).isPresent();
        CompilationUnit unit = result.getResult().orElseThrow();
        assertThat(unit.getParsed()).isEqualTo(Node.Parsedness.UNPARSABLE);
        assertThat(unit.findAll(SwitchStmt.class)).isEmpty();
        assertThat(unit.findAll(Statement.class)).isEmpty();
        assertThat(thrown).isInstanceOfSatisfying(FormatterException.class, exception -> {
            assertThat(exception).hasMessage("Unable to parse Java source");
            assertThat(exception.sourceProblems())
                    .first()
                    .satisfies(problem -> assertThat(problem.message())
                                .contains("switch entry lists")
                                .contains("Unsupported recovered node: CompilationUnit")
                    );
        });
    }

    @Test
    void rejectsCommentedEmptySwitchSelectorInsteadOfRecoveringWholeSwitchStatementAsBlockGap() {
        String source = """
                class Demo {
                    void method() {
                        switch /* selector comment */ () {
                            case 1 -> before();
                        }
                    }
                }
                """;
        var result = parser().parse(ParseStart.COMPILATION_UNIT, Providers.provider(source));

        Throwable thrown = catchThrowable(() -> Frmtr.format(source));

        assertThat(result.isSuccessful()).isFalse();
        assertThat(result.getResult()).isPresent();
        assertThat(result.getResult().orElseThrow().findAll(SwitchStmt.class)).isEmpty();
        assertThat(
            result.getResult()
                    .orElseThrow()
                    .findAll(Statement.class)
                    .stream()
                    .filter(statement -> statement.getParsed() != Node.Parsedness.PARSED)
        )
                .singleElement()
                .satisfies(statement -> {
                    assertThat(statement.getTokenRange().orElseThrow().toString()).startsWith("()");
                    assertThat(SwitchPrinter.isCollapsedMalformedSwitchStatement(statement, new SourceText(source))).isTrue();
                });
        assertThat(thrown).isInstanceOfSatisfying(FormatterException.class, exception -> {
            assertThat(exception).hasMessage("Unable to parse Java source");
            assertThat(exception.sourceProblems())
                    .first()
                    .satisfies(problem -> assertThat(problem.message())
                                .contains("switch entry lists")
                                .contains("Unsupported recovered node: UnparsableStmt")
                    );
        });
    }

    private static String printRecovered(CompilationUnit unit, String source) {
        return new DocRenderer(FormatterOptions.defaults()).render(
            new JavaPrinter(FormatterOptions.defaults(), new SourceText(source), true).print(unit)
        );
    }

    private static Statement recoveredStatement(SwitchEntry entry) {
        assertThat(entry.getParsed()).isEqualTo(Node.Parsedness.PARSED);
        return entry.findAll(Statement.class)
                .stream()
                .filter(statement -> statement.getParsed() != Node.Parsedness.PARSED)
                .findFirst()
                .orElseThrow();
    }

    private static SwitchStmt onlySwitchStatement(CompilationUnit unit) {
        return unit.findFirst(SwitchStmt.class).orElseThrow();
    }

    private static SwitchExpr onlySwitchExpression(CompilationUnit unit) {
        return unit.findFirst(SwitchExpr.class).orElseThrow();
    }

    private static CompilationUnit recoveredParseResult(String source) {
        var result = parser().parse(ParseStart.COMPILATION_UNIT, Providers.provider(source));
        assertThat(result.isSuccessful()).isFalse();
        assertThat(result.getResult()).isPresent();
        assertThat(result.getResult().orElseThrow().getParsed()).isEqualTo(Node.Parsedness.PARSED);
        return result.getResult().orElseThrow();
    }

    private static CompilationUnit parse(String source) {
        return parser()
                .parse(ParseStart.COMPILATION_UNIT, Providers.provider(source))
                .getResult()
                .orElseThrow();
    }

    private static JavaParser parser() {
        return new JavaParser(
            new ParserConfiguration()
                    .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
                    .setStoreTokens(true)
                    .setAttributeComments(true)
        );
    }
}
