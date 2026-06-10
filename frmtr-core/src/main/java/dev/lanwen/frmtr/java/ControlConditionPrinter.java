package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.comments.BlockComment;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.InstanceOfExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Renders expressions once statement or statement-switch rendering has placed them in a parenthesized control condition.
 *
 * <p>This helper owns the condition-specific boundary between compact source text and broken expression docs,
 * including the block-comment placement rules that preserve source shape inside condition parentheses. The boundary
 * exists because if, while, do-while, synchronized, and statement-switch selectors all need one condition layout policy
 * after their caller has already chosen the surrounding keyword, body, and statement separator behavior.
 *
 * <p>{@link JavaPrinter} still owns broad expression dispatch, raw-source normalization, and width calculation policy.
 * {@link StatementPrinter} owns ordinary statement grammar, and {@link SwitchPrinter} owns statement-switch selector
 * placement; this helper only returns the condition expression text or docs that fit between the already-decided
 * parentheses.
 */
final class ControlConditionPrinter {
    private final CommentTracker comments;
    private final RawSource rawSource;
    private final FormatterOptions options;
    private final Function<Expression, Doc> expressionRenderer;
    private final Function<Expression, String> compact;
    private final Function<List<? extends Node>, String> compactJoin;
    private final Function<Expression, String> compactWithoutOwnComment;
    private final Predicate<Expression> expressionHasParenthesizedNestedBinary;
    private final Function<Expression, Doc> brokenExpressionLines;
    private final ToIntFunction<String> currentIndentedWidth;
    private final ToIntFunction<String> blockStatementWidth;

    ControlConditionPrinter(
            CommentTracker comments,
            RawSource rawSource,
            FormatterOptions options,
            Function<Expression, Doc> expressionRenderer,
            Function<Expression, String> compact,
            Function<List<? extends Node>, String> compactJoin,
            Function<Expression, String> compactWithoutOwnComment,
            Predicate<Expression> expressionHasParenthesizedNestedBinary,
            Function<Expression, Doc> brokenExpressionLines,
            ToIntFunction<String> currentIndentedWidth,
            ToIntFunction<String> blockStatementWidth) {
        this.comments = comments;
        this.rawSource = rawSource;
        this.options = options;
        this.expressionRenderer = expressionRenderer;
        this.compact = compact;
        this.compactJoin = compactJoin;
        this.compactWithoutOwnComment = compactWithoutOwnComment;
        this.expressionHasParenthesizedNestedBinary = expressionHasParenthesizedNestedBinary;
        this.brokenExpressionLines = brokenExpressionLines;
        this.currentIndentedWidth = currentIndentedWidth;
        this.blockStatementWidth = blockStatementWidth;
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
        return brokenCondition(expression);
    }

    /**
     * Renders the parenthesized condition for an {@code if} statement after the statement printer has selected if/else
     * grammar.
     *
     * <p>The width gate includes the {@code if} keyword and an empty block because if conditions have a slightly wider
     * surrounding line than loop tails. Source-multiline {@code instanceof && ...} conditions intentionally keep a broken
     * operand layout even when the compact condition would fit.
     */
    Doc ifCondition(Expression expression) {
        Optional<Doc> commented = commentedIfCondition(expression);
        if (commented.isPresent()) {
            return commented.orElseThrow();
        }
        if (sourceMultilineInstanceofAndCondition(expression)) {
            return brokenCondition(expression);
        }
        String flat = compact.apply(expression);
        if (blockStatementWidth.applyAsInt("if (" + flat + ") {}") <= options.lineWidth()) {
            if (expressionHasParenthesizedNestedBinary.test(expression)) {
                return Doc.concat(Doc.text("("), expressionRenderer.apply(expression), Doc.text(")"));
            }
            return Doc.text("(" + flat + ")");
        }
        if (expression instanceof MethodCallExpr methodCall) {
            Optional<Doc> brokenMethodCall = brokenMethodCallCondition(methodCall);
            if (brokenMethodCall.isPresent()) {
                return brokenMethodCall.orElseThrow();
            }
        }
        return brokenCondition(expression);
    }

    private Optional<Doc> brokenMethodCallCondition(MethodCallExpr expression) {
        if (expression.getArguments().isEmpty() || !expression.getAllContainedComments().isEmpty()) {
            return Optional.empty();
        }
        String prefix = methodCallPrefix(expression);
        if (blockStatementWidth.applyAsInt("if (" + prefix + "(") > options.lineWidth()) {
            return Optional.empty();
        }
        Doc argumentLines = Doc.join(Doc.concat(Doc.text(","), Doc.HARD_LINE), expression.getArguments().stream()
                .map(expressionRenderer)
                .toList());
        return Optional.of(Doc.concat(
                Doc.text("(" + prefix + "("),
                Doc.indent(Doc.indent(Doc.concat(Doc.HARD_LINE, argumentLines))),
                Doc.indent(Doc.concat(
                        Doc.HARD_LINE,
                        Doc.text("))")))));
    }

    private String methodCallPrefix(MethodCallExpr expression) {
        return expression.getScope().map(scope -> compact.apply(scope) + ".").orElse("")
                + expression.getTypeArguments()
                        .map(typeArguments -> "<" + compactJoin.apply(typeArguments) + ">")
                        .orElse("")
                + expression.getNameAsString();
    }

    private Doc brokenCondition(Expression expression) {
        return Doc.concat(
                Doc.text("("),
                Doc.indent(Doc.concat(Doc.HARD_LINE, brokenExpressionLines.apply(expression))),
                Doc.HARD_LINE,
                Doc.text(")"));
    }

    private Optional<Doc> commentedIfCondition(Expression condition) {
        Optional<Comment> ownComment = condition.getComment();
        if (ownComment.filter(LineComment.class::isInstance).isPresent()) {
            Comment comment = ownComment.orElseThrow();
            Doc printedComment = comments.comment(comment);
            Doc conditionDoc = conditionCommentStartsBeforeExpression(condition, comment)
                    ? Doc.join(Doc.HARD_LINE, List.of(printedComment, Doc.text(compactWithoutOwnComment.apply(condition))))
                    : Doc.text(compactWithoutOwnComment.apply(condition) + " " + commentText(printedComment));
            return Optional.of(Doc.concat(
                    Doc.text("("),
                    Doc.indent(Doc.concat(Doc.HARD_LINE, conditionDoc)),
                    Doc.HARD_LINE,
                    Doc.text(")")));
        }
        if (ownComment.filter(BlockComment.class::isInstance).isPresent()) {
            Comment comment = ownComment.orElseThrow();
            String text = commentText(comments.comment(comment));
            String expressionText = compactWithoutOwnComment.apply(condition);
            String conditionText = conditionCommentStartsBeforeExpression(condition, comment)
                    ? text + " " + expressionText
                    : expressionText + " " + text;
            return Optional.of(Doc.text("(" + conditionText + ")"));
        }
        Doc trailingBlock = trailingBlockCommentBeforeCloseParen(condition);
        if (trailingBlock != Doc.EMPTY) {
            return Optional.of(Doc.text("(" + compact.apply(condition) + " " + commentText(trailingBlock) + ")"));
        }
        return Optional.empty();
    }

    private boolean sourceMultilineInstanceofAndCondition(Expression condition) {
        return condition instanceof BinaryExpr binaryExpr
                && binaryExpr.getOperator() == BinaryExpr.Operator.AND
                && binaryExpr.getLeft() instanceof InstanceOfExpr
                && rawSource.rawWithoutOwnComment(condition).contains("\n");
    }

    private Doc trailingBlockCommentBeforeCloseParen(Expression condition) {
        return condition.getParentNode()
                .stream()
                .flatMap(parent -> parent.getAllContainedComments().stream())
                .filter(BlockComment.class::isInstance)
                .filter(comment -> comment.getCommentedNode()
                        .map(BlockStmt.class::isInstance)
                        .orElse(false))
                .filter(comment -> CommentIndex.startsImmediatelyAfterNodeOnSameLine(condition, comment))
                .findFirst()
                .map(comments::comment)
                .orElse(Doc.EMPTY);
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
