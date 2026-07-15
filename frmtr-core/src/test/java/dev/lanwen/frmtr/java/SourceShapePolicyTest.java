package dev.lanwen.frmtr.java;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseStart;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Providers;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import dev.lanwen.frmtr.FormatterOptions;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link SourceShapePolicy}'s surviving {@code FIXPOINT_SAFE} source-shape decisions directly (blank-line
 * preservation, the width-fit gate, and the comment-presence gate), so their single canonical definitions are observable
 * without round-tripping a whole golden fixture. The six {@code RETIREMENT_TARGET} reads
 * ({@code wasMultiline}/{@code selectorBrokeAfter}/…) were deleted in the D3 flip, so this test no longer exercises them.
 */
final class SourceShapePolicyTest {

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
    void fitsOnOneLineIsTrueWhenIndentedCompactWidthIsWithinTheLineWidth() {
        String source = "class Demo {\n    int value = call(first, second);\n}\n";
        MethodCallExpr call = call(source);

        // "call(first, second)" is 19 chars; with a 4-space indent the gate measures 23 against a 30-wide line.
        SourceShapePolicy policy = policy(source, FormatterOptions.defaults().withLineWidth(30));

        assertThat(policy.fitsOnOneLine(call, text -> 4 + text.length())).isTrue();
    }

    @Test
    void fitsOnOneLineIsFalseWhenIndentedCompactWidthOverflowsTheLineWidth() {
        String source = "class Demo {\n    int value = call(first, second);\n}\n";
        MethodCallExpr call = call(source);

        // Same node, but now the 23-wide indented compact text overflows a 20-wide line.
        SourceShapePolicy policy = policy(source, FormatterOptions.defaults().withLineWidth(20));

        assertThat(policy.fitsOnOneLine(call, text -> 4 + text.length())).isFalse();
    }

    @Test
    void fitsOnOneLineAppliesTheCallerSuppliedIndentFunction() {
        String source = "class Demo {\n    int value = call(first, second);\n}\n";
        MethodCallExpr call = call(source);

        // The same node and line width flip outcome based purely on the indent the caller charges, proving the gate runs
        // the per-site indented-width function rather than measuring the bare compact text.
        SourceShapePolicy policy = policy(source, FormatterOptions.defaults().withLineWidth(20));

        assertThat(policy.fitsOnOneLine(call, text -> text.length())).isTrue();
        assertThat(policy.fitsOnOneLine(call, text -> 4 + text.length())).isFalse();
    }

    @Test
    void hasContainedCommentsIsTrueForANodeThatEnclosesAComment() {
        // The method body encloses a block comment, so the method declaration carries a contained comment and a
        // compact/source-shaped layout of it would risk dropping that comment content.
        String source = "class Demo {\n    void run() {\n        /* note */\n        call();\n    }\n}\n";
        CompilationUnit unit = parse(source);
        MethodDeclaration method = unit.findFirst(MethodDeclaration.class).orElseThrow();

        assertThat(policyForCommentRun(source, unit).hasContainedComments(method)).isTrue();
    }

    @Test
    void hasContainedCommentsIsFalseForACommentFreeNode() {
        String source = "class Demo {\n    void run() {\n        call();\n    }\n}\n";
        CompilationUnit unit = parse(source);
        MethodDeclaration method = unit.findFirst(MethodDeclaration.class).orElseThrow();

        assertThat(policyForCommentRun(source, unit).hasContainedComments(method)).isFalse();
    }

    @Test
    void hasContainedCommentsScansDetachedClonesOutsideTheRunIndex() {
        String source = "class Demo { void run() { call(/* note */ value); } }";
        CompilationUnit unit = parse(source);
        MethodCallExpr detached = unit.findFirst(MethodCallExpr.class).orElseThrow().clone();

        assertThat(policyForCommentRun(source, unit).hasContainedComments(detached)).isTrue();
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

    private static SourceShapePolicy policy(String source) {
        return policy(source, FormatterOptions.defaults());
    }

    private static SourceShapePolicy policy(String source, FormatterOptions options) {
        RawSource rawSource = new RawSource(options);
        return new SourceShapePolicy(
            new SourceText(source),
            rawSource,
            new CompactSourceText(rawSource),
            new JavaCommentPlacementPolicy(),
            options
        );
    }

    /**
     * Builds a policy with the run index started for original nodes; detached nodes use the JavaParser fallback.
     */
    private static SourceShapePolicy policyForCommentRun(String source, CompilationUnit unit) {
        FormatterOptions options = FormatterOptions.defaults();
        RawSource rawSource = new RawSource(options);
        JavaCommentPlacementPolicy commentPolicy = new JavaCommentPlacementPolicy();
        commentPolicy.startRun(unit);
        return new SourceShapePolicy(
            new SourceText(source),
            rawSource,
            new CompactSourceText(rawSource),
            commentPolicy,
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
