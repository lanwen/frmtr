package dev.lanwen.frmtr.java;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseStart;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Providers;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import dev.lanwen.frmtr.Frmtr;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import dev.lanwen.frmtr.doc.DocRenderer;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link JavaPrinter#explodedArgumentList} (delegating to {@link MethodCallPrinter#explodedArgumentList}) as a
 * standalone reproduction of the method-call ladder's generic exploded shape, ahead of a future caller (a chain
 * segment) reusing it directly instead of re-deriving the shape from a {@code MethodCallExpr}.
 */
final class MethodCallPrinterExplodedArgumentListTest {

    private static final String SOURCE = """
        class RetryDispatcher {
            void dispatch() {
                notifyRetryHandlers(RetryPolicy.of(firstDelayMillis, secondDelayMillis, thirdDelayMillis, fourthDelayMillis, fifthDelayMillis), listener);
            }
        }
        """;

    @Test
    void reproducesLadderExplodedShapeByteForByteUnderDirectCall() {
        String ladderOutput = Frmtr.format(SOURCE);
        String dedentedLadderBlock = dedentedStatementBlock(ladderOutput, "notifyRetryHandlers(");

        CompilationUnit unit = parse(SOURCE);
        MethodCallExpr call = onlyMethodCall(unit, "notifyRetryHandlers");
        JavaPrinter printer = new JavaPrinter(FormatterOptions.defaults(), new SourceText(SOURCE), false);
        printer.print(unit);

        Doc direct = printer.explodedArgumentList("notifyRetryHandlers", call.getArguments(), ";", MethodCallBreakMode.AUTO);
        String directRendered = new DocRenderer(FormatterOptions.defaults()).render(direct).stripTrailing();

        assertThat(dedentedLadderBlock)
                .isEqualTo(directRendered)
                .contains("RetryPolicy.of(", "listener", ");");
    }

    /**
     * Extracts the statement's own rendered lines starting at {@code marker} through its closing {@code );} and
     * strips the first line's leading indentation from every line, so the block reads the same regardless of the
     * enclosing class/method nesting depth the real ladder embeds it at.
     */
    private static String dedentedStatementBlock(String output, String marker) {
        List<String> lines = output.lines().toList();
        int start = -1;
        for (int index = 0; index < lines.size(); index++) {
            if (lines.get(index).contains(marker)) {
                start = index;
                break;
            }
        }
        if (start < 0) {
            throw new AssertionError("marker not found: " + marker);
        }
        String firstLine = lines.get(start);
        String baseIndent = firstLine.substring(0, firstLine.length() - firstLine.stripLeading().length());
        StringBuilder block = new StringBuilder();
        for (int index = start; index < lines.size(); index++) {
            String line = lines.get(index);
            String dedented = line.startsWith(baseIndent) ? line.substring(baseIndent.length()) : line;
            if (!block.isEmpty()) {
                block.append('\n');
            }
            block.append(dedented);
            if (line.stripLeading().equals(");")) {
                break;
            }
        }
        return block.toString();
    }

    private static MethodCallExpr onlyMethodCall(CompilationUnit unit, String name) {
        return unit.findAll(MethodDeclaration.class)
                .stream()
                .flatMap(declaration -> declaration.findAll(MethodCallExpr.class).stream())
                .filter(call -> call.getNameAsString().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static CompilationUnit parse(String source) {
        JavaParser parser = new JavaParser(
            new ParserConfiguration()
                    .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
                    .setStoreTokens(true)
                    .setAttributeComments(true)
        );
        return parser.parse(ParseStart.COMPILATION_UNIT, Providers.provider(source))
                .getResult()
                .orElseThrow();
    }
}
