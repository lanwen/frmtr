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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
    void formatsStaticChainLaterBlockLambdaFixtureAtPreferredAndTightWidths() throws Exception {
        String source = readResource("format/static-chain-later-block-lambda/input.java");
        String preferredExpected = readResource("format/static-chain-later-block-lambda/frmtr.output.java");
        String tightExpected = readResource("format/static-chain-later-block-lambda/frmtr-tight.output.java");
        FormatterOptions preferredRootWidth = FormatterOptions.forLayout(
                90,
                FormatterOptions.IndentStyle.SPACE,
                4,
                FormatterOptions.LineEnding.LF,
                true);
        FormatterOptions tightRootWidth = FormatterOptions.forLayout(
                50,
                FormatterOptions.IndentStyle.SPACE,
                4,
                FormatterOptions.LineEnding.LF,
                true);

        String preferredRoot = Frmtr.format(source, preferredRootWidth);
        String tightRoot = Frmtr.format(source, tightRootWidth);

        assertThat(preferredRoot).isEqualTo(preferredExpected);
        assertThat(Frmtr.format(preferredRoot, preferredRootWidth)).isEqualTo(preferredRoot);
        assertThatCode(() -> StaticJavaParser.parse(preferredRoot)).doesNotThrowAnyException();
        assertThat(tightRoot).isEqualTo(tightExpected);
        assertThat(Frmtr.format(tightRoot, tightRootWidth)).isEqualTo(tightRoot);
        assertThatCode(() -> StaticJavaParser.parse(tightRoot)).doesNotThrowAnyException();
    }

    @Test
    void formatsBlockLambdaChainedSuffixFixtureAndIsIdempotent() throws Exception {
        String source = readResource("format/block-lambda-chained-suffix/input.java");
        String expected = readResource("format/block-lambda-chained-suffix/frmtr.output.java");

        String formatted = Frmtr.format(source);

        assertThat(formatted).isEqualTo(expected);
        assertThat(Frmtr.format(formatted)).isEqualTo(formatted);
        assertThatCode(() -> StaticJavaParser.parse(formatted)).doesNotThrowAnyException();
    }

    @Test
    void formatsObjectCreationAssignmentPrefixFixtureAtPreferredAndTightWidths() throws Exception {
        String source = readResource("format/object-creation-assignment-prefix/input.java");
        String preferredExpected = readResource("format/object-creation-assignment-prefix/frmtr.output.java");
        String tightExpected = readResource("format/object-creation-assignment-prefix/frmtr-tight.output.java");
        FormatterOptions preferredWidth = FormatterOptions.forLayout(
                120,
                FormatterOptions.IndentStyle.SPACE,
                4,
                FormatterOptions.LineEnding.LF,
                true);
        FormatterOptions tightWidth = FormatterOptions.forLayout(
                38,
                FormatterOptions.IndentStyle.SPACE,
                4,
                FormatterOptions.LineEnding.LF,
                true);

        String preferred = Frmtr.format(source, preferredWidth);
        String tight = Frmtr.format(source, tightWidth);

        assertThat(preferred).isEqualTo(preferredExpected);
        assertThat(Frmtr.format(preferred, preferredWidth)).isEqualTo(preferred);
        assertThatCode(() -> StaticJavaParser.parse(preferred)).doesNotThrowAnyException();
        assertThat(tight).isEqualTo(tightExpected);
        assertThat(Frmtr.format(tight, tightWidth)).isEqualTo(tight);
        assertThatCode(() -> StaticJavaParser.parse(tight)).doesNotThrowAnyException();
    }

    @Test
    void formatsEnhancedForMethodCallIterableFixtureAndIsIdempotent() throws Exception {
        String source = readResource("format/enhanced-for-method-call-iterable/input.java");
        String expected = readResource("format/enhanced-for-method-call-iterable/frmtr.output.java");
        FormatterOptions options = FormatterOptions.forLayout(
                120,
                FormatterOptions.IndentStyle.SPACE,
                4,
                FormatterOptions.LineEnding.LF,
                true);

        String formatted = Frmtr.format(source, options);

        assertThat(formatted).isEqualTo(expected);
        assertThat(Frmtr.format(formatted, options)).isEqualTo(formatted);
        assertThatCode(() -> StaticJavaParser.parse(formatted)).doesNotThrowAnyException();
    }

    @Test
    void formatsFieldRootSourceMultilineChainFixtureAndIsIdempotent() throws Exception {
        String source = readResource("format/field-root-source-multiline-chain/input.java");
        String expected = readResource("format/field-root-source-multiline-chain/frmtr.output.java");
        FormatterOptions options = FormatterOptions.forLayout(
                120,
                FormatterOptions.IndentStyle.SPACE,
                4,
                FormatterOptions.LineEnding.LF,
                true);

        String formatted = Frmtr.format(source, options);

        assertThat(formatted).isEqualTo(expected);
        assertThat(Frmtr.format(formatted, options)).isEqualTo(formatted);
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
    void formatsCorrectnessDataLossFixtureAndIsIdempotent() throws Exception {
        String source = readResource("format/correctness-data-loss/input.java");
        String expected = readResource("format/correctness-data-loss/frmtr.output.java");
        FormatterOptions options = FormatterOptions.forLayout(
                120,
                FormatterOptions.IndentStyle.SPACE,
                4,
                FormatterOptions.LineEnding.LF,
                true);

        String formatted = Frmtr.format(source, options);

        assertThat(formatted).isEqualTo(expected);
        assertThat(Frmtr.format(formatted, options)).isEqualTo(formatted);
        assertThatCode(() -> assertLatestJavaParses(formatted)).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "{0} @ {1}")
    @CsvSource({
            "annotated-generic-type-width, 120",
            "annotation-array-subtype-list, 120",
            "annotation-text-block-argument, 120",
            "anonymous-object-creation-class-argument, 120",
            "assignment-method-chain, 120",
            "binary-method-call-operand, 120",
            "binary-operator-position-end, 120",
            "binary-operator-position-start, 120",
            "binary-parenthesized-or-condition, 120",
            "binary-return-comments, 120",
            "block-lambda-call-initializers, 40",
            "block-lambda-call-initializers, 120",
            "block-lambda-setup-initializer, 120",
            "chain-comment-ownership, 120",
            "class-literal-qualified-name, 120",
            "constructor-chain-roots, 120",
            "direct-constructor-source-multiline, 120",
            "field-chain-initializer, 120",
            "field-trailing-comments, 120",
            "member-blank-lines, 120",
            "member-comment-spacing, 120",
            "method-annotation-line-comment, 120",
            "method-call-binary-argument, 120",
            "method-call-initializer-opener, 120",
            "method-chain-root-arguments, 120",
            "method-chain-segment-arguments, 120",
            "comment-complex-block-statements, 120",
            "comment-preservation-annotation-array, 120",
            "comment-preservation-leading-statements, 120",
            "comment-preservation-method-arguments, 120",
            "comment-preservation-method-chain-segments, 120",
            "comment-preservation-try-resources, 120",
            "conditional-chain-branch, 120",
            "method-chain-trailing-empty-call-comment, 120",
            "method-chain-trailing-lambda-comment, 120",
            "multiline-if-condition, 120",
            "nested-generic-type-breaks, 120",
            "object-creation-diamond-break, 120",
            "object-creation-statement-argument, 120",
            "array-initializer-spacing, 120",
            "annotated-qualified-types, 120",
            "annotation-interface-declaration, 120",
            "assert-statement-expressions, 120",
            "block-lambda-arrow-parens-always, 120",
            "block-lambda-arrow-parens-avoid, 120",
            "cast-expression-layout, 120",
            "comment-preservation-class-members, 120",
            "for-loop, 120",
            "formatter-pragma-spacing, 120",
            "formatter-ignore-block, 120",
            "formatter-ignore-class-declaration, 120",
            "formatter-ignore-method, 120",
            "formatter-ignore-multiple, 120",
            "formatter-pragma-begin-with-on, 120",
            "formatter-pragma-class, 120",
            "formatter-pragma-end-with-off, 120",
            "formatter-pragma-inside-block, 120",
            "formatter-pragma-method, 120",
            "formatter-pragma-multiple, 120",
            "if-else-chain, 120",
            "initializer-equals-grouping, 120",
            "intellij-idea, 120",
            "interface-and-sealed-type-headers, 120",
            "lambda-expression-argument-opener, 120",
            "marker-annotation-stacks, 120",
            "method-parameter-list-layout, 120",
            "method-reference-expressions, 120",
            "modifier-annotation-placement, 120",
            "module-declarations-directives, 120",
            "module-declarations-mixed-imports, 120",
            "module-declarations-no-imports, 120",
            "module-declarations-non-static-imports, 120",
            "module-declarations-static-imports, 120",
            "object-creation-instantiation-layout, 120",
            "package-imports-mixed-case-type-imports, 120",
            "package-imports-mixed-imports, 120",
            "package-imports-no-imports, 120",
            "package-imports-non-static-imports, 120",
            "package-imports-static-imports, 120",
            "qualified-type-receiver-annotations, 120",
            "record-component-inline-annotations, 120",
            "record-component-line-comment, 120",
            "record-component-spacing, 120",
            "record-implements, 120",
            "require-pragma-format, 120",
            "require-pragma-invalid, 120",
            "require-pragma-supported-marker, 120",
            "return-statement-expressions, 120",
            "multiline-array-initializer-after-equals, 120",
            "nested-object-array-rows, 120",
            "qualified-static-chain-root, 120",
            "return-object-creation-width, 120",
            "return-chain-final-argument, 120",
            "single-member-annotation-array-width, 120",
            "source-multiline-shapes, 120",
            "string-concat-ternary-literal, 120",
            "string-literal-initializer-width, 120",
            "switch-entry-leading-comments, 120",
            "switch-expression-initializer, 120",
            "switch-empty-rules, 120",
            "switch-multiple-unnamed-patterns, 120",
            "switch-statement-rules, 120",
            "synchronized-block, 120",
            "template-expression-string-literals, 120",
            "text-block-concat-initializer-opener, 120",
            "text-block-language-and-escapes, 120",
            "text-block-raw-method-call, 120",
            "throw-object-creation-width, 120",
            "throws-clause-layout, 120",
            "try-resource-layout, 120",
            "type-header-brace-placement, 120",
            "unnamed-class-compilation-unit, 120",
            "unnamed-variables-patterns, 120",
            "variable-declarations, 120",
            "while-do, 120",
            "variable-chain-initializer, 120"
    })
    void formatsLineWidthFixtureAndIsIdempotent(String fixtureName, int lineWidth) throws Exception {
        String fixtureRoot = "format/" + fixtureName + "/";
        String source = readResource(fixtureRoot + "input.java");
        String expected = readResource(fixtureRoot + "frmtr-" + lineWidth + ".output.java");
        FormatterOptions options = FormatterOptions.withJavaLanguageLevel(
                lineWidth,
                FormatterOptions.IndentStyle.SPACE,
                4,
                FormatterOptions.LineEnding.LF,
                true,
                FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE);

        String formatted = Frmtr.format(source, options);

        assertThat(formatted).isEqualTo(expected);
        assertThat(Frmtr.format(formatted, options)).isEqualTo(formatted);
        assertThatCode(() -> assertLatestJavaParses(formatted)).doesNotThrowAnyException();
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
        assertThat(FormatterOptions.defaults().lineWidth()).isEqualTo(FormatterOptions.DEFAULT_LINE_WIDTH);
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
                        + "      firstVeryLongConditionName &&\n"
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
                .contains("a.b(c -> eeeeeeeeee.ffffffffff(\n"
                        + "        gggggggggg,\n"
                        + "        hhhhhhhhhh,\n"
                        + "        iiiiiiiiii,\n"
                        + "        jjjjjjjjjj,\n"
                        + "        kkkkkkkkkk\n"
                        + "    ));");
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
                .contains("aaaaaaaaaa(bbbbbbbbbb -> cccccccccc(\n"
                        + "        \"123456789012345678901234567890123456\"\n"
                        + "    ));");
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
                .contains("a.b(c -> d && eeeeeeeeee.ffffffffff()\n"
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
    void formatsGenericTypeBodyBreaksFixtureAndIsIdempotent() throws Exception {
        String source = readResource("format/generic-type-body-breaks/input.java");
        String expected = readResource("format/generic-type-body-breaks/frmtr.output.java");
        FormatterOptions options = FormatterOptions.withJavaLanguageLevel(
                120,
                FormatterOptions.IndentStyle.SPACE,
                4,
                FormatterOptions.LineEnding.LF,
                true,
                FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE);

        String formatted = Frmtr.format(source, options);

        assertThat(formatted).isEqualTo(expected);
        assertThat(Frmtr.format(formatted, options)).isEqualTo(formatted);
        assertThatCode(() -> assertLatestJavaParses(formatted)).doesNotThrowAnyException();
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
                        + "            text\n"
                        + "            \"\"\" // trailing comment\n"
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
                        + "                    public void print(%s object) {\n"
                        + "                        System.out.println(Objects.toString(object));\n"
                        + "                    }\n"
                        + "                    \"\"\".formatted(type);");
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

        assertLatestJavaParses(formatted);
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

    private static void assertLatestJavaParses(String source) {
        var parser = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_25));
        assertThat(parser.parse(ParseStart.COMPILATION_UNIT, Providers.provider(source)).isSuccessful())
                .isTrue();
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
