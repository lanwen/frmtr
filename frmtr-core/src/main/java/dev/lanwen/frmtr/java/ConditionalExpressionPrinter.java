package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
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
 * {@code frmtr-core/src/test/resources/format/conditional-expression-space-indentation/input.java} with
 * {@code frmtr-core/src/test/resources/format/conditional-expression-space-indentation/frmtr-default.output.java},
 * {@code frmtr-core/src/test/resources/format/conditional-expression-tab-indentation/input.java} with
 * {@code frmtr-core/src/test/resources/format/conditional-expression-tab-indentation/frmtr-default.output.java},
 * {@code frmtr-core/src/test/resources/format/variable-declarations/input.java} with
 * {@code frmtr-core/src/test/resources/format/variable-declarations/frmtr-default.output.java}, and
 * {@code frmtr-core/src/test/resources/format/expression-operators-layout/input.java} with
 * {@code frmtr-core/src/test/resources/format/expression-operators-layout/frmtr-default.output.java}.
 */
final class ConditionalExpressionPrinter {
    private final CommentTracker comments;
    private final FormatterOptions options;
    private final RawSource rawSource;
    private final SourceShape sourceShape;
    private final CompactSourceText compactSource;
    private final Function<Expression, Doc> expressionRenderer;
    private final Function<Expression, Doc> expressionWithoutOwnCommentRenderer;
    private final ToIntFunction<String> currentIndentedWidth;
    private final ToIntFunction<String> blockStatementWidth;
    private final ToIntFunction<String> continuationStatementWidth;
    private final BiFunction<Expression, Boolean, Doc> binaryExpressionLinesRenderer;
    private final BiFunction<Expression, Boolean, Doc> nestedBinaryExpressionLinesRenderer;
    private final Predicate<Expression> expressionHasParenthesizedNestedBinary;

    /**
     * Names whether conditional-expression layout is caller-forced or selected by local width and comment checks.
     *
     * <p>The enum owns only the ternary break mode. Assignment, return, field, and enclosed-expression callers still
     * decide when their surrounding context requires the forced mode.
     */
    private enum ConditionalBreakMode {
        /** Keep the conditional flat when source-equivalent text fits and no comments or nesting require rebuilding. */
        AUTO,

        /** Print the broken ternary shape because a caller has already selected a multiline conditional context. */
        FORCED;

        static ConditionalBreakMode fromForced(boolean forced) {
            return forced ? FORCED : AUTO;
        }

        boolean isForced() {
            return this == FORCED;
        }
    }

    /**
     * Names how a conditional expression's condition should render when the condition itself is a long binary tree.
     *
     * <p>The enum owns only the condition sub-layout. It leaves branch rendering, assignment detection, and the binary
     * expression continuation policy with their existing owners.
     */
    private enum ConditionalConditionLayout {
        /** Render the condition through ordinary expression dispatch. */
        EXPRESSION,

        /** Render a wide binary condition with the assignment/initializer continuation shape. */
        ASSIGNMENT_CONTINUATION_BINARY,

        /** Render a wide binary condition with nested-expression continuation indentation. */
        NESTED_BINARY
    }

    ConditionalExpressionPrinter(
            JavaFormatContext context,
            Function<Expression, Doc> expressionRenderer,
            Function<Expression, Doc> expressionWithoutOwnCommentRenderer,
            ToIntFunction<String> currentIndentedWidth,
            ToIntFunction<String> blockStatementWidth,
            ToIntFunction<String> continuationStatementWidth,
            BiFunction<Expression, Boolean, Doc> binaryExpressionLinesRenderer,
            BiFunction<Expression, Boolean, Doc> nestedBinaryExpressionLinesRenderer,
            Predicate<Expression> expressionHasParenthesizedNestedBinary) {
        this.comments = context.comments;
        this.options = context.options;
        this.rawSource = context.rawSource;
        this.sourceShape = context.sourceShape;
        this.compactSource = context.compactSource;
        this.expressionRenderer = expressionRenderer;
        this.expressionWithoutOwnCommentRenderer = expressionWithoutOwnCommentRenderer;
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
        if (sourceShape.spansMultipleLines(conditionalExpr) && sourceShape.startsOnSameLine(assignExpr, conditionalExpr)) {
            return Optional.of(Doc.concat(
                    expressionRenderer.apply(assignExpr.getTarget()),
                    Doc.text(" " + assignExpr.getOperator().asString() + " "),
                    conditionalExpression(conditionalExpr, ConditionalBreakMode.FORCED)));
        }
        if (shouldBreakBeforeConditionalInitializer(conditionalExpr)
                || shouldBreakBeforeConditionalAssignment(conditionalExpr)) {
            return Optional.of(Doc.concat(
                    expressionRenderer.apply(assignExpr.getTarget()),
                    Doc.text(" " + assignExpr.getOperator().asString()),
                    Doc.indent(Doc.concat(
                            Doc.HARD_LINE,
                            conditionalExpression(conditionalExpr, ConditionalBreakMode.FORCED)))));
        }
        String conditionLine = compactSource.compact(assignExpr.getTarget()) + " "
                + assignExpr.getOperator().asString()
                + " "
                + compactSource.compact(conditionalExpr.getCondition())
                + ";";
        if (blockStatementWidth.applyAsInt(conditionLine) <= options.lineWidth()) {
            return Optional.of(Doc.concat(
                    expressionRenderer.apply(assignExpr.getTarget()),
                    Doc.text(" " + assignExpr.getOperator().asString() + " "),
                    conditionalExpression(conditionalExpr, ConditionalBreakMode.FORCED)));
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
        return conditionalExpression(expression, ConditionalBreakMode.AUTO);
    }

    /**
     * Prints a conditional expression, preserving flat output until width, nesting, comments, or caller context require
     * the broken ternary shape.
     */
    Doc conditionalExpression(ConditionalExpr expression, boolean forceBreak) {
        return conditionalExpression(expression, ConditionalBreakMode.fromForced(forceBreak));
    }

    private Doc conditionalExpression(ConditionalExpr expression, ConditionalBreakMode breakMode) {
        Optional<Doc> commented = commentedConditionalExpression(expression);
        if (commented.isPresent()) {
            return commented.orElseThrow();
        }
        String flat = compactSource.compact(expression);
        if (!breakMode.isForced() && sourceShape.spansMultipleLines(expression)) {
            return brokenConditionalExpression(expression);
        }
        if (!breakMode.isForced() && currentIndentedWidth.applyAsInt(flat) <= options.lineWidth()) {
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
        return brokenConditionalExpression(expression);
    }

    private Doc brokenConditionalExpression(ConditionalExpr expression) {
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
                thenComment.filter(comment -> CommentIndex.startsBefore(comment, expression.getThenExpr()));
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
                .filter(comment -> !CommentIndex.startsBefore(comment, expression.getThenExpr()))
                .filter(comment -> !commentAppearsAfterColon(expression, comment));
        Optional<Comment> colonComment = thenComment
                .filter(comment -> questionComment.filter(question -> question == comment).isEmpty())
                .filter(comment -> commentAppearsAfterColon(expression, comment))
                .or(() -> elseComment.filter(comment -> CommentIndex.startsBefore(comment, expression.getElseExpr())));
        Optional<Comment> elseTrailingComment = elseComment
                .filter(comment -> colonComment.filter(colon -> colon == comment).isEmpty())
                .filter(comment -> !CommentIndex.startsBefore(comment, expression.getElseExpr()))
                .filter(comment -> !conditionalElseCommentIsStatementTrailing(expression, comment));
        return Optional.of(Doc.concat(
                conditionalConditionWithTrailingComment(expression.getCondition(), conditionTrailingComment),
                Doc.indent(Doc.concat(
                        Doc.HARD_LINE,
                        conditionalCommentedBranch(
                                "?",
                                expression.getThenExpr(),
                                questionComment,
                                thenTrailingComment),
                        Doc.HARD_LINE,
                        conditionalCommentedBranch(
                                ":",
                                expression.getElseExpr(),
                                colonComment,
                                elseTrailingComment)))));
    }

    private Doc conditionalConditionWithTrailingComment(Expression condition, Optional<Comment> trailingComment) {
        Doc trailing = trailingComment
                .map(comment -> Doc.concat(Doc.text(" "), comments.comment(comment)))
                .orElse(Doc.EMPTY);
        return Doc.concat(expressionWithoutOwnCommentRenderer.apply(condition), trailing);
    }

    private boolean conditionalQuestionCommentTrailsCondition(ConditionalExpr expression, Comment comment) {
        return commentAppearsAfterOperator(expression, comment, "?")
                && CommentIndex.startsAfterNodeOnSameLine(expression.getCondition(), comment);
    }

    /**
     * Prints one commented ternary branch after the surrounding classifier has decided whether the comment belongs
     * before or after the branch expression.
     */
    private Doc conditionalCommentedBranch(
            String operatorToken,
            Expression branch,
            Optional<Comment> leadingComment,
            Optional<Comment> trailingComment) {
        if (leadingComment.isPresent()) {
            return Doc.concat(
                    Doc.text(operatorToken + " "),
                    comments.comment(leadingComment.orElseThrow()),
                    Doc.HARD_LINE,
                    Doc.text("  "),
                    expressionWithoutOwnCommentRenderer.apply(branch));
        }
        Doc trailing = trailingComment
                .map(comment -> Doc.concat(Doc.text(" "), comments.comment(comment)))
                .orElse(Doc.EMPTY);
        return Doc.concat(Doc.text(operatorToken + " "), expressionWithoutOwnCommentRenderer.apply(branch), trailing);
    }

    private boolean conditionalElseCommentIsStatementTrailing(ConditionalExpr expression, Comment comment) {
        return expression.getParentNode()
                .stream()
                .flatMap(parent -> findAncestorExpressionStatement(parent).stream())
                .anyMatch(statement -> CommentIndex.startsAfterNodeOnSameLine(statement, comment));
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

    private boolean commentAppearsAfterOperator(
            ConditionalExpr expression,
            Comment comment,
            String operatorToken) {
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
                            return prefix.contains(operatorToken);
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
        return switch (conditionalConditionLayout(expression, condition)) {
            case EXPRESSION -> expressionRenderer.apply(condition);
            case ASSIGNMENT_CONTINUATION_BINARY -> enclosedBinaryCondition(condition, binaryExpressionLinesRenderer);
            case NESTED_BINARY -> enclosedBinaryCondition(condition, nestedBinaryExpressionLinesRenderer);
        };
    }

    private ConditionalConditionLayout conditionalConditionLayout(
            ConditionalExpr expression,
            Expression condition) {
        if (binaryCondition(condition).isEmpty()
                || continuationStatementWidth.applyAsInt(compactSource.compact(condition)) <= options.lineWidth()) {
            return ConditionalConditionLayout.EXPRESSION;
        }
        if (conditionalIsAssignmentValue(expression) || conditionalIsVariableInitializer(expression)) {
            return ConditionalConditionLayout.ASSIGNMENT_CONTINUATION_BINARY;
        }
        return ConditionalConditionLayout.NESTED_BINARY;
    }

    private Doc enclosedBinaryCondition(
            Expression condition,
            BiFunction<Expression, Boolean, Doc> binaryRenderer) {
        BinaryExpr binary = binaryCondition(condition).orElseThrow();
        Doc lines = binaryRenderer.apply(binary, true);
        for (int i = 0; i < enclosedDepth(condition); i++) {
            lines = Doc.concat(
                    Doc.text("("),
                    Doc.indent(Doc.concat(Doc.HARD_LINE, lines)),
                    Doc.HARD_LINE,
                    Doc.text(")"));
        }
        return lines;
    }

    private Optional<BinaryExpr> binaryCondition(Expression condition) {
        if (condition instanceof BinaryExpr binaryExpr) {
            return Optional.of(binaryExpr);
        }
        if (condition instanceof EnclosedExpr enclosedExpr) {
            return binaryCondition(enclosedExpr.getInner());
        }
        return Optional.empty();
    }

    private int enclosedDepth(Expression condition) {
        int depth = 0;
        Expression current = condition;
        while (current instanceof EnclosedExpr enclosedExpr) {
            depth++;
            current = enclosedExpr.getInner();
        }
        return depth;
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
            return conditionalExpression(conditionalExpr, ConditionalBreakMode.FORCED);
        }
        if (branch instanceof MethodCallExpr && sourceShape.spansMultipleLines(branch)) {
            return Doc.text(rawSource.rawWithoutOwnComment(branch));
        }
        return expressionRenderer.apply(branch);
    }

}
