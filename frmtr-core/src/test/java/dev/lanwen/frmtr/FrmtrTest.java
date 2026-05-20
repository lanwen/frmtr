package dev.lanwen.frmtr;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

        assertEquals("""
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
                """, formatted);
        assertEquals(formatted, Frmtr.format(formatted));
        assertDoesNotThrow(() -> StaticJavaParser.parse(formatted));
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

        assertEquals("""
                package dev.example;

                // demo type
                class Demo {
                    // value comment
                    int value;
                }
                """, formatted);
    }

    @Test
    void rejectsInvalidJava() {
        assertThrows(FormatterException.class, () -> Frmtr.format("class {"));
    }
}
