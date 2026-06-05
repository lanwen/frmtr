package dev.lanwen.frmtr.java;

import com.github.javaparser.Range;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Renders Java conditional expressions and their assignment or initializer-specific break decisions.
 *
 * <p>This helper owns the ternary decision tree around flat versus broken {@code ?:} output, line comments attached near
 * {@code ?} and {@code :}, nested conditional branches, and binary-condition wrapping when a conditional expression is
 * used as an assignment value or variable initializer. The boundary exists because {@link ConditionalExpr} nodes appear
 * in several caller contexts, but the ternary-specific comment and width decisions are the same once those callers have
 * decided that they need conditional expression formatting.
 *
 * <p>{@link JavaPrinter} still owns general expression dispatch, assignment dispatch, raw source and pragma gates, field
 * declaration layout, and binary-expression policy. This helper receives those decisions as callbacks and only chooses
 * the shape of the conditional expression itself. Representative fixture pairs for this boundary include
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/conditional-expression/spaces/input.java} with
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/conditional-expression/spaces/prettier.output.java},
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/conditional-expression/tabs/input.java} with
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/conditional-expression/tabs/prettier.output.java},
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/variables/input.java} with
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/variables/frmtr.output.java}, and
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/expressions/input.java} with
 * {@code frmtr-core/src/test/resources/format/prettier-java/unit-test/expressions/prettier.output.java}.
 */
final class ConditionalExpressionPrinter {
    private final JavaFormatter.CommentTracker comments;
    private final FormatterOptions options;
    private final Function<Expression, Doc> expressionRenderer;
    private final Function<Expression, Doc> expressionWithoutOwnCommentRenderer;
    private final Function<Node, String> compact;
    private final ToIntFunction<String> currentIndentedWidth;
    private final ToIntFunction<String> blockStatementWidth;
    private final ToIntFunction<String> continuationStatementWidth;
    private final BiFunction<Expression, Boolean, Doc> binaryExpressionLinesRenderer;
    private final BiFunction<Expression, Boolean, Doc> nestedBinaryExpressionLinesRenderer;
    private final Predicate<Expression> expressionHasParenthesizedNestedBinary;

    ConditionalExpressionPrinter(
            JavaFormatter.CommentTracker comments,
            FormatterOptions options,
            Function<Expression, Doc> expressionRenderer,
            Function<Expression, Doc> expressionWithoutOwnCommentRenderer,
            Function<Node, String> compact,
            ToIntFunction<String> currentIndentedWidth,
            ToIntFunction<String> blockStatementWidth,
            ToIntFunction<String> continuationStatementWidth,
            BiFunction<Expression, Boolean, Doc> binaryExpressionLinesRenderer,
            BiFunction<Expression, Boolean, Doc> nestedBinaryExpressionLinesRenderer,
            Predicate<Expression> expressionHasParenthesizedNestedBinary) {
        this.comments = comments;
        this.options = options;
        this.expressionRenderer = expressionRenderer;
        this.expressionWithoutOwnCommentRenderer = expressionWithoutOwnCommentRenderer;
        this.compact = compact;
        this.currentIndentedWidth = currentIndentedWidth;
        this.blockStatementWidth = blockStatementWidth;
        this.continuationStatementWidth = continuationStatementWidth;
        this.binaryExpressionLinesRenderer = binaryExpressionLinesRenderer;
        this.nestedBinaryExpressionLinesRenderer = nestedBinaryExpressionLinesRenderer;
        this.expressionHasParenthesizedNestedBinary = expressionHasParenthesizedNestedBinary;
    }

    /**
     * Chooses the shape for an assignment whose value is a conditional expression.
     *
     * <p>When the condition itself is structurally complex, the whole conditional moves under the assignment operator so
     * the reader sees the assignment first and then the ternary tree. If only the full expression is too wide, but the
     * target, operator, and condition still fit, the condition stays after {@code =} and only the {@code ?} and
     * {@code :} branches break below it.
     */
    Optional<Doc> assignmentWithConditionalValue(AssignExpr assignExpr, ConditionalExpr conditionalExpr) {
        if (shouldBreakBeforeConditionalInitializer(conditionalExpr)
                || shouldBreakBeforeConditionalAssignment(conditionalExpr)) {
            return Optional.of(Doc.concat(
                    expressionRenderer.apply(assignExpr.getTarget()),
                    Doc.text(" " + assignExpr.getOperator().asString()),
                    Doc.indent(Doc.concat(Doc.HARD_LINE, conditionalExpression(conditionalExpr, true)))));
        }
        String conditionLine = compact.apply(assignExpr.getTarget()) + " "
                + assignExpr.getOperator().asString()
                + " "
                + compact.apply(conditionalExpr.getCondition())
                + ";";
        if (blockStatementWidth.applyAsInt(conditionLine) <= options.lineWidth()) {
            return Optional.of(Doc.concat(
                    expressionRenderer.apply(assignExpr.getTarget()),
                    Doc.text(" " + assignExpr.getOperator().asString() + " "),
                    conditionalExpression(conditionalExpr, true)));
        }
        return Optional.empty();
    }

    /**
     * Reports whether initializer callers should start the whole conditional on the next line after {@code =}.
     *
     * <p>A binary condition combined with a binary branch creates a multi-part ternary where keeping the condition after
     * the initializer name makes the tree harder to scan; callers use this fork to put the whole conditional under the
     * assignment instead.
     */
    boolean shouldBreakBeforeConditionalInitializer(ConditionalExpr initializer) {
        return initializer.getCondition() instanceof BinaryExpr
                && (initializer.getThenExpr() instanceof BinaryExpr || initializer.getElseExpr() instanceof BinaryExpr);
    }

    private boolean shouldBreakBeforeConditionalAssignment(ConditionalExpr conditionalExpr) {
        return conditionalExpr.getCondition() instanceof BinaryExpr binaryExpr
                && binaryExpr.findAll(MethodCallExpr.class).stream().findAny().isPresent();
    }

    Doc conditionalExpression(ConditionalExpr expression) {
        return conditionalExpression(expression, false);
    }

    /**
     * Prints a conditional expression, preserving flat output until width, nesting, comments, or caller context require
     * the broken ternary shape.
     */
    Doc conditionalExpression(ConditionalExpr expression, boolean forceBreak) {
        Optional<Doc> commented = commentedConditionalExpression(expression);
        if (commented.isPresent()) {
            return commented.orElseThrow();
        }
        String flat = compact.apply(expression);
        if (!forceBreak && currentIndentedWidth.applyAsInt(flat) <= options.lineWidth()) {
            if (expressionHasParenthesizedNestedBinary.test(expression)) {
                return Doc.concat(
                        conditionalCondition(expression),
                        Doc.text(" ? "),
                        conditionalBranch(expression.getThenExpr()),
                        Doc.text(" : "),
                        conditionalBranch(expression.getElseExpr()));
            }
            return Doc.text(flat);
        }
        return Doc.concat(
                conditionalCondition(expression),
                Doc.indent(Doc.concat(
                        Doc.HARD_LINE,
                        Doc.text("? "),
                        conditionalBranch(expression.getThenExpr()),
                        Doc.HARD_LINE,
                        Doc.text(": "),
                        conditionalBranch(expression.getElseExpr()))));
    }

    /**
     * Rebuilds a conditional expression when line comments are attached around the ternary operators.
     *
     * <p>JavaParser attaches these comments to nearby expressions, not to {@code ?} or {@code :} tokens. The formatter
     * therefore checks source positions to classify each line comment as trailing the condition, leading the
     * {@code ?} branch, trailing the then branch, leading the {@code :} branch, or trailing the else branch. A line
     * comment that actually trails the containing expression statement is left to statement-level handling.
     */
    private Optional<Doc> commentedConditionalExpression(ConditionalExpr expression) {
        if (expression.getAllContainedComments().stream().noneMatch(LineComment.class::isInstance)) {
            return Optional.empty();
        }
        Optional<Comment> conditionComment = expression.getCondition().getComment()
                .filter(LineComment.class::isInstance);
        Optional<Comment> thenComment = expression.getThenExpr().getComment()
                .filter(LineComment.class::isInstance);
        Optional<Comment> elseComment = expression.getElseExpr().getComment()
                .filter(LineComment.class::isInstance);
        Optional<Comment> leadingThenComment =
                thenComment.filter(comment -> startsBefore(comment, expression.getThenExpr()));
        Optional<Comment> conditionTrailingComment =
                conditionComment
                        .filter(comment -> conditionalQuestionCommentTrailsCondition(expression, comment))
                        .or(() -> leadingThenComment
                                .filter(comment -> conditionalQuestionCommentTrailsCondition(expression, comment)));
        Optional<Comment> questionComment = conditionComment
                .filter(comment -> !conditionalQuestionCommentTrailsCondition(expression, comment))
                .or(() -> leadingThenComment
                        .filter(comment -> !conditionalQuestionCommentTrailsCondition(expression, comment)));
        Optional<Comment> thenTrailingComment = thenComment
                .filter(comment -> !startsBefore(comment, expression.getThenExpr()))
                .filter(comment -> !commentAppearsAfterColon(expression, comment));
        Optional<Comment> colonComment = thenComment
                .filter(comment -> questionComment.filter(question -> question == comment).isEmpty())
                .filter(comment -> commentAppearsAfterColon(expression, comment))
                .or(() -> elseComment.filter(comment -> startsBefore(comment, expression.getElseExpr())));
        Optional<Comment> elseTrailingComment = elseComment
                .filter(comment -> colonComment.filter(colon -> colon == comment).isEmpty())
                .filter(comment -> !startsBefore(comment, expression.getElseExpr()))
                .filter(comment -> !conditionalElseCommentIsStatementTrailing(expression, comment));
        return Optional.of(Doc.concat(
                conditionalConditionWithTrailingComment(expression.getCondition(), conditionTrailingComment),
                Doc.indent(Doc.concat(
                        Doc.HARD_LINE,
                        conditionalCommentedBranch("?", expression.getThenExpr(), questionComment, thenTrailingComment),
                        Doc.HARD_LINE,
                        conditionalCommentedBranch(":", expression.getElseExpr(), colonComment, elseTrailingComment)))));
    }

    private Doc conditionalConditionWithTrailingComment(Expression condition, Optional<Comment> trailingComment) {
        Doc trailing = trailingComment
                .map(comment -> Doc.concat(Doc.text(" "), comments.comment(comment)))
                .orElse(Doc.EMPTY);
        return Doc.concat(expressionWithoutOwnCommentRenderer.apply(condition), trailing);
    }

    private boolean conditionalQuestionCommentTrailsCondition(ConditionalExpr expression, Comment comment) {
        return commentAppearsAfterOperator(expression, comment, "?")
                && startsAfterNodeOnSameLine(expression.getCondition(), comment);
    }

    /**
     * Prints one commented ternary branch after the surrounding classifier has decided whether the comment belongs
     * before or after the branch expression.
     */
    private Doc conditionalCommentedBranch(
            String operator,
            Expression branch,
            Optional<Comment> leadingComment,
            Optional<Comment> trailingComment) {
        if (leadingComment.isPresent()) {
            return Doc.concat(
                    Doc.text(operator + " "),
                    comments.comment(leadingComment.orElseThrow()),
                    Doc.HARD_LINE,
                    Doc.text("  "),
                    expressionWithoutOwnCommentRenderer.apply(branch));
        }
        Doc trailing = trailingComment
                .map(comment -> Doc.concat(Doc.text(" "), comments.comment(comment)))
                .orElse(Doc.EMPTY);
        return Doc.concat(Doc.text(operator + " "), expressionWithoutOwnCommentRenderer.apply(branch), trailing);
    }

    private boolean conditionalElseCommentIsStatementTrailing(ConditionalExpr expression, Comment comment) {
        return expression.getParentNode()
                .stream()
                .flatMap(parent -> findAncestorExpressionStatement(parent).stream())
                .anyMatch(statement -> startsAfterNodeOnSameLine(statement, comment));
    }

    private Optional<ExpressionStmt> findAncestorExpressionStatement(Node node) {
        Optional<Node> parent = node.getParentNode();
        while (parent.isPresent()) {
            Node current = parent.orElseThrow();
            if (current instanceof ExpressionStmt expressionStmt) {
                return Optional.of(expressionStmt);
            }
            parent = current.getParentNode();
        }
        return Optional.empty();
    }

    private boolean commentAppearsAfterColon(ConditionalExpr expression, Comment comment) {
        return commentAppearsAfterOperator(expression, comment, ":");
    }

    private boolean commentAppearsAfterOperator(ConditionalExpr expression, Comment comment, String operator) {
        return expression.getTokenRange()
                .flatMap(tokenRange -> expression.getRange().flatMap(expressionRange -> comment.getRange()
                        .map(commentRange -> {
                            List<String> lines = tokenRange.toString().lines().toList();
                            int lineIndex = commentRange.begin.line - expressionRange.begin.line;
                            if (lineIndex < 0 || lineIndex >= lines.size()) {
                                return false;
                            }
                            int column = lineIndex == 0
                                    ? commentRange.begin.column - expressionRange.begin.column
                                    : commentRange.begin.column - 1;
                            if (column <= 0) {
                                return false;
                            }
                            String prefix = lines.get(lineIndex).substring(0, Math.min(column, lines.get(lineIndex).length()));
                            return prefix.contains(operator);
                        })))
                .orElse(false);
    }

    /**
     * Prints the ternary condition and chooses the binary wrapping shape for long binary conditions.
     *
     * <p>When a conditional is the value of an assignment or variable initializer, its condition is already under an
     * assignment continuation, so regular binary lines keep the indentation stable. In nested expression contexts,
     * nested binary lines add the extra continuation shape that makes the inner expression read as subordinate to the
     * outer one.
     */
    private Doc conditionalCondition(ConditionalExpr expression) {
        Expression condition = expression.getCondition();
        if (condition instanceof BinaryExpr
                && continuationStatementWidth.applyAsInt(compact.apply(condition)) > options.lineWidth()) {
            if (conditionalIsAssignmentValue(expression) || conditionalIsVariableInitializer(expression)) {
                return binaryExpressionLinesRenderer.apply(condition, true);
            }
            return nestedBinaryExpressionLinesRenderer.apply(condition, true);
        }
        return expressionRenderer.apply(condition);
    }

    private boolean conditionalIsAssignmentValue(ConditionalExpr expression) {
        return expression.getParentNode()
                .filter(AssignExpr.class::isInstance)
                .map(AssignExpr.class::cast)
                .filter(assignExpr -> assignExpr.getValue() == expression)
                .isPresent();
    }

    private boolean conditionalIsVariableInitializer(ConditionalExpr expression) {
        return expression.getParentNode()
                .filter(VariableDeclarator.class::isInstance)
                .map(VariableDeclarator.class::cast)
                .flatMap(VariableDeclarator::getInitializer)
                .filter(initializer -> initializer == expression)
                .isPresent();
    }

    private Doc conditionalBranch(Expression branch) {
        if (branch instanceof ConditionalExpr conditionalExpr) {
            return conditionalExpression(conditionalExpr, true);
        }
        return expressionRenderer.apply(branch);
    }

    private boolean startsBefore(Comment comment, Node node) {
        return comment.getRange()
                .flatMap(commentRange -> node.getRange().map(nodeRange -> startsBefore(commentRange, nodeRange)))
                .orElse(false);
    }

    private boolean startsBefore(Range left, Range right) {
        if (left.begin.line != right.begin.line) {
            return left.begin.line < right.begin.line;
        }
        return left.begin.column < right.begin.column;
    }

    private boolean startsAfterNodeOnSameLine(Node node, Comment comment) {
        return node.getRange()
                .flatMap(nodeRange -> comment.getRange()
                        .map(commentRange -> commentRange.begin.line == nodeRange.end.line
                                && commentRange.begin.column > nodeRange.end.column))
                .orElse(false);
    }
}
