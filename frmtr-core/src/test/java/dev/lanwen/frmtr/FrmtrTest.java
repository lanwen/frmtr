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
import org.junit.jupiter.api.Test;

final class FrmtrTest {
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

        FormatterOptions avoid = new FormatterOptions(
                80,
                FormatterOptions.IndentStyle.SPACE,
                2,
                FormatterOptions.LineEnding.LF,
                true,
                false,
                false,
                FormatterOptions.LambdaArrowParens.AVOID,
                FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE);
        FormatterOptions always = new FormatterOptions(
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

        assertThat(Frmtr.format(source, new FormatterOptions(
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
                FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE);

        assertThat(Frmtr.format(source, start))
                .contains("@Annotation(\n"
                        + "    \"This operation with two very long string should break\"\n"
                        + "    + \"in a very nice way\"\n"
                        + "  )");
        assertThat(Frmtr.format(source, end))
                .contains("@Annotation(\n"
                        + "    \"This operation with two very long string should break\" +\n"
                        + "    \"in a very nice way\"\n"
                        + "  )");
    }

    @Test
    void requirePragmaLeavesSourceWithoutLeadingFormatPragmaUnchanged() {
        String source = """
                /**
                 * @surely this is invalid
                 */
                 public class Demo{int value;}\
                """;
        FormatterOptions options = new FormatterOptions(
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
        FormatterOptions options = new FormatterOptions(
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

        Throwable thrown = catchThrowable(() -> Frmtr.format(source));

        assertThat(thrown)
                .isInstanceOf(FormatterException.class)
                .hasMessageContaining("2      void method() {")
                .hasMessageContaining("3          var something =")
                .hasMessageContaining("4      }")
                .hasMessageContaining("----------------------^")
                .hasMessageContaining("(line 3,col 23) Parse error");
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

        Throwable thrown = catchThrowable(() -> Frmtr.format(source));

        assertThat(thrown)
                .isInstanceOf(FormatterException.class)
                .hasMessageContaining(System.lineSeparator()
                        + System.lineSeparator()
                        + "// ..."
                        + System.lineSeparator()
                        + System.lineSeparator())
                .hasMessageContaining("(line 3,col 17) Parse error")
                .hasMessageContaining("(line 6,col 17) Parse error");
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
        FormatterOptions options = new FormatterOptions(
                FormatterOptions.DEFAULT_LINE_WIDTH,
                FormatterOptions.IndentStyle.SPACE,
                FormatterOptions.DEFAULT_INDENT_WIDTH,
                FormatterOptions.LineEnding.LF,
                true,
                FormatterOptions.JavaLanguageLevel.UNSET);

        assertThatThrownBy(() -> Frmtr.format(switchExpressionYieldSource(), options))
                .isInstanceOf(FormatterException.class)
                .hasMessageContaining("yield");
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
}
