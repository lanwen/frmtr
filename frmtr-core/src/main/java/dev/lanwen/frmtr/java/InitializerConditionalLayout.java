package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Owns the ternary-initializer break ({@link #conditionalInitializer}) and its over-width probe
 * ({@link #conditionalInitializerLineOverflows}), plus the binary-initializer first-operand-with-{@code =} probe
 * ({@link #binaryInitializerCanKeepFirstOperandWithEquals}) consulted by the same pre-empt tier.
 *
 * <p>This helper hosts the family that prefers breaking a ternary or binary initializer over stranding {@code =} at
 * end of line: the condition-stays-on-the-{@code =}-line shapes (a condition that fits after {@code =}, or a
 * parenthesized condition whose opener fits), the fully-broken-ternary fallback, and the first-binary-operand line
 * probe that decides whether a comment-carrying binary initializer can keep its first operand on the {@code =} line.
 * It claims no ownership of the structural {@link Predicate} that orders these ahead of width policy, nor of the
 * binary/ternary rendering itself, both of which the caller threads in.
 */
final class InitializerConditionalLayout {

    private final FormatterOptions options;

    private final LayoutWidth layoutWidth;

    private final Function<Node, String> compact;

    private final ConditionalExpressionLineProjection conditionalProjection;

    private final Function<ConditionalExpr, Doc> brokenConditionalExpression;

    private final Predicate<ConditionalExpr> shouldBreakBeforeConditionalInitializer;

    InitializerConditionalLayout(
            FormatterOptions options,
            LayoutWidth layoutWidth,
            Function<Node, String> compact,
            Function<Expression, String> compactExpression,
            Function<ConditionalExpr, Doc> brokenConditionalExpression,
            Predicate<ConditionalExpr> shouldBreakBeforeConditionalInitializer
    ) {
        this.options = options;
        this.layoutWidth = layoutWidth;
        this.compact = compact;
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
     * <p>Break-after-{@code =} is a last resort: when {@code NAME = <whole ternary>} overflows we would
     * rather keep the condition on the {@code NAME = <condition>} line and let the ternary own its {@code ?}/{@code :}
     * break than strand {@code =} at end of line. So the condition-stays-on-the-{@code =}-line shapes (a condition that
     * fits after {@code =}, or a parenthesized condition whose opener fits) are chosen ahead of the break-after-{@code =}
     * shapes. Only when the condition genuinely cannot start after {@code =} do we break there — preferring the whole
     * ternary flat on the continuation line when it fits, otherwise the fully-broken ternary under {@code =}. The
     * structural {@link #shouldBreakBeforeConditionalInitializer} rule (a binary condition combined with a binary branch,
     * which reads better wholly under the assignment) is honored first and is independent of this width policy.
     */
    Doc conditionalInitializer(String name, String flatName, ConditionalExpr initializer) {
        String conditionLine = flatName + " = " + compact.apply(initializer.getCondition());
        String compactInitializer = compact.apply(initializer);
        if (shouldBreakBeforeConditionalInitializer.test(initializer)) {
            return Doc.concat(
                Doc.text(name + " ="),
                Doc.indent(Doc.concat(Doc.HARD_LINE, brokenConditionalExpression.apply(initializer)))
            );
        }
        if (layoutWidth.blockStatement(conditionLine + ";") <= options.lineWidth()) {
            return Doc.concat(Doc.text(name + " = "), brokenConditionalExpression.apply(initializer));
        }
        if (parenthesizedConditionalConditionOpenerFits(flatName, initializer)) {
            return Doc.concat(Doc.text(name + " = "), brokenConditionalExpression.apply(initializer));
        }
        // The condition itself will not start after `=`; break there. Keep the whole ternary flat on the continuation
        // line when it fits, otherwise fall back to the fully-broken ternary under `=`.
        if (layoutWidth.continuationStatement(compactInitializer + ";") <= options.lineWidth()) {
            return Doc.concat(
                Doc.text(name + " ="),
                Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.text(compactInitializer)))
            );
        }
        return Doc.concat(
            Doc.text(name + " ="),
            Doc.indent(Doc.concat(Doc.HARD_LINE, brokenConditionalExpression.apply(initializer)))
        );
    }

    private boolean parenthesizedConditionalConditionOpenerFits(String flatName, ConditionalExpr initializer) {
        return initializer.getCondition() instanceof EnclosedExpr
            // Measure the {@code NAME = (} opener at the initializer's true rendered block/type depth rather than a fixed
            // current-column baseline.
            && layoutWidth.nodeLine(initializer, flatName + " = (") <= options.lineWidth();
    }

    /**
     * Keeps a binary initializer from stranding {@code =} when the first operand still fits on the declaration line.
     */
    boolean binaryInitializerCanKeepFirstOperandWithEquals(
            VariableDeclarator variable,
            String declarationPrefix,
            BinaryExpr binaryExpr
    ) {
        // A leading comment before the first operand cannot ride the `=` line ({@code = // note} swallows the operand and
        // re-parses onto its own line), so break after `=` and let the comment lead the first operand.
        if (binaryInitializerHasLeadingFirstOperandComment(binaryExpr)) {
            return false;
        }
        String firstOperand = binaryInitializerFirstOperandLine(binaryExpr);
        return layoutWidth.variableInitializer(
            variable,
            declarationPrefix + variable.getNameAsString() + " = " + firstOperand
        ) <= options.lineWidth();
    }

    private boolean binaryInitializerHasLeadingFirstOperandComment(BinaryExpr binaryExpr) {
        Expression firstOperand = firstBinaryOperand(binaryExpr);
        return isLineCommentBefore(binaryExpr.getComment().orElse(null), firstOperand)
            || isLineCommentBefore(firstOperand.getComment().orElse(null), firstOperand);
    }

    private boolean isLineCommentBefore(Comment comment, Expression operand) {
        return comment instanceof LineComment && CommentIndex.startsBefore(comment, operand);
    }

    private String binaryInitializerFirstOperandLine(BinaryExpr binaryExpr) {
        Expression firstOperand = firstBinaryOperand(binaryExpr);
        if (firstOperand instanceof TextBlockLiteralExpr) {
            return "\"\"\"";
        }
        return compact.apply(firstOperand);
    }

    private Expression firstBinaryOperand(BinaryExpr binaryExpr) {
        Expression left = binaryExpr.getLeft();
        while (left instanceof BinaryExpr leftBinary && leftBinary.getOperator() == binaryExpr.getOperator()) {
            left = leftBinary.getLeft();
        }
        return left;
    }
}
