package dev.lanwen.frmtr.java;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseStart;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Providers;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.stmt.BlockStmt;
import dev.lanwen.frmtr.FormatterException;
import dev.lanwen.frmtr.FormatterOptions;
import org.junit.jupiter.api.Test;

final class BlockPrinterTest {
    private static final String SOURCE = """
            class Demo {
                void method() {
                    int value = 1;
                }
            }
            """;

    @Test
    void reportsRawCommentBoundaryFailuresAsRecoverableBlockStatementListFailures() {
        CompilationUnit unit = parse(SOURCE);
        BlockStmt block = methodBlock(unit);
        LineComment rangeLessComment = new LineComment("range-less");
        block.addOrphanComment(rangeLessComment);
        block.setParsed(Node.Parsedness.UNPARSABLE);

        Throwable thrown = catchThrowable(() -> printRecovered(unit));

        assertThat(rangeLessComment.getRange()).isEmpty();
        assertThat(thrown)
                .isInstanceOf(FormatterException.class)
                .hasMessageContaining("Unable to recover Java parse error inside block statement list:")
                .hasMessageContaining("cannot safely account LineComment at unknown range")
                .hasCauseInstanceOf(RecoveredSourceRegions.CrossingCommentBoundaryException.class);
        assertThat(((FormatterException) thrown).internal()).isFalse();
    }

    @Test
    void rejectsRecoveredBlockWhenMappedRangeDoesNotProveBraces() {
        CompilationUnit unit = parse(SOURCE);
        BlockStmt block = methodBlock(unit);
        block.setRange(block.getStatement(0).getRange().orElseThrow());
        block.setParsed(Node.Parsedness.UNPARSABLE);

        Throwable thrown = catchThrowable(() -> printRecovered(unit));

        assertThat(thrown)
                .isInstanceOf(FormatterException.class)
                .hasMessageContaining("Unable to recover Java parse error inside block statement list:")
                .hasMessageContaining("block source range must start with '{' and end with '}'")
                .hasCauseInstanceOf(IllegalArgumentException.class);
        assertThat(((FormatterException) thrown).internal()).isFalse();
    }

    private static void printRecovered(CompilationUnit unit) {
        new JavaPrinter(FormatterOptions.defaults(), new SourceText(SOURCE), true).print(unit);
    }

    private static BlockStmt methodBlock(CompilationUnit unit) {
        return unit.findFirst(MethodDeclaration.class, method -> method.getNameAsString().equals("method"))
                .flatMap(MethodDeclaration::getBody)
                .orElseThrow();
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
