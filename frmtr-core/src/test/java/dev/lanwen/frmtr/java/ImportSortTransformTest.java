package dev.lanwen.frmtr.java;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseStart;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Providers;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
final class ImportSortTransformTest {
    @Test
    void reportsTransformResultMetadata() {
        CompilationUnit unit = parse("class Demo {}");

        JavaTransformResult result = new ImportSortTransform().transform(unit);

        assertThat(result.unit()).isSameAs(unit);
        assertThat(result.transformName()).isEqualTo("ImportSortTransform");
    }

    @Test
    void reordersCompilationUnitImportsWithoutReplacingNodes() {
        CompilationUnit unit = parse("""
                package dev.example;

                // z import
                import z.Normal;
                // static import
                import static a.Static;
                import a.Normal;

                class Demo {}
                """);
        ImportDeclaration normalZ = unit.getImport(0);
        ImportDeclaration staticA = unit.getImport(1);
        ImportDeclaration normalA = unit.getImport(2);

        CompilationUnit transformed = withGuardrails("true", () -> new JavaTransformPipeline(List.of(new ImportSortTransform()))
                .transform(unit));

        assertThat(transformed).isSameAs(unit);
        assertThat(unit.getImport(0)).isSameAs(staticA);
        assertThat(unit.getImport(1)).isSameAs(normalA);
        assertThat(unit.getImport(2)).isSameAs(normalZ);
        assertThat(staticA.getParentNode()).hasValueSatisfying(parent -> assertThat(parent).isSameAs(unit));
        assertThat(normalA.getParentNode()).hasValueSatisfying(parent -> assertThat(parent).isSameAs(unit));
        assertThat(normalZ.getParentNode()).hasValueSatisfying(parent -> assertThat(parent).isSameAs(unit));
        assertThat(normalZ.getComment()).hasValueSatisfying(comment -> assertThat(comment.toString().stripTrailing())
                .isEqualTo("// z import"));
        assertThat(staticA.getComment()).hasValueSatisfying(comment -> assertThat(comment.toString().stripTrailing())
                .isEqualTo("// static import"));
    }

    private static CompilationUnit parse(String source) {
        JavaParser parser = new JavaParser(new ParserConfiguration()
                .setStoreTokens(true)
                .setAttributeComments(true));
        return parser.parse(ParseStart.COMPILATION_UNIT, Providers.provider(source))
                .getResult()
                .orElseThrow();
    }

    private static <T> T withGuardrails(String value, GuardrailAction<T> action) {
        String previous = System.getProperty(FormatterGuardrails.ENABLED_PROPERTY);
        try {
            if (value == null) {
                System.clearProperty(FormatterGuardrails.ENABLED_PROPERTY);
            } else {
                System.setProperty(FormatterGuardrails.ENABLED_PROPERTY, value);
            }
            return action.run();
        } finally {
            if (previous == null) {
                System.clearProperty(FormatterGuardrails.ENABLED_PROPERTY);
            } else {
                System.setProperty(FormatterGuardrails.ENABLED_PROPERTY, previous);
            }
        }
    }

    private interface GuardrailAction<T> {
        T run();
    }
}
