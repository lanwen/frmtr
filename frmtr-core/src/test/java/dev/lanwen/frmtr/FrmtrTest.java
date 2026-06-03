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

                import java.util.List;

                import static java.util.Collections.emptyList;

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
    void defaultsToLatestAvailableJavaLanguageLevel() {
        assertThat(FormatterOptions.defaults().javaLanguageLevel())
                .isEqualTo(FormatterOptions.JavaLanguageLevel.LATEST_AVAILABLE);
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
