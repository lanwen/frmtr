package dev.lanwen.frmtr.java;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseStart;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Providers;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.comments.LineComment;
import dev.lanwen.frmtr.FormatterException;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.DocRenderer;
import org.junit.jupiter.api.Test;

final class MemberBlockPrinterTest {

    @Test
    void formatsValidClassMemberSiblingsAroundRawRecoveredMemberGap() {
        String source = """
                class Demo { // keep brace
                    int before=1;
                    int broken=2; // keep raw
                    int after=3;
                }
                """;
        CompilationUnit unit = parse(source);
        field(unit, "broken").setParsed(Node.Parsedness.UNPARSABLE);

        String formatted = printRecovered(unit, source);

        assertThat(formatted).isEqualTo(
            """
                class Demo {

                    // keep brace
                    int before = 1;
                    int broken=2; // keep raw
                    int after = 3;
                }
                """
        );
    }

    @Test
    void formatsValidRecordMemberSiblingsAroundRawRecoveredMemberGap() {
        String source = """
                record Demo() {
                    void before(){before( 1 );}
                    void broken(){broken( 2 );} // keep raw
                    void after(){after( 3 );}
                }
                """;
        CompilationUnit unit = parse(source);
        method(unit, "broken").setParsed(Node.Parsedness.UNPARSABLE);

        String formatted = printRecovered(unit, source);

        assertThat(formatted).isEqualTo(
            """
                record Demo() {
                    void before() {
                        before(1);
                    }
                    void broken(){broken( 2 );} // keep raw
                    void after() {
                        after(3);
                    }
                }
                """
        );
    }

    @Test
    void reportsRawCommentBoundaryFailuresAsRecoverableMemberDeclarationListFailures() {
        String source = """
                class Demo {
                    int before=1;
                    int broken=2;
                    int after=3;
                }
                """;
        CompilationUnit unit = parse(source);
        ClassOrInterfaceDeclaration type = onlyClass(unit);
        LineComment rangeLessComment = new LineComment("range-less");
        type.addOrphanComment(rangeLessComment);
        field(unit, "broken").setParsed(Node.Parsedness.UNPARSABLE);

        Throwable thrown = catchThrowable(() -> printRecovered(unit, source));

        assertThat(rangeLessComment.getRange()).isEmpty();
        assertThat(thrown)
                .isInstanceOf(FormatterException.class)
                .hasMessageContaining("Unable to recover Java parse error inside member declaration list:")
                .hasMessageContaining("cannot safely account LineComment at unknown range")
                .hasCauseInstanceOf(RecoveredSourceRegions.CrossingCommentBoundaryException.class);
        assertThat(((FormatterException) thrown).internal()).isFalse();
    }

    private static String printRecovered(CompilationUnit unit, String source) {
        return new DocRenderer(FormatterOptions.defaults()).render(
            new JavaPrinter(FormatterOptions.defaults(), new SourceText(source), true).print(unit)
        );
    }

    private static BodyDeclaration<?> field(CompilationUnit unit, String name) {
        return onlyClass(unit)
                .getFields()
                .stream()
                .filter(field -> field.getVariables().stream().anyMatch(
                        variable -> variable.getNameAsString().equals(name)
                ))
                .findFirst()
                .orElseThrow();
    }

    private static BodyDeclaration<?> method(CompilationUnit unit, String name) {
        return onlyRecord(unit).getMethodsByName(name).getFirst();
    }

    private static ClassOrInterfaceDeclaration onlyClass(CompilationUnit unit) {
        return unit.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();
    }

    private static RecordDeclaration onlyRecord(CompilationUnit unit) {
        return unit.findFirst(RecordDeclaration.class).orElseThrow();
    }

    private static CompilationUnit parse(String source) {
        JavaParser parser = new JavaParser(
            new ParserConfiguration()
                    .setStoreTokens(true)
                    .setAttributeComments(true)
        );
        return parser.parse(ParseStart.COMPILATION_UNIT, Providers.provider(source))
                .getResult()
                .orElseThrow();
    }
}
