package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.comments.BlockComment;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.expr.Expression;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Renders expressions once statement or statement-switch rendering has placed them in a parenthesized control condition.
 *
 * <p>This helper owns the condition-specific boundary between compact source text and broken expression docs,
 * including the block-comment placement rules that preserve source shape inside condition parentheses. The boundary
 * exists because while, do-while, synchronized, and statement-switch selectors all need one condition layout policy after
 * their caller has already chosen the surrounding keyword, body, and statement separator behavior.
 *
 * <p>{@link JavaPrinter} still owns broad expression dispatch, raw-source normalization, and width calculation policy.
 * {@link StatementPrinter} owns ordinary statement grammar, and {@link SwitchPrinter} owns statement-switch selector
 * placement; this helper only returns the condition expression text or docs that fit between the already-decided
 * parentheses.
 */
final class ControlConditionPrinter {
    private final CommentTracker comments;
    private final FormatterOptions options;
    private final Function<Expression, String> compact;
    private final Function<Expression, String> compactWithoutOwnComment;
    private final Function<Expression, Doc> brokenExpressionLines;
    private final ToIntFunction<String> currentIndentedWidth;

    ControlConditionPrinter(
            CommentTracker comments,
            FormatterOptions options,
            Function<Expression, String> compact,
            Function<Expression, String> compactWithoutOwnComment,
            Function<Expression, Doc> brokenExpressionLines,
            ToIntFunction<String> currentIndentedWidth) {
        this.comments = comments;
        this.options = options;
        this.compact = compact;
        this.compactWithoutOwnComment = compactWithoutOwnComment;
        this.brokenExpressionLines = brokenExpressionLines;
        this.currentIndentedWidth = currentIndentedWidth;
    }

    /**
     * Renders a parenthesized control condition without deciding the surrounding statement grammar.
     *
     * <p>The compact path includes attached block comments because conditions with a block comment before the value
     * should keep the comment visible inside the parentheses. When the condition no longer fits, the caller-provided
     * broken expression layout is used so binary conditions keep the same continuation policy as the rest of the
     * formatter.
     */
    Doc controlCondition(Expression expression) {
        String flat = compactWithOwnBlockComment(expression);
        if (currentIndentedWidth.applyAsInt("(" + flat + ") {}") <= options.lineWidth()) {
            return Doc.text("(" + flat + ")");
        }
        return Doc.concat(
                Doc.text("("),
                Doc.indent(Doc.concat(Doc.HARD_LINE, brokenExpressionLines.apply(expression))),
                Doc.HARD_LINE,
                Doc.text(")"));
    }

    /**
     * Builds compact condition text while preserving where an attached block comment appeared in source.
     *
     * <p>JavaParser attaches both block comments before a condition value and block comments after that value as an own
     * comment on the expression. A normal compact clone would erase that distinction, so this fork compares source
     * ranges and places the comment before or after the expression text to keep that visible condition shape.
     */
    String compactWithOwnBlockComment(Expression expression) {
        Optional<Comment> ownComment = expression.getComment().filter(BlockComment.class::isInstance);
        if (ownComment.isEmpty()) {
            return compact.apply(expression);
        }
        Comment comment = ownComment.orElseThrow();
        String commentText = commentText(comments.comment(comment));
        String expressionText = compactWithoutOwnComment.apply(expression);
        return conditionCommentStartsBeforeExpression(expression, comment)
                ? commentText + " " + expressionText
                : expressionText + " " + commentText;
    }

    private boolean conditionCommentStartsBeforeExpression(Expression condition, Comment comment) {
        return CommentIndex.startsBefore(comment, condition);
    }

    private String commentText(Doc comment) {
        if (comment instanceof Doc.Text text) {
            return text.value();
        }
        return "";
    }
}
