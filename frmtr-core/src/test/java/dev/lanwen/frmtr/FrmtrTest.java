package dev.lanwen.frmtr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseStart;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Providers;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;

import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;

final class FrmtrTest {

    @ParameterizedTest(name = "{0}")
    @ResourceFixtureSource(glob = "format/**/input.java")
    void formatsDiscoveredFixtureAndIsIdempotent(FormatFixture fixture) throws Exception {
        String formatted = Frmtr.format(fixture.source(), fixture.options());

        assertThat(formatted).isEqualTo(fixture.expected());
        assertThat(Frmtr.format(formatted, fixture.options())).isEqualTo(formatted);
        if (latestJavaParses(fixture.expected())) {
            assertThatCode(() -> assertLatestJavaParses(formatted)).doesNotThrowAnyException();
        }
    }

    @ParameterizedTest(name = "{0}")
    @ResourceFixtureSource(glob = "unsupported/**/input.java")
    void unsupportedFixturesFailWithExpectedFormatterError(UnsupportedFixture fixture) throws Exception {
        FormatterException exception = formatterException(fixture.source());

        assertThat(fixture.expectedError()).isNotEmpty();
        assertThat(exception).hasMessage(fixture.expectedError().getFirst());
        assertThat(exception.sourceProblems()).isNotEmpty();
        fixture.expectedError()
                .stream()
                .skip(1)
                .filter(line -> !line.isBlank())
                .forEach(expectedProblem -> assertThat(exception.sourceProblems())
                            .extracting(FormatterException.SourceProblem::message)
                            .anySatisfy(message -> assertThat(message).contains(expectedProblem))
                );
    }

    @Test
    void debugsFormatterDocTreeForJavaSource() {
        String rendered = Frmtr.debugDoc("class Demo{int value;}");

        assertThat(rendered)
                .contains("Group")
                .contains("Concat")
                .contains("Indent")
                .contains("Text(\"class \")")
                .contains("Text(\"Demo\")")
                .contains("Text(\"int \")")
                .contains("Text(\"value\")");
    }

    @Test
    void debugDocIncludesFormatterRuleLabels() {
        String rendered = Frmtr.debugDoc("class Demo{void method(){call(value);}}");

        assertThat(rendered)
                .contains("Label(\"java.compilationUnit\")")
                .contains("Label(\"java.bodyDeclaration:ClassOrInterfaceDeclaration\")")
                .contains("Label(\"java.bodyDeclaration:MethodDeclaration\")")
                .contains("Label(\"java.statement:ExpressionStmt\")")
                .contains("Label(\"java.expression:MethodCallExpr\")");
    }

    @Test
    void debugDocUsesFormatterOptionsThatAffectPrinterShape() {
        String source = """
                class Demo {
                    void method() {
                        call(value -> firstVeryLongConditionName && secondVeryLongConditionName);
                    }
                }
                """;
        FormatterOptions end = new FormatterOptions(
            40,
            FormatterOptions.IndentStyle.SPACE,
            2,
            FormatterOptions.LineEnding.LF,
            true,
            false,
            false,
            FormatterOptions.LambdaArrowParens.PRESERVE,
            FormatterOptions.BinaryOperatorPosition.END,
            FormatterOptions.ParseErrorBehavior.RECOVER,
            FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE
        );
        FormatterOptions start = new FormatterOptions(
            40,
            FormatterOptions.IndentStyle.SPACE,
            2,
            FormatterOptions.LineEnding.LF,
            true,
            false,
            false,
            FormatterOptions.LambdaArrowParens.PRESERVE,
            FormatterOptions.BinaryOperatorPosition.START,
            FormatterOptions.ParseErrorBehavior.RECOVER,
            FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE
        );

        assertThat(Frmtr.debugDoc(source, end)).contains("Text(\" &&\")");
        assertThat(Frmtr.debugDoc(source, start)).contains("Text(\"&& \")");
    }

    @Test
    void debugDocBuildsTreeWhenFormatWouldSkipForMissingPragma() {
        FormatterOptions requirePragma = TestFormatterOptions.withPragmaRequirement(
            80,
            FormatterOptions.IndentStyle.SPACE,
            2,
            FormatterOptions.LineEnding.LF,
            true,
            false,
            true,
            FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE
        );

        String source = "class Demo{int value;}";

        assertThat(Frmtr.format(source, requirePragma)).isEqualTo(source);
        assertThat(Frmtr.debugDoc(source, requirePragma)).contains("Text(\"class \")");
    }

    @Test
    void explainFormattedOutputMatchesFormat() {
        String source = "class Demo{void method(){call(value);}}";

        ExplainResult result = Frmtr.explain(source);

        assertThat(result.formatted()).isEqualTo(Frmtr.format(source));
    }

    @Test
    void explainReportsRealWidthArithmeticForWrappingMethodChain() {
        String source = "class A{void m(){foo().bar().baz().qux().quux().corge().grault().garply().waldo().fred();}}";
        FormatterOptions narrow = FormatterOptions.defaults().withLineWidth(40);

        ExplainResult result = Frmtr.explain(source, narrow);

        // The chain wraps onto one segment per line, so the formatted output spans many lines.
        assertThat(result.formatted().lines().filter(line -> line.contains(".")).count()).isGreaterThan(5);
        // The printer that broke the chain recorded its own width decision: the measured flat width, the budget it blew,
        // and how many segments the broken chain produced. This is the real "why it wrapped", not an opaque forced break.
        assertThat(result.explanation().printerWraps()).anySatisfy(wrap -> {
            assertThat(wrap.construct()).isEqualTo("method chain");
            assertThat(wrap.label()).isEqualTo("java.expression:MethodCallExpr");
            assertThat(wrap.available()).isEqualTo(40);
            assertThat(wrap.flatWidth()).isGreaterThan(40);
            assertThat(wrap.segments()).isGreaterThanOrEqualTo(5);
            assertThat(wrap.preview()).startsWith("foo().bar()");
        });
    }

    @Test
    void explainDoesNotAttributeACommentDrivenChainBreakToWidth() {
        // A trailing line comment after the receiver forces the chain across lines even though its flat form
        // (foo().bar().baz()) is far narrower than the budget. The recorder must refuse to call this a width wrap:
        // the chain still breaks (a forced break is present) but no PrinterWrap is recorded, so explain reports it as
        // "laid out across lines by rule" rather than inventing width arithmetic for a break width did not cause.
        FormatterOptions wide = FormatterOptions.defaults().withLineWidth(120);
        String source = "class A{\n void m(){\n  foo() // note\n   .bar()\n   .baz();\n }\n}\n";

        ExplainResult result = Frmtr.explain(source, wide);

        // The chain genuinely wrapped: the formatted output spans the selectors across lines.
        assertThat(result.formatted().lines().filter(line -> line.contains(".")).count()).isGreaterThanOrEqualTo(2);
        // The receiver method call carries the forced break, so something visibly wrapped here.
        assertThat(result.explanation().forcedBreaks()).anySatisfy(
            forced -> assertThat(forced.label()).contains("java.expression:MethodCallExpr")
        );
        // But the printer refused to attribute it to width: no width wrap was recorded for the chain.
        assertThat(result.explanation().printerWraps()).isEmpty();
    }

    @Test
    void explainDoesNotAttributeASourceMultilineChainBreakToWidth() {
        // A chain the developer already split across source lines stays split even at a wide budget, so width is not
        // the cause of the break. The recorder must not manufacture a width wrap for it.
        FormatterOptions wide = FormatterOptions.defaults().withLineWidth(120);
        String source = "class A{\n void m(){\n  foo()\n   .bar()\n   .baz();\n }\n}\n";

        ExplainResult result = Frmtr.explain(source, wide);

        assertThat(result.formatted().lines().filter(line -> line.contains(".")).count()).isGreaterThanOrEqualTo(2);
        assertThat(result.explanation().printerWraps()).isEmpty();
    }

    @Test
    void explainKeepsFormattedOutputIdenticalWhetherOrNotItIsExplained() {
        // Recording printer width decisions must be a pure observer: explain's formatted output must byte-for-byte match
        // plain format for a wrapping chain, an overflowing argument list, and a wide ternary alike.
        FormatterOptions narrow = FormatterOptions.defaults().withLineWidth(40);
        String chain = "class A{void m(){foo().bar().baz().qux().quux().corge().grault().garply().waldo().fred();}}";
        String arguments = "class A{void m(){process(alphaValue,betaValue,gammaValue,deltaValue,epsilonValue,zeta);}}";
        String ternary =
            "class A{int m(boolean c){int r=c?computeTheFirstValue():computeTheSecondAlternativeValue();return r;}}";
        for (String source : new String[] {chain, arguments, ternary}) {
            assertThat(Frmtr.explain(source, narrow).formatted()).isEqualTo(Frmtr.format(source, narrow));
        }
    }

    @Test
    void explainReportsNoWrapWhenEverythingFits() {
        ExplainResult result = Frmtr.explain("class Demo{int value;}");

        assertThat(result.explanation().brokenGroups()).isEmpty();
    }

    @Test
    void explainBuildsExplanationWhenFormatWouldSkipForMissingPragma() {
        FormatterOptions requirePragma = TestFormatterOptions.withPragmaRequirement(
            80,
            FormatterOptions.IndentStyle.SPACE,
            2,
            FormatterOptions.LineEnding.LF,
            true,
            false,
            true,
            FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE
        );
        String source = "class Demo{void method(){call(value);}}";

        ExplainResult result = Frmtr.explain(source, requirePragma);

        assertThat(result.explanation().decisions()).isNotEmpty();
    }

    @Test
    void debugDocRejectsInvalidJavaLikeFormat() {
        assertThatThrownBy(() -> Frmtr.debugDoc("class {")).isInstanceOf(FormatterException.class);
    }

    @Test
    void internalFormatterFailuresKeepCauseAndExplainTheFailure() {
        NoSuchFieldError cause = new NoSuchFieldError("variables");

        FormatterException exception = FormatterException.internal(cause);

        assertThat(exception)
                .hasMessage(
                    "Internal formatter error. This is a bug in frmtr or one of its parser dependencies: "
                        + "NoSuchFieldError: variables"
                )
                .hasCause(cause);
        assertThat(exception.internal()).isTrue();
    }

    @Test
    void defaultsToLatestAvailableJavaLanguageLevel() {
        assertThat(FormatterOptions.defaults().lineWidth()).isEqualTo(FormatterOptions.DEFAULT_LINE_WIDTH);
        assertThat(FormatterOptions.defaults().javaLanguageLevel()).isEqualTo(
            FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE
        );
        assertThat(FormatterOptions.defaults().preserveRawTrailingWhitespace()).isFalse();
        assertThat(FormatterOptions.defaults().requirePragma()).isFalse();
        assertThat(FormatterOptions.defaults().lambdaArrowParens()).isEqualTo(
            FormatterOptions.LambdaArrowParens.PRESERVE
        );
        assertThat(FormatterOptions.defaults().binaryOperatorPosition()).isEqualTo(
            FormatterOptions.BinaryOperatorPosition.START
        );
    }

    @Test
    void parseErrorsIncludeSourceContext() {
        String source = """
                class Demo {
                    void method() {
                        var something =
                    }
                }""";

        FormatterException exception = formatterException(source, failOnParseErrorsOptions());

        assertThat(exception).hasMessage("Unable to parse Java source");
        assertThat(exception.sourceProblems())
                .singleElement()
                .satisfies(problem -> {
                    assertThat(problem.message()).contains("(line 3,col 23) Parse error");
                    assertThat(problem.location()).hasValue(new FormatterException.SourceLocation(3, 23));
                    assertThat(problem.enclosingUnitLine()).hasValue(
                        new FormatterException.SourceLine(2, 1, "    void method() {")
                    );
                    assertThat(problem.contextLines())
                            .extracting(FormatterException.SourceLine::lineNumber)
                            .containsExactly(1, 2, 3, 4, 5);
                    assertThat(problem.contextLines())
                            .contains(new FormatterException.SourceLine(2, 1, "    void method() {"))
                            .contains(new FormatterException.SourceLine(3, 1, "        var something ="));
                });
    }

    @Test
    void lexicalParseErrorsIncludeSourceContextFromMessagePosition() throws Exception {
        String source = Files.readString(
            resourceRoot("unsupported/string-template-preview/input.java"),
            StandardCharsets.UTF_8
        );

        FormatterException exception = formatterException(source, failOnParseErrorsOptions());

        assertThat(exception).hasMessage("Unable to parse Java source");
        assertThat(exception.sourceProblems())
                .first()
                .satisfies(problem -> {
                    assertThat(problem.message()).contains("Lexical error at line 3, column 34");
                    assertThat(problem.location()).hasValue(new FormatterException.SourceLocation(3, 34));
                    assertThat(problem.enclosingUnitLine()).hasValue(
                        new FormatterException.SourceLine(1, 1, "class TemplateExpression {")
                    );
                    assertThat(problem.contextLines())
                            .contains(new FormatterException.SourceLine(1, 1, "class TemplateExpression {"))
                            .contains(
                                new FormatterException.SourceLine(3, 1, "  String info = STR.\"My name is \\{name}\";")
                            );
                });
    }

    @Test
    void parseErrorsSeparateMultipleProblems() {
        String source = """
                class Demo {
                    void first() {
                        var one =
                    }
                    void second() {
                        var two =
                    }
                }""";

        FormatterException exception = formatterException(source, failOnParseErrorsOptions());

        assertThat(exception).hasMessage("Unable to parse Java source");
        assertThat(exception.sourceProblems())
                .hasSize(2)
                .anySatisfy(problem -> assertThat(problem.message()).contains("(line 3,col 17) Parse error"))
                .anySatisfy(problem -> assertThat(problem.message()).contains("(line 6,col 17) Parse error"));
    }

    @Test
    void parseErrorContextKeepsFiveLinesAroundPosition() {
        String source = """
                class Demo {
                    void method() {
                        int before1 = 1;
                        int before2 = 2;
                        int before3 = 3;
                        int before4 = 4;
                        int before5 = 5;
                        var value =
                        int after1 = 1;
                        int after2 = 2;
                        int after3 = 3;
                        int after4 = 4;
                        int after5 = 5;
                        int after6 = 6;
                    }
                }""";

        FormatterException exception = formatterException(source, failOnParseErrorsOptions());

        assertThat(exception.sourceProblems())
                .first()
                .satisfies(problem -> assertThat(problem.contextLines())
                            .extracting(FormatterException.SourceLine::lineNumber)
                            .containsExactly(3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13)
                );
        assertThat(exception.sourceProblems())
                .first()
                .satisfies(problem -> assertThat(problem.enclosingUnitLine()).hasValue(
                        new FormatterException.SourceLine(2, 1, "    void method() {")
                ));
    }

    @Test
    void parseErrorContextCropsLongLinesAroundPosition() {
        String linePrefix = "        int value = 1;";
        String source = "class Demo {\n"
            + "    void method() {\n"
            + linePrefix
            + " ".repeat(4128 - linePrefix.length() - 1)
            + "public int next = 2;"
            + " ".repeat(300)
            + "\n"
            + "    }\n"
            + "}\n";

        FormatterException exception = formatterException(source, failOnParseErrorsOptions());

        assertThat(exception.sourceProblems())
                .first()
                .satisfies(problem -> {
                    FormatterException.SourceLocation location = problem.location().orElseThrow();
                    FormatterException.SourceLine errorLine = problem.contextLines()
                            .stream()
                            .filter(line -> line.lineNumber() == location.line())
                            .findFirst()
                            .orElseThrow();
                    assertThat(location.column()).isEqualTo(4128);
                    assertThat(errorLine.startColumn()).isEqualTo(4000);
                    assertThat(errorLine.text()).hasSize(256);
                    assertThat(location.column()).isBetween(
                        errorLine.startColumn(),
                        errorLine.startColumn() + errorLine.text().length()
                    );
                });
    }

    @Test
    void parsesSwitchExpressionYieldCases() {
        String formatted = Frmtr.format(switchExpressionYieldSource());

        assertLatestJavaParses(formatted);
    }

    @Test
    void unsetJavaLanguageLevelUsesRawParserMode() {
        FormatterOptions options = failOnParseErrorsOptions(FormatterOptions.JavaLanguageLevel.UNSET);

        FormatterException exception = formatterException(switchExpressionYieldSource(), options);

        assertThat(exception.sourceProblems()).anySatisfy(problem -> assertThat(problem.message()).contains(
                "yield"
        ));
    }

    @Test
    void formattingNeverChangesATextBlockStringValue() {
        // A text block is a string literal: its JLS-computed value (incidental indent stripped, escapes translated) is
        // program data, not layout. The formatter must reproduce that value exactly. This source packs the cases that an
        // embedded-language reformatter would have mangled: JSON with an escaped quote and tab, a backslash
        // line-continuation that joins two source lines into one value line, significant interior whitespace, and a
        // compact single-line HTML document that a "pretty printer" would expand across lines.
        String source = """
                class Demo {
                    void embeddedContent() {
                        String glossary = ""\"
                            {"glossary":{"title": "example \\'glossary\\'"}}
                            ""\";
                        String config = ""\"
                            { \\t "name":"example",
                              "enabled"   :true,
                              "timeout":30}
                            ""\";
                        String sql = ""\"
                            {"sql":"SELECT * FROM users \\
                            WHERE active=1"}
                            ""\";
                        String html = ""\"
                            <!DOCTYPE html><html><head><title>T</title></head><body><p>Hi</p></body></html>
                            ""\";
                    }
                }
                """;

        List<String> inputValues = textBlockValues(source);
        // Sanity: the fixture really does contain several non-trivial text blocks with escapes and interior whitespace.
        assertThat(inputValues).hasSize(4);
        assertThat(inputValues.get(0)).contains("example 'glossary'");
        assertThat(inputValues.get(1)).contains("\t").contains("\"enabled\"   :true");
        // The backslash line-continuation collapses two source lines into a single value line (no newline between them).
        assertThat(inputValues.get(2).lines()).anySatisfy(
            line -> assertThat(line).contains("SELECT * FROM users").contains("WHERE active=1")
        );
        // The HTML document is a single line in the value; a reflow into a multiline page would change it.
        assertThat(inputValues.get(3).lines().count()).isEqualTo(1L);

        String formatted = Frmtr.format(source);

        // The regression lock: every text block in the formatted output resolves to exactly the same String value as the
        // matching block in the input. Only incidental indentation may move; escapes, interior whitespace, and content
        // are preserved byte-for-byte. A reflow/expansion/escape-resolution would break this.
        assertThat(textBlockValues(formatted)).containsExactlyElementsOf(inputValues);
    }

    private static List<String> textBlockValues(String source) {
        CompilationUnit unit = new JavaParser(
            new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_25)
        )
                .parse(ParseStart.COMPILATION_UNIT, Providers.provider(source))
                .getResult()
                .orElseThrow();
        return unit.findAll(TextBlockLiteralExpr.class)
                .stream()
                .map(TextBlockLiteralExpr::translateEscapes)
                .toList();
    }

    private static FormatterException formatterException(String source) {
        return formatterException(source, FormatterOptions.defaults());
    }

    private static FormatterException formatterException(String source, FormatterOptions options) {
        Throwable thrown = catchThrowable(() -> Frmtr.format(source, options));
        assertThat(thrown).isInstanceOf(FormatterException.class);
        return (FormatterException) thrown;
    }

    private static String switchExpressionYieldSource() {
        return """
                class Demo {
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

    private static void assertLatestJavaParses(String source) {
        var parser = new JavaParser(
            new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_25)
        );
        assertThat(parser.parse(ParseStart.COMPILATION_UNIT, Providers.provider(source)).isSuccessful()).isTrue();
    }

    private static boolean latestJavaParses(String source) {
        var parser = new JavaParser(
            new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_25)
        );
        return parser.parse(ParseStart.COMPILATION_UNIT, Providers.provider(source)).isSuccessful();
    }

    private static FormatterOptions failOnParseErrorsOptions() {
        return failOnParseErrorsOptions(FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE);
    }

    private static FormatterOptions failOnParseErrorsOptions(FormatterOptions.JavaLanguageLevel javaLanguageLevel) {
        return FormatterOptions.withJavaLanguageLevel(
            FormatterOptions.DEFAULT_LINE_WIDTH,
            FormatterOptions.IndentStyle.SPACE,
            FormatterOptions.DEFAULT_INDENT_WIDTH,
            FormatterOptions.LineEnding.LF,
            true,
            javaLanguageLevel
        ).withParseErrorBehavior(FormatterOptions.ParseErrorBehavior.FAIL);
    }

    private static Path resourceRoot(String name) throws URISyntaxException {
        return Path.of(Objects.requireNonNull(FrmtrTest.class.getClassLoader().getResource(name), name).toURI())
                .toAbsolutePath()
                .normalize();
    }
}
