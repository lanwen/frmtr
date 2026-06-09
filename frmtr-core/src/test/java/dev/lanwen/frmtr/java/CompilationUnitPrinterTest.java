package dev.lanwen.frmtr.java;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseStart;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Providers;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.comments.LineComment;
import dev.lanwen.frmtr.FormatterException;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.DocRenderer;
import org.junit.jupiter.api.Test;

final class CompilationUnitPrinterTest {
    @Test
    void formatsValidTopLevelSiblingsAroundRawRecoveredDeclarationGap() {
        String source = """
                // file header
                package dev.example;

                import java.util.List;

                class Before{void method(){before( 1 );}}

                class Broken{void method(){broken( 2 );}} // keep raw

                class After{void method(){after( 3 );}}
                """;
        CompilationUnit unit = parse(source);
        type(unit, "Broken").setParsed(Node.Parsedness.UNPARSABLE);

        String formatted = printRecovered(unit, source);

        assertThat(formatted).isEqualTo("""
                // file header

                package dev.example;

                import java.util.List;

                class Before {

                    void method() {
                        before(1);
                    }
                }

                class Broken{void method(){broken( 2 );}} // keep raw

                class After {

                    void method() {
                        after(3);
                    }
                }
                """);
    }

    @Test
    void reportsRawCommentBoundaryFailuresAsRecoverableTopLevelDeclarationListFailures() {
        String source = """
                class Before {}
                class Broken {}
                class After {}
                """;
        CompilationUnit unit = parse(source);
        LineComment rangeLessComment = new LineComment("range-less");
        unit.addOrphanComment(rangeLessComment);
        type(unit, "Broken").setParsed(Node.Parsedness.UNPARSABLE);

        Throwable thrown = catchThrowable(() -> printRecovered(unit, source));

        assertThat(rangeLessComment.getRange()).isEmpty();
        assertThat(thrown)
                .isInstanceOf(FormatterException.class)
                .hasMessageContaining("Unable to recover Java parse error inside top-level declaration list:")
                .hasMessageContaining("cannot safely account LineComment at unknown range")
                .hasCauseInstanceOf(RecoveredSourceRegions.CrossingCommentBoundaryException.class);
        assertThat(((FormatterException) thrown).internal()).isFalse();
    }

    private static String printRecovered(CompilationUnit unit, String source) {
        return new DocRenderer(FormatterOptions.defaults())
                .render(new JavaPrinter(FormatterOptions.defaults(), new SourceText(source), true).print(unit));
    }

    private static ClassOrInterfaceDeclaration type(CompilationUnit unit, String name) {
        return unit.getClassByName(name).orElseThrow();
    }

    private static CompilationUnit parse(String source) {
        JavaParser parser = new JavaParser(new ParserConfiguration()
                .setStoreTokens(true)
                .setAttributeComments(true));
        return parser.parse(ParseStart.COMPILATION_UNIT, Providers.provider(source))
                .getResult()
                .orElseThrow();
    }
}
