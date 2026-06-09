package dev.lanwen.frmtr.java;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseStart;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Providers;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.AnnotationDeclaration;
import com.github.javaparser.ast.body.AnnotationMemberDeclaration;
import com.github.javaparser.ast.body.BodyDeclaration;
import com.github.javaparser.ast.stmt.Statement;
import dev.lanwen.frmtr.FormatterException;
import dev.lanwen.frmtr.Frmtr;
import org.junit.jupiter.api.Test;

final class AnnotationDeclarationPrinterTest {
    @Test
    void formatsValidAnnotationMemberSiblingsAroundRawRecoveredMemberGap() {
        String source = """
                @interface Demo {
                    String before();

                    Object broken() default new Object() {
                        void m() {
                            var x = ; // keep raw
                        }
                    };

                    String after() default "ok";

                    int value;
                }
                """;
        CompilationUnit unit = recoveredParseResult(source);
        AnnotationDeclaration declaration = onlyAnnotation(unit);
        BodyDeclaration<?> brokenMember = annotationMember(declaration, "broken");
        Statement recoveredStatement = recoveredStatement(brokenMember);

        String formatted = Frmtr.format(source);

        assertThat(AnnotationDeclarationPrinter.hasRecoverableAnnotationMemberListProblem(declaration)).isTrue();
        assertThat(AnnotationDeclarationPrinter.nearestAnnotationMemberListSibling(recoveredStatement))
                .contains(brokenMember);
        assertThat(JavaFormatter.isSupportedRecovery(recoveredStatement)).isTrue();
        assertThat(formatted).isEqualTo(source);
    }

    @Test
    void rejectsMalformedAnnotationMemberDefaultWhenJavaParserCollapsesCompilationUnit() {
        String source = """
                @interface Demo {
                    String before();
                    String broken() default ;
                    String after();
                }
                """;
        var result = parser().parse(ParseStart.COMPILATION_UNIT, Providers.provider(source));

        Throwable thrown = catchThrowable(() -> Frmtr.format(source));

        assertThat(result.isSuccessful()).isFalse();
        assertThat(result.getResult()).isPresent();
        assertThat(result.getResult().orElseThrow().getParsed()).isEqualTo(Node.Parsedness.UNPARSABLE);
        assertThat(result.getResult().orElseThrow().findAll(AnnotationDeclaration.class)).isEmpty();
        assertThat(thrown)
                .isInstanceOf(FormatterException.class)
                .hasMessageContaining("Unable to parse Java source:")
                .hasMessageContaining("annotation declaration member lists")
                .hasMessageContaining("Unsupported recovered node: CompilationUnit");
    }

    private static Statement recoveredStatement(BodyDeclaration<?> declaration) {
        return declaration.findAll(Statement.class).stream()
                .filter(statement -> statement.getParsed() != Node.Parsedness.PARSED)
                .findFirst()
                .orElseThrow();
    }

    private static BodyDeclaration<?> annotationMember(AnnotationDeclaration declaration, String name) {
        return declaration.getMembers().stream()
                .filter(AnnotationMemberDeclaration.class::isInstance)
                .map(AnnotationMemberDeclaration.class::cast)
                .filter(member -> member.getNameAsString().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static AnnotationDeclaration onlyAnnotation(CompilationUnit unit) {
        return unit.findFirst(AnnotationDeclaration.class).orElseThrow();
    }

    private static CompilationUnit recoveredParseResult(String source) {
        var result = parser().parse(ParseStart.COMPILATION_UNIT, Providers.provider(source));
        assertThat(result.isSuccessful()).isFalse();
        assertThat(result.getResult()).isPresent();
        assertThat(result.getResult().orElseThrow().getParsed()).isEqualTo(Node.Parsedness.PARSED);
        return result.getResult().orElseThrow();
    }

    private static JavaParser parser() {
        return new JavaParser(new ParserConfiguration()
                .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
                .setStoreTokens(true)
                .setAttributeComments(true));
    }
}
