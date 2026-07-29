package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Owns the ternary-initializer break ({@link #conditionalInitializer}) and its over-width probe
 * ({@link #conditionalInitializerLineOverflows}), plus the binary-initializer break-after-{@code =} rule
 * ({@link #binaryInitializerMustBreakAfterEquals}) consulted by the same pre-empt tier.
 *
 * <p>This helper hosts the family that prefers breaking a ternary or binary initializer over stranding {@code =} at
 * end of line: the renderer-ranked attach-vs-break-after-{@code =} pair for a ternary initializer, and the structural
 * rule that pins a comment-led binary initializer to the break-after-{@code =} shape.
 * It claims no ownership of the structural {@link Predicate} that orders these ahead of width policy, nor of the
 * binary/ternary rendering itself, both of which the caller threads in.
 */
final class InitializerConditionalLayout {

    private final FormatterOptions options;

    private final LayoutWidth layoutWidth;

    private final ConditionalExpressionLineProjection conditionalProjection;

    private final Function<ConditionalExpr, Doc> brokenConditionalExpression;

    private final Predicate<ConditionalExpr> shouldBreakBeforeConditionalInitializer;

    InitializerConditionalLayout(
            FormatterOptions options,
            LayoutWidth layoutWidth,
            Function<Expression, String> compactExpression,
            Function<ConditionalExpr, Doc> brokenConditionalExpression,
            Predicate<ConditionalExpr> shouldBreakBeforeConditionalInitializer
    ) {
        this.options = options;
        this.layoutWidth = layoutWidth;
        this.conditionalProjection = new ConditionalExpressionLineProjection(compactExpression);
        this.brokenConditionalExpression = brokenConditionalExpression;
        this.shouldBreakBeforeConditionalInitializer = shouldBreakBeforeConditionalInitializer;
    }

    boolean conditionalInitializerLineOverflows(
            VariableDeclarator variable,
            String declarationPrefix,
            ConditionalExpr initializer
    ) {
        String line = declarationPrefix
            + variable.getNameAsString()
            + " = "
            + conditionalProjection.line(initializer)
            + ";";
        return layoutWidth.variableInitializer(variable, line) > options.lineWidth();
    }

    /**
     * Chooses the conditional initializer shape, preferring to break the ternary itself over breaking after {@code =}.
     *
     * <p>Break-after-{@code =} is a last resort: an attach candidate ({@code NAME = <condition>} with the ternary's
     * {@code ?}/{@code :} owning their own breaks below it) is ranked against a break-after-{@code =} candidate by the
     * renderer's true first-line fit, so a condition that only <em>starts</em> after {@code =} (e.g. a parenthesized
     * binary that breaks internally) still attaches. The structural {@link #shouldBreakBeforeConditionalInitializer} rule
     * (a binary condition combined with a binary branch, which reads better wholly under the assignment) is honored
     * first and is independent of this ranking.
     */
    Doc conditionalInitializer(String name, String flatName, ConditionalExpr initializer) {
        if (shouldBreakBeforeConditionalInitializer.test(initializer)) {
            return Doc.concat(
                Doc.text(name + " ="),
                Doc.indent(Doc.concat(Doc.HARD_LINE, brokenConditionalExpression.apply(initializer)))
            );
        }
        // Built once and referenced by both candidates below: rebuilding it per candidate would claim any branch or
        // condition comment a second time.
        Doc broken = brokenConditionalExpression.apply(initializer);
        Doc condition = Doc.findLabelled(broken, ConditionalExpressionPrinter.TERNARY_CONDITION_LABEL)
            .orElseThrow(() -> new IllegalStateException("broken conditional is missing its labeled condition part"));
        Doc branches = Doc.findLabelled(broken, ConditionalExpressionPrinter.TERNARY_BRANCHES_LABEL)
            .orElseThrow(() -> new IllegalStateException("broken conditional is missing its labeled branches part"));
        Doc attach = Doc.concat(Doc.text(name + " = "), condition, branches);
        Doc breakAfterEquals = Doc.concat(
            Doc.text(name + " ="),
            Doc.indent(Doc.concat(Doc.HARD_LINE, condition, branches))
        );
        return Doc.bestFittingFirstLine(List.of(attach, breakAfterEquals), new int[] {1, 0});
    }

    /**
     * Reports that a binary initializer must break after {@code =} whatever the width: a leading comment before the first
     * operand cannot ride the {@code =} line ({@code = // note} swallows the operand and re-parses onto its own line), so
     * the comment has to lead the first operand on its own continuation line.
     */
    boolean binaryInitializerMustBreakAfterEquals(BinaryExpr binaryExpr) {
        Expression firstOperand = firstBinaryOperand(binaryExpr);
        return isLineCommentBefore(binaryExpr.getComment().orElse(null), firstOperand)
            || isLineCommentBefore(firstOperand.getComment().orElse(null), firstOperand);
    }

    private boolean isLineCommentBefore(Comment comment, Expression operand) {
        return comment instanceof LineComment && CommentIndex.startsBefore(comment, operand);
    }

    private Expression firstBinaryOperand(BinaryExpr binaryExpr) {
        Expression left = binaryExpr.getLeft();
        while (left instanceof BinaryExpr leftBinary && leftBinary.getOperator() == binaryExpr.getOperator()) {
            left = leftBinary.getLeft();
        }
        return left;
    }
}
