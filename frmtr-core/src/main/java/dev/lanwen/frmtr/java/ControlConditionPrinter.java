package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.comments.BlockComment;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
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

    private final SourceShape sourceShape;

    private final FormatterOptions options;

    private final Function<Expression, Doc> expressionRenderer;

    private final Function<Expression, String> compact;

    private final Function<List<? extends Node>, String> compactJoin;

    private final Function<Expression, String> compactWithoutOwnComment;

    private final Predicate<Expression> expressionHasParenthesizedNestedBinary;

    private final Function<Expression, Doc> brokenExpressionLines;

    private final Function<MethodCallExpr, Optional<Doc>> forcedMethodCallChain;

    private final ControlConditionMethodCallLayout methodCallLayout;

    private final ToIntFunction<String> currentIndentedWidth;

    private final ToIntFunction<String> blockStatementWidth;

    private final LayoutDecisionLog layoutDecisions;

    ControlConditionPrinter(
            CommentTracker comments,
            SourceShape sourceShape,
            FormatterOptions options,
            Function<Expression, Doc> expressionRenderer,
            Function<Expression, String> compact,
            Function<List<? extends Node>, String> compactJoin,
            Function<Expression, String> compactWithoutOwnComment,
            Predicate<Expression> expressionHasParenthesizedNestedBinary,
            Function<Expression, Doc> brokenExpressionLines,
            Function<MethodCallExpr, Optional<Doc>> forcedMethodCallChain,
            ToIntFunction<String> currentIndentedWidth,
            ToIntFunction<String> blockStatementWidth,
            LayoutDecisionLog layoutDecisions
    ) {
        this.comments = comments;
        this.sourceShape = sourceShape;
        this.options = options;
        this.expressionRenderer = expressionRenderer;
        this.compact = compact;
        this.compactJoin = compactJoin;
        this.compactWithoutOwnComment = compactWithoutOwnComment;
        this.expressionHasParenthesizedNestedBinary = expressionHasParenthesizedNestedBinary;
        this.brokenExpressionLines = brokenExpressionLines;
        this.forcedMethodCallChain = forcedMethodCallChain;
        this.currentIndentedWidth = currentIndentedWidth;
        this.blockStatementWidth = blockStatementWidth;
        this.layoutDecisions = layoutDecisions;
        this.methodCallLayout = new ControlConditionMethodCallLayout(
            sourceShape,
            options,
            expressionRenderer,
            compact,
            compactJoin,
            forcedMethodCallChain,
            blockStatementWidth
        );
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
        return controlCondition(expression, "(", ") {}", currentIndentedWidth);
    }

    Doc controlCondition(
            Expression expression,
            String opening,
            String closing,
            ToIntFunction<String> conditionLineWidth
    ) {
        String flat = compactWithOwnBlockComment(expression);
        if (sourceMultilineLogicalCondition(expression)) {
            return brokenLogicalCondition(expression).orElseGet(() -> brokenCondition(expression));
        }
        if (logicalConditionWithControlContextOverflows(expression, flat, opening, closing, conditionLineWidth)) {
            return brokenLogicalCondition(expression).orElseGet(() -> brokenCondition(expression));
        }
        if (conditionLineWidth.applyAsInt(opening + flat + closing) <= options.lineWidth()) {
            return Doc.text("(" + flat + ")");
        }
        return brokenCondition(expression);
    }

    private boolean logicalConditionWithControlContextOverflows(
            Expression expression,
            String flat,
            String opening,
            String closing,
            ToIntFunction<String> conditionLineWidth
    ) {
        return sourceMultilineLogicalConditionExpression(expression)
            && !sourceShape.spansMultipleLines(expression)
            && conditionLineWidth.applyAsInt(opening + flat + closing) > options.lineWidth();
    }

    private Optional<Doc> brokenLogicalCondition(Expression expression) {
        List<LogicalConditionTerm> terms = new ArrayList<>();
        collectLogicalConditionTerms(expression, "", terms);
        if (terms.size() < 2) {
            return Optional.empty();
        }
        return Optional.of(
            Doc.concat(
                Doc.text("("),
                Doc.indent(
                    Doc.concat(
                        Doc.HARD_LINE,
                        Doc.join(
                            Doc.HARD_LINE,
                            terms.stream()
                                    .map(LogicalConditionTerm::doc)
                                    .toList()
                        )
                    )
                ),
                Doc.HARD_LINE,
                Doc.text(")")
            )
        );
    }

    private void collectLogicalConditionTerms(
            Expression expression,
            String operator,
            List<LogicalConditionTerm> terms
    ) {
        Expression current = expression;
        if (current instanceof BinaryExpr binaryExpr && isLogicalConditionOperator(binaryExpr)) {
            collectLogicalConditionTerms(binaryExpr.getLeft(), operator, terms);
            collectLogicalConditionTerms(binaryExpr.getRight(), binaryExpr.getOperator().asString(), terms);
            return;
        }
        Doc operand = methodCallLayout
                .sourceMultilineLogicalOperand(current)
                .orElseGet(
                    () ->
                        logicalConditionOperandShouldBreak(current)
                            ? brokenExpressionLines.apply(current)
                            : sourceShape.spansMultipleLines(current)
                                ? expressionRenderer.apply(current)
                                : Doc.text(compact.apply(current))
                );
        terms.add(new LogicalConditionTerm(operator, operand));
    }

    private boolean logicalConditionOperandShouldBreak(Expression expression) {
        return blockStatementWidth.applyAsInt(compact.apply(expression)) > options.lineWidth();
    }

    private boolean isLogicalConditionOperator(BinaryExpr expression) {
        return expression.getOperator() == BinaryExpr.Operator.AND
            || expression.getOperator() == BinaryExpr.Operator.OR;
    }

    /**
     * Renders the parenthesized condition for an {@code if} statement after the statement printer has selected if/else
     * grammar.
     *
     * <p>The width gate includes the {@code if} keyword and an empty block because if conditions have a slightly wider
     * surrounding line than loop tails. Source-multiline logical conditions intentionally keep a broken operand layout
     * even when the compact condition would fit the formatter's local width estimate.
     */
    Doc ifCondition(Expression expression) {
        Optional<Doc> commented = commentedIfCondition(expression);
        if (commented.isPresent()) {
            return commented.orElseThrow();
        }
        if (sourceMultilineLogicalCondition(expression)) {
            if (methodCallLayout.sourceMultilineLogicalConditionHasMethodCallOperand(expression)) {
                return brokenLogicalCondition(expression).orElseGet(() -> brokenCondition(expression));
            }
            return brokenCondition(expression);
        }
        String flat = compact.apply(expression);
        int flatWidth = ifConditionLineWidth(expression, "if (" + flat + ") {}");
        if (
            expression instanceof MethodCallExpr methodCall
            && methodCall.getArguments().size() > 1
            && (flatWidth > options.lineWidth()
                || (sourceMultilineMethodCallArguments(methodCall)
                    && flatWidth > options.lineWidth() - options.indentUnit().length())
                || (methodCallLayout.hasComplexArgument(methodCall)
                    && flatWidth > options.lineWidth() - options.indentUnit().length()))
        ) {
            Optional<Doc> brokenMethodCall = brokenMethodCallCondition(methodCall);
            if (brokenMethodCall.isPresent()) {
                return brokenMethodCall.orElseThrow();
            }
        }
        if (flatWidth <= options.lineWidth()) {
            if (expressionHasParenthesizedNestedBinary.test(expression)) {
                return Doc.concat(Doc.text("("), expressionRenderer.apply(expression), Doc.text(")"));
            }
            return Doc.text("(" + flat + ")");
        }
        recordIfConditionWidthBreak(flat, flatWidth);
        if (expression instanceof MethodCallExpr methodCall) {
            Optional<Doc> brokenMethodCall = brokenMethodCallCondition(methodCall);
            if (brokenMethodCall.isPresent()) {
                return brokenMethodCall.orElseThrow();
            }
        }
        Optional<Doc> complementedMethodCallChain = complementedMethodCallChainCondition(expression);
        if (complementedMethodCallChain.isPresent()) {
            return complementedMethodCallChain.orElseThrow();
        }
        return brokenCondition(expression);
    }

    private Optional<Doc> complementedMethodCallChainCondition(Expression expression) {
        if (
            !(expression instanceof UnaryExpr unaryExpr)
            || unaryExpr.getOperator() != UnaryExpr.Operator.LOGICAL_COMPLEMENT
            || !(unaryExpr.getExpression() instanceof MethodCallExpr methodCall)
        ) {
            return Optional.empty();
        }
        return forcedMethodCallChain.apply(methodCall)
                .map(chain -> Doc.concat(
                        Doc.text("("),
                        Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.text("!"), chain)),
                        Doc.HARD_LINE,
                        Doc.text(")")
                ));
    }

    /**
     * Records the if condition's flat-width decision when the condition breaks because its single-line form (with the
     * {@code if (...) {}} surround) overflowed the budget, so explain can attribute the wrap to width.
     *
     * <p>The recorded label is the enclosing {@code IfStmt} so explain merges this with the statement's forced break and
     * reports the wrap once with real arithmetic. Recording runs after the printer chose the broken shape and does not
     * change it. Like the chain, argument-list, and ternary recorders it guards on the flat form genuinely overflowing
     * the budget, so a future second caller that breaks for a non-width reason cannot misattribute the break to width.
     */
    private void recordIfConditionWidthBreak(String flat, int flatWidth) {
        if (flatWidth <= options.lineWidth()) {
            return;
        }
        layoutDecisions.recordWidthBreak(
            "if condition",
            "java.statement:IfStmt",
            "if (" + ifConditionPreview(flat) + ")",
            flatWidth,
            options.lineWidth(),
            0
        );
    }

    private String ifConditionPreview(String flat) {
        int firstAnd = flat.indexOf("&&");
        int firstOr = flat.indexOf("||");
        int split = firstAnd < 0 ? firstOr : firstOr < 0 ? firstAnd : Math.min(firstAnd, firstOr);
        return split < 0 ? flat : flat.substring(0, split).strip() + " …";
    }

    private int ifConditionLineWidth(Expression expression, String line) {
        int sourceWidth = expression.getRange()
                .map(range -> Math.max(0, range.begin.column - "if (".length() + 1) + line.length())
                .orElseGet(() -> blockStatementWidth.applyAsInt(line));
        return Math.max(sourceWidth, currentIndentedWidth.applyAsInt(line));
    }

    private Optional<Doc> brokenMethodCallCondition(MethodCallExpr expression) {
        if (expression.getArguments().isEmpty() || !expression.getAllContainedComments().isEmpty()) {
            return Optional.empty();
        }
        return methodCallLayout.brokenCondition(expression);
    }

    private boolean sourceMultilineMethodCallArguments(MethodCallExpr expression) {
        return methodCallLayout.sourceMultilineArgumentsStartAfterName(expression);
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
            Doc.text(")")
        );
    }

    private Optional<Doc> commentedIfCondition(Expression condition) {
        Optional<Comment> ownComment = condition.getComment();
        if (ownComment.filter(LineComment.class::isInstance).isPresent()) {
            Comment comment = ownComment.orElseThrow();
            Doc printedComment = comments.comment(comment);
            Doc conditionDoc = conditionCommentStartsBeforeExpression(condition, comment)
                ? Doc.join(Doc.HARD_LINE, List.of(printedComment, Doc.text(compactWithoutOwnComment.apply(condition))))
                : Doc.text(compactWithoutOwnComment.apply(condition) + " " + commentText(printedComment));
            return Optional.of(
                Doc.concat(
                    Doc.text("("),
                    Doc.indent(Doc.concat(Doc.HARD_LINE, conditionDoc)),
                    Doc.HARD_LINE,
                    Doc.text(")")
                )
            );
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

    private boolean sourceMultilineLogicalCondition(Expression condition) {
        return sourceMultilineLogicalConditionExpression(condition)
            && sourceShape.sourceMultilineLogicalCondition(condition);
    }

    private boolean sourceMultilineLogicalConditionExpression(Expression condition) {
        return sourceShape.logicalConditionExpression(condition);
    }

    private Doc trailingBlockCommentBeforeCloseParen(Expression condition) {
        return condition.getParentNode()
                .stream()
                .flatMap(parent -> parent.getAllContainedComments().stream())
                .filter(BlockComment.class::isInstance)
                .filter(comment -> comment.getCommentedNode().map(BlockStmt.class::isInstance).orElse(false))
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

    private record LogicalConditionTerm(String operator, Doc operand) {
        Doc doc() {
            return operator.isEmpty() ? operand : Doc.concat(Doc.text(operator + " "), operand);
        }
    }
}
