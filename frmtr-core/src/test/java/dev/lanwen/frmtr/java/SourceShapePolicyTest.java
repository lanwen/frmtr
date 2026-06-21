package dev.lanwen.frmtr.java;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseStart;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Providers;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import dev.lanwen.frmtr.FormatterOptions;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link SourceShapePolicy}'s source-shape decisions directly, so the single canonical definitions of
 * "was multiline" and "had a blank line between" are observable without round-tripping a whole golden fixture.
 */
final class SourceShapePolicyTest {

    @Test
    void wasMultilineUsesRangeWhenBeginAndEndLinesDiffer() {
        String source = "class Demo {\n    int value = call(\n        first,\n        second);\n}\n";
        MethodCallExpr call = call(source);

        assertThat(call.getRange()).isPresent();
        assertThat(policy(source).wasMultiline(call)).isTrue();
    }

    @Test
    void wasMultilineUsesRangeWhenNodeStaysOnOneLine() {
        String source = "class Demo {\n    int value = call(first, second);\n}\n";
        MethodCallExpr call = call(source);

        assertThat(call.getRange()).isPresent();
        assertThat(policy(source).wasMultiline(call)).isFalse();
    }

    @Test
    void wasMultilineFallsBackToRawNewlineWhenRangeIsMissing() {
        String multilineSource = "class Demo {\n    int value = call(\n        first,\n        second);\n}\n";
        MethodCallExpr multilineCall = call(multilineSource);
        multilineCall.setRange(null);

        String flatSource = "class Demo {\n    int value = call(first, second);\n}\n";
        MethodCallExpr flatCall = call(flatSource);
        flatCall.setRange(null);

        // With no range the policy must consult the raw token text, which still carries the author's line breaks.
        assertThat(multilineCall.getRange()).isEmpty();
        assertThat(flatCall.getRange()).isEmpty();
        assertThat(policy(multilineSource).wasMultiline(multilineCall)).isTrue();
        assertThat(policy(flatSource).wasMultiline(flatCall)).isFalse();
    }

    @Test
    void wasMultilineRawFallbackIgnoresTheNodesOwnLeadingComment() {
        // The node's own comment spans several lines, but the code token itself stays on one line; the raw fallback must
        // strip that own comment before scanning for a newline so a one-line statement is not misreported as multiline.
        String source = "class Demo {\n    int value =\n        /*\n         * note\n         */ call(first);\n}\n";
        Expression initializer = initializer(source);
        initializer.setRange(null);

        assertThat(initializer.getRange()).isEmpty();
        assertThat(initializer.getComment()).isPresent();
        assertThat(policy(source).wasMultiline(initializer)).isFalse();
    }

    @Test
    void hadBlankLineBetweenIsTrueWhenABlankLineSeparatesTheNodes() {
        String source = "class Demo {\n    int first;\n\n    int second;\n}\n";
        FieldDeclaration first = field(source, 0);
        FieldDeclaration second = field(source, 1);

        assertThat(policy(source).hadBlankLineBetween(first, second)).isTrue();
    }

    @Test
    void hadBlankLineBetweenIsFalseWhenTheNodesAreOnConsecutiveLines() {
        String source = "class Demo {\n    int first;\n    int second;\n}\n";
        FieldDeclaration first = field(source, 0);
        FieldDeclaration second = field(source, 1);

        assertThat(policy(source).hadBlankLineBetween(first, second)).isFalse();
    }

    @Test
    void hadBlankLineBetweenIsFalseWhenEitherNodeHasNoRange() {
        String source = "class Demo {\n    int first;\n\n    int second;\n}\n";
        FieldDeclaration first = field(source, 0);
        FieldDeclaration second = field(source, 1);
        second.setRange(null);

        // Without both ranges the formatter cannot claim the author asked for a blank line.
        assertThat(policy(source).hadBlankLineBetween(first, second)).isFalse();
        assertThat(policy(source).hadBlankLineBetween(second, first)).isFalse();
    }

    @Test
    void hadBlankLineBeforeComparesACallerResolvedBeginLineAgainstThePreviousNode() {
        String source = "class Demo {\n    int first;\n\n\n    int second;\n}\n";
        FieldDeclaration first = field(source, 0);
        int previousEndLine = first.getRange().orElseThrow().end.line;

        // The overload lets callers substitute a comment-aware begin line while the policy keeps the one + 1 rule.
        assertThat(policy(source).hadBlankLineBefore(first, previousEndLine + 1)).isFalse();
        assertThat(policy(source).hadBlankLineBefore(first, previousEndLine + 2)).isTrue();
    }

    private static FieldDeclaration field(String source, int index) {
        return parse(source).findAll(FieldDeclaration.class).get(index);
    }

    private static MethodCallExpr call(String source) {
        return parse(source).findFirst(MethodCallExpr.class).orElseThrow();
    }

    private static Expression initializer(String source) {
        return parse(source)
                .findFirst(FieldDeclaration.class)
                .orElseThrow()
                .getVariable(0)
                .getInitializer()
                .orElseThrow();
    }

    private static SourceShapePolicy policy(String source) {
        FormatterOptions options = FormatterOptions.defaults();
        RawSource rawSource = new RawSource(options);
        return new SourceShapePolicy(
            new SourceText(source),
            rawSource,
            new CompactSourceText(rawSource),
            new JavaCommentPlacementPolicy(),
            options
        );
    }

    private static CompilationUnit parse(String source) {
        JavaParser parser = new JavaParser(
            new ParserConfiguration()
                    .setStoreTokens(true)
                    .setAttributeComments(true)
                    .setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21)
        );
        return parser.parse(ParseStart.COMPILATION_UNIT, Providers.provider(source))
                .getResult()
                .orElseThrow();
    }
}
