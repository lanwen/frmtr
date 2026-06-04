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
    void formatsEnumConstantLambdaArguments() {
        String source = """
                enum Demo {
                    VALUE(x -> {
                        // testing method
                        return n * 2;
                    }, other),
                }
                """;
        FormatterOptions options = new FormatterOptions(
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

        FormatterOptions options = new FormatterOptions(
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
        FormatterOptions options = new FormatterOptions(
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
        FormatterOptions options = new FormatterOptions(
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
        FormatterOptions options = new FormatterOptions(
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
        FormatterOptions options = new FormatterOptions(
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
        FormatterOptions options = new FormatterOptions(
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
        FormatterOptions options = new FormatterOptions(
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
        FormatterOptions options = new FormatterOptions(
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
        FormatterOptions options = new FormatterOptions(
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

    private static String readResource(String name) throws IOException, URISyntaxException {
        return Files.readString(
                Path.of(Objects.requireNonNull(FrmtrTest.class.getClassLoader().getResource(name), name).toURI()),
                StandardCharsets.UTF_8);
    }
}
