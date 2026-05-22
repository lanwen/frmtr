package dev.lanwen.frmtr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
}
