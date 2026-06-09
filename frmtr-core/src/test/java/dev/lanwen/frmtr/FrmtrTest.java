package dev.lanwen.frmtr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseStart;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Providers;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.Node;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.junit.jupiter.api.Test;

final class FrmtrTest {
    @Test
    void formatsBasicGoldenFixtureAndIsIdempotent() throws Exception {
        String source = readResource("format/basic/input.java");
        String expected = readResource("format/basic/frmtr.output.java");

        String formatted = Frmtr.format(source);

        assertThat(formatted).isEqualTo(expected);
        assertThat(Frmtr.format(formatted)).isEqualTo(formatted);
        assertThatCode(() -> StaticJavaParser.parse(formatted)).doesNotThrowAnyException();
    }

    @Test
    void formatsAnnotationArrayInitializerSpacingFixtureAndIsIdempotent() throws Exception {
        String source = readResource("format/annotation-array-initializer-spacing/input.java");
        String expected = readResource("format/annotation-array-initializer-spacing/frmtr.output.java");

        String formatted = Frmtr.format(source);

        assertThat(formatted).isEqualTo(expected);
        assertThat(Frmtr.format(formatted)).isEqualTo(formatted);
        assertThatCode(() -> StaticJavaParser.parse(formatted)).doesNotThrowAnyException();
    }

    @Test
    void formatsMethodChainBlockLambdaFixtureAndIsIdempotent() throws Exception {
        String source = readResource("format/method-chain-block-lambda/input.java");
        String expected = readResource("format/method-chain-block-lambda/frmtr.output.java");

        String formatted = Frmtr.format(source);

        assertThat(formatted).isEqualTo(expected);
        assertThat(Frmtr.format(formatted)).isEqualTo(formatted);
        assertThatCode(() -> StaticJavaParser.parse(formatted)).doesNotThrowAnyException();
    }

    @Test
    void formatsMethodCallCharLiteralFixtureAndIsIdempotent() throws Exception {
        String source = readResource("format/method-call-char-literal/input.java");
        String expected = readResource("format/method-call-char-literal/frmtr.output.java");

        String formatted = Frmtr.format(source);

        assertThat(formatted).isEqualTo(expected);
        assertThat(Frmtr.format(formatted)).isEqualTo(formatted);
        assertThatCode(() -> StaticJavaParser.parse(formatted)).doesNotThrowAnyException();
    }

    @Test
    void formatsBlockOrphanAndMethodCallArgumentCommentsFixtureAndIsIdempotent() throws Exception {
        String source = readResource("format/block-orphan-method-call-comments/input.java");
        String expected = readResource("format/block-orphan-method-call-comments/frmtr.output.java");

        String formatted = Frmtr.format(source);

        assertThat(formatted).isEqualTo(expected);
        assertThat(Frmtr.format(formatted)).isEqualTo(formatted);
        assertThatCode(() -> StaticJavaParser.parse(formatted)).doesNotThrowAnyException();
    }

    @Test
    void formatsCommonJavaAndIsIdempotent() {
        String source = """
                package dev.example;
                import java.util.List;
                import static java.util.Collections.emptyList;
                public class Demo{private final int value=1; public Demo(int value){this.value=value;} public int value(){return value;}}""";

        String formatted = Frmtr.format(source);

        assertThat(formatted).isEqualTo("""
                package dev.example;

                import static java.util.Collections.emptyList;

                import java.util.List;

                public class Demo {

                    private final int value = 1;

                    public Demo(int value) {
                        this.value = value;
                    }

                    public int value() {
                        return value;
                    }
                }
                """);
        assertThat(Frmtr.format(formatted)).isEqualTo(formatted);
        assertThatCode(() -> StaticJavaParser.parse(formatted)).doesNotThrowAnyException();
    }

    @Test
    void preservesLeadingComments() {
        String source = """
                package dev.example;
                // demo type
                class Demo {
                // value comment
                int value;
                }""";

        String formatted = Frmtr.format(source);

        assertThat(formatted).isEqualTo("""
                package dev.example;

                // demo type
                class Demo {

                    // value comment
                    int value;
                }
                """);
    }

    @Test
    void rejectsInvalidJava() {
        assertThatThrownBy(() -> Frmtr.format("class {")).isInstanceOf(FormatterException.class);
    }

    @Test
    void formatterOptionFactoriesDefaultToParseErrorRecovery() {
        assertThat(FormatterOptions.defaults().parseErrorBehavior())
                .isEqualTo(FormatterOptions.ParseErrorBehavior.RECOVER);
        assertThat(FormatterOptions.forLayout(
                        80,
                        FormatterOptions.IndentStyle.SPACE,
                        2,
                        FormatterOptions.LineEnding.LF,
                        true)
                .parseErrorBehavior())
                .isEqualTo(FormatterOptions.ParseErrorBehavior.RECOVER);
        assertThat(FormatterOptions.withJavaLanguageLevel(
                        80,
                        FormatterOptions.IndentStyle.SPACE,
                        2,
                        FormatterOptions.LineEnding.LF,
                        true,
                        FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE)
                .parseErrorBehavior())
                .isEqualTo(FormatterOptions.ParseErrorBehavior.RECOVER);
        assertThat(FormatterOptions.withRawTrailingWhitespace(
                        80,
                        FormatterOptions.IndentStyle.SPACE,
                        2,
                        FormatterOptions.LineEnding.LF,
                        true,
                        true,
                        FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE)
                .parseErrorBehavior())
                .isEqualTo(FormatterOptions.ParseErrorBehavior.RECOVER);
        assertThat(FormatterOptions.withPragmaRequirement(
                        80,
                        FormatterOptions.IndentStyle.SPACE,
                        2,
                        FormatterOptions.LineEnding.LF,
                        true,
                        false,
                        true,
                        FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE)
                .parseErrorBehavior())
                .isEqualTo(FormatterOptions.ParseErrorBehavior.RECOVER);
        assertThat(FormatterOptions.withLambdaArrowParens(
                        80,
                        FormatterOptions.IndentStyle.SPACE,
                        2,
                        FormatterOptions.LineEnding.LF,
                        true,
                        false,
                        false,
                        FormatterOptions.LambdaArrowParens.AVOID,
                        FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE)
                .parseErrorBehavior())
                .isEqualTo(FormatterOptions.ParseErrorBehavior.RECOVER);
    }

    @Test
    void formatterOptionParseErrorBehaviorWitherSelectsFail() {
        FormatterOptions options = FormatterOptions.withJavaLanguageLevel(
                        80,
                        FormatterOptions.IndentStyle.SPACE,
                        2,
                        FormatterOptions.LineEnding.LF,
                        true,
                        FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE)
                .withParseErrorBehavior(FormatterOptions.ParseErrorBehavior.FAIL);

        assertThat(options.parseErrorBehavior()).isEqualTo(FormatterOptions.ParseErrorBehavior.FAIL);
    }

    @Test
    void defaultParseErrorRecoveryFormatsValidBlockStatementSiblingsAroundRawMalformedGap() {
        String source = """
                class Demo{void method(){
                        before( 1 );
                        var broken = ; // keep raw
                        after( 2 );
                }}""";

        String formatted = Frmtr.format(source);

        assertThat(formatted).isEqualTo("""
                class Demo {

                    void method() {
                        before(1);
                        var broken = ; // keep raw
                        after(2);
                    }
                }
                """);
    }

    @Test
    void defaultParseErrorRecoveryFormatsValidMemberSiblingsAroundRawMalformedInitializer() {
        String source = """
                class Demo{int before=1;{
                        var broken = ; // keep raw
                }int after=2;}""";
        var result = new JavaParser(new ParserConfiguration()
                        .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
                        .setStoreTokens(true)
                        .setAttributeComments(true))
                .parse(ParseStart.COMPILATION_UNIT, Providers.provider(source));

        String formatted = Frmtr.format(source);

        assertThat(result.isSuccessful()).isFalse();
        assertThat(result.getResult()).isPresent();
        assertThat(formatted).isEqualTo("""
                class Demo {

                    int before = 1;

                    {
                        var broken = ; // keep raw
                    }

                    int after = 2;
                }
                """);
    }

    @Test
    void parseErrorRecoverySkipsTransformsBeforePrintingRecoveredTree() {
        String source = """
                import java.util.List;
                import java.io.File;
                class Demo{void method(){
                        before( 1 );
                        var broken = ;
                        after( 2 );
                }}""";

        String formatted = Frmtr.format(source);

        assertThat(formatted).isEqualTo("""
                import java.util.List;
                import java.io.File;

                class Demo {

                    void method() {
                        before(1);
                        var broken = ;
                        after(2);
                    }
                }
                """);
    }

    @Test
    void defaultParseErrorRecoveryRejectsCollapsedMalformedImportList() {
        String source = """
                package dev.example;

                import java.util.List;
                import ; // JavaParser collapses this import list today
                import java.io.File;

                class Demo {}
                """;
        var result = new JavaParser(new ParserConfiguration()
                        .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
                        .setStoreTokens(true)
                        .setAttributeComments(true))
                .parse(ParseStart.COMPILATION_UNIT, Providers.provider(source));

        Throwable thrown = catchThrowable(() -> Frmtr.format(source));

        assertThat(result.isSuccessful()).isFalse();
        assertThat(result.getResult()).isPresent();
        assertThat(result.getResult().orElseThrow().getParsed()).isEqualTo(Node.Parsedness.UNPARSABLE);
        assertThat(result.getResult().orElseThrow().getImports()).isEmpty();
        assertThat(result.getResult().orElseThrow().getTypes()).isEmpty();
        assertThat(thrown).isInstanceOfSatisfying(FormatterException.class, exception -> {
            assertThat(exception).hasMessage("Unable to parse Java source");
            assertThat(exception.sourceProblems()).first().satisfies(problem -> assertThat(problem.message())
                    .contains("Unsupported recovered node: CompilationUnit"));
        });
    }

    @Test
    void defaultParseErrorRecoveryRejectsUnsupportedMemberRecovery() {
        String source = """
                class Demo {
                    int = ;
                    void method() {}
                }""";

        Throwable thrown = catchThrowable(() -> Frmtr.format(source));

        assertThat(thrown).isInstanceOfSatisfying(FormatterException.class, exception -> {
            assertThat(exception).hasMessage("Unable to parse Java source");
            assertThat(exception.sourceProblems()).first().satisfies(problem -> assertThat(problem.message())
                    .contains("Parse-error recovery is configured")
                    .contains("module directive lists"));
        });
    }

    @Test
    void defaultParseErrorRecoveryRejectsCollapsedTopLevelDeclarationRecovery() {
        String source = """
                class Before {}
                int broken = ; // keep raw
                class After {}
                """;
        var result = new JavaParser(new ParserConfiguration()
                        .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
                        .setStoreTokens(true)
                        .setAttributeComments(true))
                .parse(ParseStart.COMPILATION_UNIT, Providers.provider(source));

        Throwable thrown = catchThrowable(() -> Frmtr.format(source));

        assertThat(result.isSuccessful()).isFalse();
        assertThat(result.getResult()).isPresent();
        assertThat(result.getResult().orElseThrow().getParsed()).isEqualTo(Node.Parsedness.UNPARSABLE);
        assertThat(result.getResult().orElseThrow().getTypes()).isEmpty();
        assertThat(thrown).isInstanceOfSatisfying(FormatterException.class, exception -> {
            assertThat(exception).hasMessage("Unable to parse Java source");
            assertThat(exception.sourceProblems()).first().satisfies(problem -> assertThat(problem.message())
                    .contains("Unsupported recovered node: CompilationUnit"));
        });
    }

    @Test
    void defaultParseErrorRecoveryRejectsCollapsedMalformedModuleDirectiveList() {
        String source = """
                module demo {
                    requires before;
                    exports ; // keep raw
                    uses after.Service;
                }
                """;
        var result = new JavaParser(new ParserConfiguration()
                        .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
                        .setStoreTokens(true)
                        .setAttributeComments(true))
                .parse(ParseStart.COMPILATION_UNIT, Providers.provider(source));

        Throwable thrown = catchThrowable(() -> Frmtr.format(source));

        assertThat(result.isSuccessful()).isFalse();
        assertThat(result.getResult()).isPresent();
        assertThat(result.getResult().orElseThrow().getParsed()).isEqualTo(Node.Parsedness.UNPARSABLE);
        assertThat(result.getResult().orElseThrow().getModule()).isEmpty();
        assertThat(thrown).isInstanceOfSatisfying(FormatterException.class, exception -> {
            assertThat(exception).hasMessage("Unable to parse Java source");
            assertThat(exception.sourceProblems()).first().satisfies(problem -> assertThat(problem.message())
                    .contains("module directive lists")
                    .contains("Unsupported recovered node: CompilationUnit"));
        });
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
                FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE);
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
                FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE);

        assertThat(Frmtr.debugDoc(source, end)).contains("Text(\" &&\")");
        assertThat(Frmtr.debugDoc(source, start)).contains("Text(\"&& \")");
    }

    @Test
    void debugDocBuildsTreeWhenFormatWouldSkipForMissingPragma() {
        FormatterOptions requirePragma = FormatterOptions.withPragmaRequirement(
                80,
                FormatterOptions.IndentStyle.SPACE,
                2,
                FormatterOptions.LineEnding.LF,
                true,
                false,
                true,
                FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE);

        String source = "class Demo{int value;}";

        assertThat(Frmtr.format(source, requirePragma)).isEqualTo(source);
        assertThat(Frmtr.debugDoc(source, requirePragma)).contains("Text(\"class \")");
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
                                + "NoSuchFieldError: variables")
                .hasCause(cause);
        assertThat(exception.internal()).isTrue();
    }

    @Test
    void defaultsToLatestAvailableJavaLanguageLevel() {
        assertThat(FormatterOptions.defaults().javaLanguageLevel())
                .isEqualTo(FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE);
        assertThat(FormatterOptions.defaults().preserveRawTrailingWhitespace()).isFalse();
        assertThat(FormatterOptions.defaults().requirePragma()).isFalse();
        assertThat(FormatterOptions.defaults().lambdaArrowParens())
                .isEqualTo(FormatterOptions.LambdaArrowParens.PRESERVE);
        assertThat(FormatterOptions.defaults().binaryOperatorPosition())
                .isEqualTo(FormatterOptions.BinaryOperatorPosition.END);
    }

    @Test
    void lambdaArrowParensOptionControlsSingleParameterLambdas() {
        String source = """
                class Demo {
                    void method() {
                        call((value) -> value);
                    }
                }
                """;

        FormatterOptions avoid = FormatterOptions.withLambdaArrowParens(
                80,
                FormatterOptions.IndentStyle.SPACE,
                2,
                FormatterOptions.LineEnding.LF,
                true,
                false,
                false,
                FormatterOptions.LambdaArrowParens.AVOID,
                FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE);
        FormatterOptions always = FormatterOptions.withLambdaArrowParens(
                80,
                FormatterOptions.IndentStyle.SPACE,
                2,
                FormatterOptions.LineEnding.LF,
                true,
                false,
                false,
                FormatterOptions.LambdaArrowParens.ALWAYS,
                FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE);

        assertThat(Frmtr.format(source, avoid)).contains("call(value -> value);");
        assertThat(Frmtr.format(source.replace("(value) ->", "value ->"), always)).contains("call((value) -> value);");
    }

    @Test
    void formatsEnumConstantLambdaArguments() {
        String source = """
                enum Demo {
                    VALUE(x -> {
                        // testing method
                        return n * 2;
                    }, other),
                }
                """;
        FormatterOptions options = FormatterOptions.withJavaLanguageLevel(
                80,
                FormatterOptions.IndentStyle.SPACE,
                2,
                FormatterOptions.LineEnding.LF,
                true,
                FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE);

        String formatted = Frmtr.format(source, options);

        assertThat(formatted)
                .contains("VALUE(x -> {\n"
                        + "    // testing method\n"
                        + "    return n * 2;\n"
                        + "  }, other),");
    }

    @Test
    void breaksLongBinaryLambdaExpressionBody() {
        String source = """
                class Demo {
                    void method() {
                        call(value -> firstVeryLongConditionName && secondVeryLongConditionName);
                    }
                }
                """;
        FormatterOptions options = FormatterOptions.withJavaLanguageLevel(
                60,
                FormatterOptions.IndentStyle.SPACE,
                2,
                FormatterOptions.LineEnding.LF,
                true,
                FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE);

        String formatted = Frmtr.format(source, options);

        assertThat(formatted)
                .contains("value ->\n"
                        + "        firstVeryLongConditionName &&\n"
                        + "        secondVeryLongConditionName");
    }

    @Test
    void hugsMethodCallExpressionLambdaBeforeBreakingBody() {
        String source = """
                class Demo {
                    void method() {
                        a.b(c -> eeeeeeeeee.ffffffffff(gggggggggg, hhhhhhhhhh, iiiiiiiiii, jjjjjjjjjj, kkkkkkkkkk));
                    }
                }
                """;
        FormatterOptions options = FormatterOptions.withJavaLanguageLevel(
                80,
                FormatterOptions.IndentStyle.SPACE,
                2,
                FormatterOptions.LineEnding.LF,
                true,
                FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE);

        String formatted = Frmtr.format(source, options);

        assertThat(formatted)
                .contains("a.b(c ->\n"
                        + "      eeeeeeeeee.ffffffffff(\n"
                        + "        gggggggggg,\n"
                        + "        hhhhhhhhhh,\n"
                        + "        iiiiiiiiii,\n"
                        + "        jjjjjjjjjj,\n"
                        + "        kkkkkkkkkk\n"
                        + "      )\n"
                        + "    );");
    }

    @Test
    void hugsExactWidthMethodCallExpressionLambdaBeforeBreakingBody() {
        String source = """
                class Demo {
                    void method() {
                        aaaaaaaaaa(bbbbbbbbbb -> cccccccccc("123456789012345678901234567890123456"));
                    }
                }
                """;
        FormatterOptions options = FormatterOptions.withJavaLanguageLevel(
                80,
                FormatterOptions.IndentStyle.SPACE,
                2,
                FormatterOptions.LineEnding.LF,
                true,
                FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE);

        String formatted = Frmtr.format(source, options);

        assertThat(formatted)
                .contains("aaaaaaaaaa(bbbbbbbbbb ->\n"
                        + "      cccccccccc(\"123456789012345678901234567890123456\")\n"
                        + "    );");
    }

    @Test
    void hugsNestedMethodCallExpressionLambdaBeforeBreakingBody() {
        String source = """
                class Demo {
                    void method() {
                        a.b(c -> d -> eeeeeeeeee.ffffffffff(gggggggggg, hhhhhhhhhh, iiiiiiiiii, jjjjjjjjjj, kkkkkkkkkk));
                    }
                }
                """;
        FormatterOptions options = FormatterOptions.withJavaLanguageLevel(
                80,
                FormatterOptions.IndentStyle.SPACE,
                2,
                FormatterOptions.LineEnding.LF,
                true,
                FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE);

        String formatted = Frmtr.format(source, options);

        assertThat(formatted)
                .contains("a.b(\n"
                        + "      c -> d ->\n"
                        + "        eeeeeeeeee.ffffffffff(\n"
                        + "          gggggggggg,\n"
                        + "          hhhhhhhhhh,\n"
                        + "          iiiiiiiiii,\n"
                        + "          jjjjjjjjjj,\n"
                        + "          kkkkkkkkkk\n"
                        + "        )\n"
                        + "    );");
    }

    @Test
    void hugsConditionalExpressionLambdaBeforeBreakingBody() {
        String source = """
                class Demo {
                    void method() {
                        a.b(c -> d && eeeeeeeeee.ffffffffff() ? g && hhhhhhhhhh.iiiiiiiiii() : j && kkkkkkkkkk.llllllllll());
                    }
                }
                """;
        FormatterOptions options = FormatterOptions.withJavaLanguageLevel(
                80,
                FormatterOptions.IndentStyle.SPACE,
                2,
                FormatterOptions.LineEnding.LF,
                true,
                FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE);

        String formatted = Frmtr.format(source, options);

        assertThat(formatted)
                .contains("a.b(c ->\n"
                        + "      d && eeeeeeeeee.ffffffffff()\n"
                        + "        ? g && hhhhhhhhhh.iiiiiiiiii()\n"
                        + "        : j && kkkkkkkkkk.llllllllll()\n"
                        + "    );");
    }

    @Test
    void preservesLineCommentsInBrokenConditionalExpression() {
        String source = """
                class Demo {
                    void method() {
                        value = a ? // b
                            b : // c
                            c;
                        value = a
                            // b
                            ? b
                            // c
                            : c;
                        value = a
                            ? b // b
                            : c; // c
                    }
                }
                """;
        FormatterOptions options = FormatterOptions.withJavaLanguageLevel(
                80,
                FormatterOptions.IndentStyle.SPACE,
                2,
                FormatterOptions.LineEnding.LF,
                true,
                FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE);

        String formatted = Frmtr.format(source, options);

        assertThat(formatted)
                .contains("value = a\n"
                        + "      ? // b\n"
                        + "        b\n"
                        + "      : // c\n"
                        + "        c;")
                .contains("value = a // b\n"
                        + "      ? b\n"
                        + "      : // c\n"
                        + "        c;")
                .contains("value = a\n"
                        + "      ? b // b\n"
                        + "      : c; // c");
    }

    @Test
    void promotesStaticChainRootWithBlockLambdaFirstCall() {
        String source = """
                class Demo {
                    Object method() {
                        return ctor -> Try.of(a, () -> {
                            var ng = ctor.newInstance(entity.getId(), entity.getSystemGenerated(), entity.getVersionKey());
                            return ng;
                        }).getOrElseThrow(ex -> new RuntimeException(ex));
                    }
                }
                """;
        FormatterOptions options = FormatterOptions.withJavaLanguageLevel(
                80,
                FormatterOptions.IndentStyle.SPACE,
                2,
                FormatterOptions.LineEnding.LF,
                true,
                FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE);

        String formatted = Frmtr.format(source, options);

        assertThat(formatted)
                .contains("return ctor ->\n"
                        + "      Try.of(a, () -> {\n"
                        + "        var ng = ctor.newInstance(\n"
                        + "          entity.getId(),\n"
                        + "          entity.getSystemGenerated(),\n"
                        + "          entity.getVersionKey()\n"
                        + "        );\n"
                        + "        return ng;\n"
                        + "      }).getOrElseThrow(ex -> new RuntimeException(ex));");
    }

    @Test
    void promotesStaticChainFirstCallBeforeCommentedLambdaArgument() {
        String source = """
                class Demo {
                    void method() {
                        System.out.println(List.of(1, 2, 3).stream().map(
                            // first
                            // second
                            v -> v * 2
                        ).collect(Collectors.summingInt(v -> v)));
                    }
                }
                """;
        FormatterOptions options = FormatterOptions.withJavaLanguageLevel(
                80,
                FormatterOptions.IndentStyle.SPACE,
                2,
                FormatterOptions.LineEnding.LF,
                true,
                FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE);

        String formatted = Frmtr.format(source, options);

        assertThat(formatted)
                .contains("List.of(1, 2, 3)\n"
                        + "        .stream()\n"
                        + "        .map(\n"
                        + "          // first\n"
                        + "          // second\n"
                        + "          v -> v * 2\n"
                        + "        )");
    }

    @Test
    void breaksParenthesizedExpressionLambdaBody() {
        String source = """
                class Demo {
                    void method() {
                        (aaaaaaaaaa -> bbbbbbbbbb.cccccccccc().dddddddddd().eeeeeeeeee().ffffffffff());
                    }
                }
                """;
        FormatterOptions options = FormatterOptions.withJavaLanguageLevel(
                80,
                FormatterOptions.IndentStyle.SPACE,
                2,
                FormatterOptions.LineEnding.LF,
                true,
                FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE);

        String formatted = Frmtr.format(source, options);

        assertThat(formatted)
                .contains("(aaaaaaaaaa ->\n"
                        + "      bbbbbbbbbb.cccccccccc().dddddddddd().eeeeeeeeee().ffffffffff());");
    }

    @Test
    void parenthesizedExpressionLambdaBodyRespectsAlwaysArrowParens() {
        String source = """
                class Demo {
                    void method() {
                        (aaaaaaaaaa -> bbbbbbbbbb.cccccccccc().dddddddddd().eeeeeeeeee().ffffffffff());
                    }
                }
                """;
        FormatterOptions options = FormatterOptions.withLambdaArrowParens(
                80,
                FormatterOptions.IndentStyle.SPACE,
                2,
                FormatterOptions.LineEnding.LF,
                true,
                false,
                false,
                FormatterOptions.LambdaArrowParens.ALWAYS,
                FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE);

        String formatted = Frmtr.format(source, options);

        assertThat(formatted)
                .contains("((aaaaaaaaaa) ->\n"
                        + "      bbbbbbbbbb.cccccccccc().dddddddddd().eeeeeeeeee().ffffffffff());");
    }

    @Test
    void preservesLeadingLineCommentsOnExpressionLambdaArgument() {
        String source = """
                class Demo {
                    void method() {
                        list.map(
                            // first
                            // second
                            v -> v * 2
                        );
                    }
                }
                """;
        FormatterOptions options = FormatterOptions.withJavaLanguageLevel(
                80,
                FormatterOptions.IndentStyle.SPACE,
                2,
                FormatterOptions.LineEnding.LF,
                true,
                FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE);

        String formatted = Frmtr.format(source, options);

        assertThat(formatted)
                .contains("list.map(\n"
                        + "      // first\n"
                        + "      // second\n"
                        + "      v -> v * 2\n"
                        + "    );");
    }

    @Test
    void preservesLineCommentBeforeExpressionLambdaArgument() {
        String source = """
                class Demo {
                    void method() {
                        a( // comment
                            (b, c, d) -> e.f()
                        );
                    }
                }
                """;
        FormatterOptions options = FormatterOptions.withJavaLanguageLevel(
                80,
                FormatterOptions.IndentStyle.SPACE,
                2,
                FormatterOptions.LineEnding.LF,
                true,
                FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE);

        String formatted = Frmtr.format(source, options);

        assertThat(formatted)
                .contains("a(\n"
                        + "      // comment\n"
                        + "      (b, c, d) -> e.f()\n"
                        + "    );");
    }

    @Test
    void preservesInlineBlockCommentBeforeExpressionLambdaArgument() {
        String source = """
                class Demo {
                    void method() {
                        a(/* comment */ (b, c, d) -> e.f());
                    }
                }
                """;
        FormatterOptions options = FormatterOptions.withJavaLanguageLevel(
                80,
                FormatterOptions.IndentStyle.SPACE,
                2,
                FormatterOptions.LineEnding.LF,
                true,
                FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE);

        String formatted = Frmtr.format(source, options);

        assertThat(formatted).contains("a(/* comment */ (b, c, d) -> e.f());");
    }

    @Test
    void keepsInlineBlockCommentsInFittingLambdaParameters() {
        String source = """
                class Demo {
                    void method() {
                        a(( /* first */ b, c, d) -> e.f());
                        a((b, /* second */ c, d) -> e.f());
                        a((b, c, d /* third */) -> e.f());
                    }
                }
                """;
        FormatterOptions options = FormatterOptions.withJavaLanguageLevel(
                80,
                FormatterOptions.IndentStyle.SPACE,
                2,
                FormatterOptions.LineEnding.LF,
                true,
                FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE);

        String formatted = Frmtr.format(source, options);

        assertThat(formatted)
                .contains("a((/* first */ b, c, d) -> e.f());")
                .contains("a((b, /* second */ c, d) -> e.f());")
                .contains("a((b, c, d /* third */) -> e.f());");
    }

    @Test
    void keepsLeadingBlockCommentWithBrokenExpressionLambdaParameters() {
        String source = """
                class Demo {
                    void method() {
                        aaaaaaaaaaaaaaaaaaaaaaaa(/* comment */ (bbbbbbbbbbbbbbbbbbbbbbbb, cccccccccccccccccccccccc, dddddddddddddddddddddddd) -> eeeeeeeeeeeeeeeeeeeeeeee.ffffffffffffffffffffffff());
                    }
                }
                """;
        FormatterOptions options = FormatterOptions.withJavaLanguageLevel(
                80,
                FormatterOptions.IndentStyle.SPACE,
                2,
                FormatterOptions.LineEnding.LF,
                true,
                FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE);

        String formatted = Frmtr.format(source, options);

        assertThat(formatted)
                .contains("aaaaaaaaaaaaaaaaaaaaaaaa(\n"
                        + "      /* comment */ (\n"
                        + "        bbbbbbbbbbbbbbbbbbbbbbbb,\n"
                        + "        cccccccccccccccccccccccc,\n"
                        + "        dddddddddddddddddddddddd\n"
                        + "      ) -> eeeeeeeeeeeeeeeeeeeeeeee.ffffffffffffffffffffffff()\n"
                        + "    );");
    }

    @Test
    void preservesTrailingLineCommentsOnExpressionLambdaArgument() {
        String source = """
                class Demo {
                    void method() {
                        list.map(
                            v -> v * 2
                            // first
                            // second
                        );
                    }
                }
                """;
        FormatterOptions options = FormatterOptions.withJavaLanguageLevel(
                80,
                FormatterOptions.IndentStyle.SPACE,
                2,
                FormatterOptions.LineEnding.LF,
                true,
                FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE);

        String formatted = Frmtr.format(source, options);

        assertThat(formatted)
                .contains("list.map(\n"
                        + "      v -> v * 2\n"
                        + "      // first\n"
                        + "      // second\n"
                        + "    );");
    }

    @Test
    void keepsBrokenLambdaParametersWithCompactMethodCallBody() {
        String source = """
                class Demo {
                    void method() {
                        aaaaaaaaaaaaaaaaaaaaaaaa((bbbbbbbbbbbbbbbbbbbbbbbb, cccccccccccccccccccccccc, dddddddddddddddddddddddd) -> eeeeeeeeeeeeeeeeeeeeeeee.ffffffffffffffffffffffff());
                    }
                }
                """;
        FormatterOptions options = FormatterOptions.withJavaLanguageLevel(
                80,
                FormatterOptions.IndentStyle.SPACE,
                2,
                FormatterOptions.LineEnding.LF,
                true,
                FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE);

        String formatted = Frmtr.format(source, options);

        assertThat(formatted)
                .contains("aaaaaaaaaaaaaaaaaaaaaaaa(\n"
                        + "      (\n"
                        + "        bbbbbbbbbbbbbbbbbbbbbbbb,\n"
                        + "        cccccccccccccccccccccccc,\n"
                        + "        dddddddddddddddddddddddd\n"
                        + "      ) -> eeeeeeeeeeeeeeeeeeeeeeee.ffffffffffffffffffffffff()\n"
                        + "    );");
    }

    @Test
    void keepsRelationalLambdaBodyOperatorWithBrokenLeftExpression() {
        String source = """
                class Demo {
                    void method() {
                        call(value -> eeeeeeeeee.ffffffffff(gggggggggg, hhhhhhhhhh, iiiiiiiiii, jjjjjjjjjj, kkkkkkkkkk) > 0);
                    }
                }
                """;
        FormatterOptions options = FormatterOptions.withJavaLanguageLevel(
                80,
                FormatterOptions.IndentStyle.SPACE,
                2,
                FormatterOptions.LineEnding.LF,
                true,
                FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE);

        String formatted = Frmtr.format(source, options);

        assertThat(formatted)
                .contains("eeeeeeeeee.ffffffffff(\n"
                        + "          gggggggggg,\n"
                        + "          hhhhhhhhhh,\n"
                        + "          iiiiiiiiii,\n"
                        + "          jjjjjjjjjj,\n"
                        + "          kkkkkkkkkk\n"
                        + "        ) > 0");
    }

    @Test
    void binaryOperatorPositionOptionControlsBrokenContinuationLines() {
        String source = """
                class Demo {
                    void method() {
                        value = aaaaaaaaaa && bbbbbbbbbb && cccccccccc && dddddddddd;
                    }
                }
                """;
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
                FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE);
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
                FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE);

        assertThat(Frmtr.format(source, start))
                .contains("value =\n      aaaaaaaaaa\n      && bbbbbbbbbb\n      && cccccccccc\n      && dddddddddd;");
        assertThat(Frmtr.format(source, end))
                .contains("value =\n      aaaaaaaaaa &&\n      bbbbbbbbbb &&\n      cccccccccc &&\n      dddddddddd;");
    }

    @Test
    void preservesLineCommentsBetweenVariableEqualsAndInitializer() {
        String source = """
                class Demo {
                    void method() {
                        Map<String, String> map =
                            // first comment
                            // second comment
                            new HashMap<>(
                                initialValues()
                            );
                    }
                }
                """;

        assertThat(Frmtr.format(source, FormatterOptions.withJavaLanguageLevel(
                        80,
                        FormatterOptions.IndentStyle.SPACE,
                        2,
                        FormatterOptions.LineEnding.LF,
                        true,
                        FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE)))
                .contains("Map<String, String> map =\n"
                        + "      // first comment\n"
                        + "      // second comment\n"
                        + "      new HashMap<>(initialValues());");
    }

    @Test
    void binaryOperatorPositionOptionControlsBrokenAnnotationValues() {
        String source = """
                class Demo {
                    @Annotation("This operation with two very long string should break" + "in a very nice way")
                    void method() {}
                }
                """;
        FormatterOptions start = new FormatterOptions(
                80,
                FormatterOptions.IndentStyle.SPACE,
                2,
                FormatterOptions.LineEnding.LF,
                true,
                false,
                false,
                FormatterOptions.LambdaArrowParens.PRESERVE,
                FormatterOptions.BinaryOperatorPosition.START,
                FormatterOptions.ParseErrorBehavior.RECOVER,
                FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE);
        FormatterOptions end = new FormatterOptions(
                80,
                FormatterOptions.IndentStyle.SPACE,
                2,
                FormatterOptions.LineEnding.LF,
                true,
                false,
                false,
                FormatterOptions.LambdaArrowParens.PRESERVE,
                FormatterOptions.BinaryOperatorPosition.END,
                FormatterOptions.ParseErrorBehavior.RECOVER,
                FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE);

        assertThat(Frmtr.format(source, start))
                .contains("@Annotation(\n"
                        + "    \"This operation with two very long string should break\"\n"
                        + "      + \"in a very nice way\"\n"
                        + "  )");
        assertThat(Frmtr.format(source, end))
                .contains("@Annotation(\n"
                        + "    \"This operation with two very long string should break\" +\n"
                        + "      \"in a very nice way\"\n"
                        + "  )");
    }

    @Test
    void binaryOperatorPositionOptionControlsBrokenSingleMethodCallArgument() {
        String source = """
                class Demo {
                    void method() {
                        System.out.println("This operation with two very long string should break" + "in a very nice way");
                    }
                }
                """;
        FormatterOptions start = new FormatterOptions(
                80,
                FormatterOptions.IndentStyle.SPACE,
                2,
                FormatterOptions.LineEnding.LF,
                true,
                false,
                false,
                FormatterOptions.LambdaArrowParens.PRESERVE,
                FormatterOptions.BinaryOperatorPosition.START,
                FormatterOptions.ParseErrorBehavior.RECOVER,
                FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE);
        FormatterOptions end = new FormatterOptions(
                80,
                FormatterOptions.IndentStyle.SPACE,
                2,
                FormatterOptions.LineEnding.LF,
                true,
                false,
                false,
                FormatterOptions.LambdaArrowParens.PRESERVE,
                FormatterOptions.BinaryOperatorPosition.END,
                FormatterOptions.ParseErrorBehavior.RECOVER,
                FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE);

        assertThat(Frmtr.format(source, start))
                .contains("System.out.println(\n"
                        + "      \"This operation with two very long string should break\"\n"
                        + "        + \"in a very nice way\"\n"
                        + "    );");
        assertThat(Frmtr.format(source, end))
                .contains("System.out.println(\n"
                        + "      \"This operation with two very long string should break\" +\n"
                        + "        \"in a very nice way\"\n"
                        + "    );");
    }

    @Test
    void breaksSingleTextBlockMethodCallArgumentAndPreservesArgumentComments() {
        String source = """
                class Demo {
                    void method() {
                        System.out.println(
                            // leading comment
                            \"""
                            text
                            \""" // trailing comment
                        );
                    }
                }
                """;

        FormatterOptions options = FormatterOptions.withJavaLanguageLevel(
                80,
                FormatterOptions.IndentStyle.SPACE,
                2,
                FormatterOptions.LineEnding.LF,
                true,
                FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE);

        String formatted = Frmtr.format(source, options);

        assertThat(formatted)
                .contains("System.out.println(\n"
                        + "      // leading comment\n"
                        + "      \"\"\"\n"
                        + "      text\n"
                        + "      \"\"\" // trailing comment\n"
                        + "    );");
    }

    @Test
    void preservesTextBlockMethodCallScope() {
        String source = """
                class Demo {
                    void method() {
                        String source = \"""
                                    public void print(%s object) {
                                        System.out.println(Objects.toString(object));
                                    }
                                    \""".formatted(type);
                    }
                }
                """;
        FormatterOptions options = FormatterOptions.withJavaLanguageLevel(
                80,
                FormatterOptions.IndentStyle.SPACE,
                2,
                FormatterOptions.LineEnding.LF,
                true,
                FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE);

        String formatted = Frmtr.format(source, options);

        assertThat(formatted)
                .contains("String source = \"\"\"\n"
                        + "      public void print(%s object) {\n"
                        + "          System.out.println(Objects.toString(object));\n"
                        + "      }\n"
                        + "      \"\"\".formatted(type);");
    }

    @Test
    void keepsTextBlockInitializerOpeningAfterEquals() {
        String source = """
                class Demo {
                    void method() {
                        String html = \"""
                                  <html>012345678901234567890123456789012345678901234567890123456789</html>
                                  \""";
                    }
                }
                """;
        FormatterOptions options = FormatterOptions.withJavaLanguageLevel(
                80,
                FormatterOptions.IndentStyle.SPACE,
                2,
                FormatterOptions.LineEnding.LF,
                true,
                FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE);

        String formatted = Frmtr.format(source, options);

        assertThat(formatted).contains("String html = \"\"\"");
    }

    @Test
    void formatsCompactHtmlTextBlock() {
        String source = """
                class Demo {
                    void method() {
                        String html = \"""
                                  <!DOCTYPE html><html><head><title>Page Title</title></head><body><h1>My First Heading</h1><p>My first paragraph.</p></body></html>
                                  \""";
                    }
                }
                """;
        FormatterOptions options = FormatterOptions.withJavaLanguageLevel(
                80,
                FormatterOptions.IndentStyle.SPACE,
                2,
                FormatterOptions.LineEnding.LF,
                true,
                FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE);

        String formatted = Frmtr.format(source, options);

        assertThat(formatted)
                .contains("""
                        String html = \"\"\"
                              <!DOCTYPE html>
                              <html>
                                <head>
                                  <title>Page Title</title>
                                </head>
                                <body>
                                  <h1>My First Heading</h1>
                                  <p>My first paragraph.</p>
                                </body>
                              </html>
                              \"\"\";""");
    }

    @Test
    void formatsCompactJsonTextBlocks() {
        String source = """
                class Demo {
                    void method() {
                        String someJson = \"""
                            {"glossary":{"title": "example \\'glossary\\'"}}
                            \""";
                        String config = \"""
                              { \\t "name":"example",
                          "enabled"   :true,
                                "timeout":30}
                            \""";
                        String query = \"""
                             {
                           "sql":"SELECT * FROM users \\
                        WHERE active=1 \\
                        AND deleted=0",
                           "limit":10}
                            \""";
                    }
                }
                """;
        FormatterOptions options = FormatterOptions.withJavaLanguageLevel(
                80,
                FormatterOptions.IndentStyle.SPACE,
                2,
                FormatterOptions.LineEnding.LF,
                true,
                FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE);

        String formatted = Frmtr.format(source, options);

        assertThat(formatted)
                .contains("{ \"glossary\": { \"title\": \"example 'glossary'\" } }")
                .contains("{ \"name\": \"example\", \"enabled\": true, \"timeout\": 30 }")
                .contains("""
                        {
                                "sql": "SELECT * FROM users WHERE active=1 AND deleted=0",
                                "limit": 10
                              }""");
    }

    @Test
    void formatsCompactJavaTextBlock() {
        String source = """
                class Demo {
                    void method() {
                        String java = \"""
                            class Class{void method() {
                            // comment
                            }}
                            \""";
                    }
                }
                """;
        FormatterOptions options = FormatterOptions.withJavaLanguageLevel(
                80,
                FormatterOptions.IndentStyle.SPACE,
                2,
                FormatterOptions.LineEnding.LF,
                true,
                FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE);

        String formatted = Frmtr.format(source, options);

        assertThat(formatted)
                .contains("String java = \"\"\"\n")
                .contains("      class Class {\n")
                .contains("        void method() {\n")
                .contains("          // comment\n")
                .contains("        }\n")
                .contains("      }\n")
                .contains("      \"\"\";");
    }

    @Test
    void breaksLongBinaryConditionalCondition() {
        String source = """
                class Demo {
                    void method() {
                        value = aaaaaaaaaa && bbbbbbbbbb && cccccccccc && dddddddddd && eeeeeeeeee && ffffffffff ? gggggggggg : hhhhhhhhhh;
                    }
                }
                """;
        FormatterOptions options = FormatterOptions.withJavaLanguageLevel(
                80,
                FormatterOptions.IndentStyle.SPACE,
                2,
                FormatterOptions.LineEnding.LF,
                true,
                FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE);

        String formatted = Frmtr.format(source, options);

        assertThat(formatted)
                .contains("value = aaaaaaaaaa &&\n"
                        + "    bbbbbbbbbb &&\n"
                        + "    cccccccccc &&\n"
                        + "    dddddddddd &&\n"
                        + "    eeeeeeeeee &&\n"
                        + "    ffffffffff\n"
                        + "      ? gggggggggg\n"
                        + "      : hhhhhhhhhh;");
    }

    @Test
    void breaksNestedConditionalBranches() {
        String source = """
                class Demo {
                    void method() {
                        value = aaaaaaaaaa ? bbbbbbbbbb : cccccccccc ? dddddddddd : eeeeeeeeee ? ffffffffff : gggggggggg;
                    }
                }
                """;
        FormatterOptions options = FormatterOptions.withJavaLanguageLevel(
                80,
                FormatterOptions.IndentStyle.SPACE,
                2,
                FormatterOptions.LineEnding.LF,
                true,
                FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE);

        String formatted = Frmtr.format(source, options);

        assertThat(formatted)
                .contains("value = aaaaaaaaaa\n"
                        + "      ? bbbbbbbbbb\n"
                        + "      : cccccccccc\n"
                        + "        ? dddddddddd\n"
                        + "        : eeeeeeeeee\n"
                        + "          ? ffffffffff\n"
                        + "          : gggggggggg;");
    }

    @Test
    void keepsShortConditionalInitializerConditionAfterEquals() {
        String source = """
                class Demo {
                    void method() {
                        int shortInteger = thisIsAVeryLongInteger ? thisIsAnotherVeryLongOne : thisIsAnotherVeryLongIntegerThatIsEvenLongerThanFirstOne;
                    }
                }
                """;
        FormatterOptions options = FormatterOptions.withJavaLanguageLevel(
                80,
                FormatterOptions.IndentStyle.SPACE,
                2,
                FormatterOptions.LineEnding.LF,
                true,
                FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE);

        String formatted = Frmtr.format(source, options);

        assertThat(formatted)
                .contains("int shortInteger = thisIsAVeryLongInteger\n"
                        + "      ? thisIsAnotherVeryLongOne\n"
                        + "      : thisIsAnotherVeryLongIntegerThatIsEvenLongerThanFirstOne;");
    }

    @Test
    void breaksAssignmentBeforeMethodCallComparisonConditionalValue() {
        String source = """
                class Demo {
                    void method() {
                        aaaaaaaaaa = bbbbbbbbbb(cccccccccc, dddddddddd, eeeeeeeeee) != ffffffffff ? gggggggggg : hhhhhhhhhh;
                    }
                }
                """;
        FormatterOptions options = FormatterOptions.withJavaLanguageLevel(
                80,
                FormatterOptions.IndentStyle.SPACE,
                2,
                FormatterOptions.LineEnding.LF,
                true,
                FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE);

        String formatted = Frmtr.format(source, options);

        assertThat(formatted)
                .contains("aaaaaaaaaa =\n"
                        + "      bbbbbbbbbb(cccccccccc, dddddddddd, eeeeeeeeee) != ffffffffff\n"
                        + "        ? gggggggggg\n"
                        + "        : hhhhhhhhhh;");
    }

    @Test
    void preservesCommentsInBrokenBinaryContinuationLines() {
        String source = """
                class Demo {
                    void method() {
                        boolean value = one || two >> 1 // one
                            // two
                            // three
                            || three;
                    }
                }
                """;
        FormatterOptions start = new FormatterOptions(
                80,
                FormatterOptions.IndentStyle.SPACE,
                2,
                FormatterOptions.LineEnding.LF,
                true,
                false,
                false,
                FormatterOptions.LambdaArrowParens.PRESERVE,
                FormatterOptions.BinaryOperatorPosition.START,
                FormatterOptions.ParseErrorBehavior.RECOVER,
                FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE);
        FormatterOptions end = new FormatterOptions(
                80,
                FormatterOptions.IndentStyle.SPACE,
                2,
                FormatterOptions.LineEnding.LF,
                true,
                false,
                false,
                FormatterOptions.LambdaArrowParens.PRESERVE,
                FormatterOptions.BinaryOperatorPosition.END,
                FormatterOptions.ParseErrorBehavior.RECOVER,
                FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE);

        assertThat(Frmtr.format(source, start))
                .contains("boolean value =\n"
                        + "      one\n"
                        + "      || two >> 1\n"
                        + "      // one\n"
                        + "      // two\n"
                        + "      // three\n"
                        + "      || three;");
        assertThat(Frmtr.format(source, end))
                .contains("boolean value =\n"
                        + "      one ||\n"
                        + "      two >> 1 || // one\n"
                        + "      // two\n"
                        + "      // three\n"
                        + "      three;");
    }

    @Test
    void requirePragmaLeavesSourceWithoutLeadingFormatPragmaUnchanged() {
        String source = """
                /**
                 * @surely this is invalid
                 */
                 public class Demo{int value;}\
                """;
        FormatterOptions options = FormatterOptions.withPragmaRequirement(
                FormatterOptions.DEFAULT_LINE_WIDTH,
                FormatterOptions.IndentStyle.SPACE,
                FormatterOptions.DEFAULT_INDENT_WIDTH,
                FormatterOptions.LineEnding.LF,
                true,
                false,
                true,
                FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE);

        String formatted = Frmtr.format(source, options);

        assertThat(formatted).isEqualTo(source);
    }

    @Test
    void requirePragmaFormatsSourceWithLeadingFormatPragma() {
        String source = """
                /**
                 * @format
                 */
                 public class Demo{int value;}\
                """;
        FormatterOptions options = FormatterOptions.withPragmaRequirement(
                FormatterOptions.DEFAULT_LINE_WIDTH,
                FormatterOptions.IndentStyle.SPACE,
                FormatterOptions.DEFAULT_INDENT_WIDTH,
                FormatterOptions.LineEnding.LF,
                true,
                false,
                true,
                FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE);

        String formatted = Frmtr.format(source, options);

        assertThat(formatted).isNotEqualTo(source).contains("public class Demo {");
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
        assertThat(exception.sourceProblems()).singleElement().satisfies(problem -> {
            assertThat(problem.message()).contains("(line 3,col 23) Parse error");
            assertThat(problem.location()).hasValue(new FormatterException.SourceLocation(3, 23));
            assertThat(problem.enclosingUnitLine())
                    .hasValue(new FormatterException.SourceLine(2, 1, "    void method() {"));
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
        String source = readResource("format/prettier-java/unit-test/template-expression/prettier.output.java");

        FormatterException exception = formatterException(source, failOnParseErrorsOptions());

        assertThat(exception).hasMessage("Unable to parse Java source");
        assertThat(exception.sourceProblems()).first().satisfies(problem -> {
            assertThat(problem.message()).contains("Lexical error at line 3, column 34");
            assertThat(problem.location()).hasValue(new FormatterException.SourceLocation(3, 34));
            assertThat(problem.enclosingUnitLine())
                    .hasValue(new FormatterException.SourceLine(1, 1, "class TemplateExpression {"));
            assertThat(problem.contextLines())
                    .contains(new FormatterException.SourceLine(1, 1, "class TemplateExpression {"))
                    .contains(new FormatterException.SourceLine(3, 1, "  String info = STR.\"My name is \\{name}\";"));
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

        assertThat(exception.sourceProblems()).first().satisfies(problem -> assertThat(problem.contextLines())
                .extracting(FormatterException.SourceLine::lineNumber)
                .containsExactly(3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13));
        assertThat(exception.sourceProblems()).first().satisfies(problem -> assertThat(problem.enclosingUnitLine())
                .hasValue(new FormatterException.SourceLine(2, 1, "    void method() {")));
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

        assertThat(exception.sourceProblems()).first().satisfies(problem -> {
            FormatterException.SourceLocation location = problem.location().orElseThrow();
            FormatterException.SourceLine errorLine = problem.contextLines().stream()
                    .filter(line -> line.lineNumber() == location.line())
                    .findFirst()
                    .orElseThrow();
            assertThat(location.column()).isEqualTo(4128);
            assertThat(errorLine.startColumn()).isEqualTo(4000);
            assertThat(errorLine.text()).hasSize(256);
            assertThat(location.column()).isBetween(
                    errorLine.startColumn(),
                    errorLine.startColumn() + errorLine.text().length());
        });
    }

    @Test
    void parsesSwitchExpressionYieldCases() {
        String formatted = Frmtr.format(switchExpressionYieldSource());

        var parser = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_25));
        assertThat(parser.parse(ParseStart.COMPILATION_UNIT, Providers.provider(formatted)).isSuccessful())
                .isTrue();
    }

    @Test
    void unsetJavaLanguageLevelUsesRawParserMode() {
        FormatterOptions options = failOnParseErrorsOptions(FormatterOptions.JavaLanguageLevel.UNSET);

        FormatterException exception = formatterException(switchExpressionYieldSource(), options);

        assertThat(exception.sourceProblems()).anySatisfy(problem -> assertThat(problem.message()).contains("yield"));
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
                        javaLanguageLevel)
                .withParseErrorBehavior(FormatterOptions.ParseErrorBehavior.FAIL);
    }

    private static String readResource(String name) throws IOException, URISyntaxException {
        return Files.readString(
                Path.of(Objects.requireNonNull(FrmtrTest.class.getClassLoader().getResource(name), name).toURI()),
                StandardCharsets.UTF_8);
    }
}
