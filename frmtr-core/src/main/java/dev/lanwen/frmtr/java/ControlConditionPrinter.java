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
import com.github.javaparser.ast.stmt.IfStmt;
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
 * including the comment placement rules that preserve source shape inside condition parentheses. The boundary exists
 * because if, while, do-while, synchronized, and statement-switch selectors all need one condition layout policy after
 * their caller has already chosen the surrounding keyword, body, and statement separator behavior.
 *
 * <p>{@link JavaPrinter} still owns broad expression dispatch, raw-source normalization, and width calculation policy.
 * {@link StatementPrinter} owns ordinary statement grammar, and {@link SwitchPrinter} owns statement-switch selector
 * placement; this helper only returns the condition expression text or docs that fit between the already-decided
 * parentheses.
 */
final class ControlConditionPrinter {

    private final CommentTracker comments;

    private final JavaCommentPlacementPolicy commentPlacement;

    private final SourceText sourceText;

    private final SourceShapePolicy sourceShapePolicy;

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

    private final LayoutWidth layoutWidth;

    private final LayoutDecisionLog layoutDecisions;

    ControlConditionPrinter(
            CommentTracker comments,
            JavaCommentPlacementPolicy commentPlacement,
            SourceText sourceText,
            SourceShapePolicy sourceShapePolicy,
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
            LayoutWidth layoutWidth,
            LayoutDecisionLog layoutDecisions
    ) {
        this.comments = comments;
        this.commentPlacement = commentPlacement;
        this.sourceText = sourceText;
        this.sourceShapePolicy = sourceShapePolicy;
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
        this.layoutWidth = layoutWidth;
        this.layoutDecisions = layoutDecisions;
        this.methodCallLayout = new ControlConditionMethodCallLayout(
            sourceShapePolicy,
            options,
            expressionRenderer,
            compact,
            compactJoin,
            forcedMethodCallChain,
            blockStatementWidth
        );
    }

    Doc controlCondition(
            Expression expression,
            String opening,
            String closing,
            ToIntFunction<String> conditionLineWidth
    ) {
        String flat = compactWithOwnBlockComment(expression);
        if (commentedLogicalCondition(expression)) {
            return brokenCondition(expression);
        }
        Optional<Doc> commented = lineCommentCondition(expression);
        if (commented.isPresent()) {
            return commented.orElseThrow();
        }
        if (hasDetachedConditionLineComment(expression)) {
            return brokenCondition(expression);
        }
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
            && !sourceShapePolicy.wasMultiline(expression)
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
                            : sourceShapePolicy.wasMultiline(current)
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
        if (commentedLogicalCondition(expression)) {
            return brokenCondition(expression);
        }
        Optional<Doc> commented = commentedCondition(expression);
        if (commented.isPresent()) {
            return commented.orElseThrow();
        }
        if (sourceMultilineLogicalCondition(expression)) {
            if (methodCallLayout.sourceMultilineLogicalConditionHasMethodCallOperand(expression)) {
                return brokenLogicalCondition(expression).orElseGet(() -> brokenCondition(expression));
            }
            return brokenCondition(expression);
        }
        if (hasDetachedConditionLineComment(expression)) {
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

    /**
     * Measures the flat {@code if (...) {}} line at the indentation it will actually render at, not at the source
     * column of the condition.
     *
     * <p>The earlier estimate derived the line width from {@code range.begin.column}, but JavaParser counts a leading
     * {@code \t} as a single column. A tab-indented over-width condition therefore under-measured, looked like it fit on
     * pass one, and was emitted flat with the formatter's space indentation — at which point it genuinely overflowed and
     * pass two broke it, so {@code format(format(x)) != format(x)}. Counting the enclosing block/type nesting through
     * {@link LayoutWidth#nodeLine} reproduces the rendered indentation deterministically regardless of how the source
     * was indented, so the fit/break decision is the same on every pass. The {@code currentIndentedWidth} floor is kept
     * so a condition nested directly under a member (no enclosing block) is still measured against at least one
     * indentation unit.
     */
    private int ifConditionLineWidth(Expression expression, String line) {
        return Math.max(
            layoutWidth.nodeLine(expression, line),
            currentIndentedWidth.applyAsInt(line)
        );
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

    private Doc brokenCondition(Expression expression) {
        return Doc.concat(
            Doc.text("("),
            Doc.indent(Doc.concat(Doc.HARD_LINE, brokenConditionContent(expression))),
            Doc.HARD_LINE,
            Doc.text(")")
        );
    }

    private Doc brokenConditionContent(Expression expression) {
        Optional<Doc> leadingLineComments = leadingLineCommentConditionContent(expression);
        if (leadingLineComments.isPresent()) {
            return leadingLineComments.orElseThrow();
        }
        if (
            expression instanceof EnclosedExpr enclosedExpr
            && sourceMultilineLogicalConditionExpression(enclosedExpr.getInner())
        ) {
            return Doc.concat(
                Doc.text("("),
                brokenConditionContent(enclosedExpr.getInner()),
                Doc.text(")")
            );
        }
        return brokenExpressionLines.apply(expression);
    }

    private Optional<Doc> leadingLineCommentConditionContent(Expression expression) {
        int contentLine = contentBeginLine(expression, CommentIndex.beginLine(expression, Integer.MAX_VALUE));
        List<Doc> leadingComments = detachedConditionLineComments(expression)
                .stream()
                .filter(comment -> CommentIndex.beginLine(comment, Integer.MAX_VALUE) < contentLine)
                // A detached condition line comment can also be offered by a neighboring condition render path; skip
                // already-printed comments so this slot does not duplicate-claim them. Output is unchanged because the
                // first claimant placed the comment and a re-offer only ever rendered empty.
                .filter(comment -> !comments.isPrinted(JavaCommentTrivia.from(comment)))
                .map(comments::comment)
                .filter(comment -> comment != Doc.EMPTY)
                .toList();
        if (leadingComments.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Doc.join(
            Doc.HARD_LINE,
            java.util.stream.Stream.concat(
                leadingComments.stream(),
                java.util.stream.Stream.of(Doc.text(compact.apply(expression)))
            ).toList()
        ));
    }

    private Optional<Doc> commentedCondition(Expression condition) {
        Optional<Doc> lineComment = lineCommentCondition(condition);
        if (lineComment.isPresent()) {
            return lineComment;
        }
        if (condition.getComment().filter(BlockComment.class::isInstance).isPresent()) {
            Comment comment = condition.getComment().orElseThrow();
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

    private Optional<Doc> lineCommentCondition(Expression condition) {
        Optional<Doc> ownLineComment = ownLineCommentCondition(condition);
        if (ownLineComment.isPresent()) {
            return ownLineComment;
        }
        return recoverableTrailingLineCommentCondition(condition);
    }

    private Optional<Doc> ownLineCommentCondition(Expression condition) {
        Optional<Comment> ownComment = condition.getComment();
        if (ownComment.filter(LineComment.class::isInstance).isPresent()) {
            Comment comment = ownComment.orElseThrow();
            if (
                !conditionCommentStartsBeforeExpression(condition, comment)
                && !lineCommentTrailsInsideCondition(condition, comment)
            ) {
                return Optional.empty();
            }
            Doc printedComment = comments.comment(comment);
            Doc conditionDoc = conditionCommentStartsBeforeExpression(condition, comment)
                ? Doc.join(Doc.HARD_LINE, List.of(printedComment, Doc.text(compactWithoutOwnComment.apply(condition))))
                : Doc.concat(
                    Doc.text(compactWithoutOwnComment.apply(condition)),
                    Doc.lineSuffix(Doc.concat(Doc.text(" "), printedComment))
                );
            return Optional.of(
                Doc.concat(
                    Doc.text("("),
                    Doc.indent(Doc.concat(Doc.HARD_LINE, conditionDoc)),
                    Doc.HARD_LINE,
                    Doc.text(")")
                )
            );
        }
        return Optional.empty();
    }

    /**
     * Recovers trailing line comments that JavaParser leaves on the parent or only in the raw source gap instead of
     * attaching to the condition.
     *
     * <p>Recovered switch entry lists can keep the selector expression parsed while storing {@code value // comment}
     * trivia outside the selector's own comment association. The shared condition renderer still owns that source
     * shape, so it asks the placement policy first, then falls back to a narrow source slice before choosing a compact
     * form.
     */
    private Optional<Doc> recoverableTrailingLineCommentCondition(Expression condition) {
        Optional<String> rawComment = rawTrailingLineCommentText(condition);
        if (rawComment.isEmpty()) {
            return Optional.empty();
        }
        Optional<JavaCommentTrivia> trailing = commentPlacement.sameLineTrailingLineComment(condition)
                .filter(comment -> lineCommentTrailsConditionContent(condition, comment.comment()))
                .filter(comment -> rawComment.orElseThrow().equals(commentText(JavaFormatter.commentDoc(comment))));
        if (trailing.isPresent()) {
            Doc printedComment = comments.comment(trailing.orElseThrow());
            if (printedComment == Doc.EMPTY) {
                return Optional.empty();
            }
            return Optional.of(conditionWithTrailingLineComment(condition, printedComment));
        }
        return Optional.of(conditionWithTrailingLineComment(condition, Doc.text(rawComment.orElseThrow())));
    }

    Doc closeParenTrailingLineComment(Expression condition) {
        Optional<String> rawComment = rawCloseParenTrailingLineCommentText(condition);
        if (rawComment.isEmpty()) {
            return recoveredCloseParenTrailingLineComment(condition);
        }
        Optional<JavaCommentTrivia> trailing = commentPlacement.sameLineTrailingLineComment(condition)
                .filter(comment -> lineCommentTrailsConditionContent(condition, comment.comment()))
                .filter(comment -> rawComment.orElseThrow().equals(commentText(JavaFormatter.commentDoc(comment))));
        if (trailing.isPresent()) {
            Doc printedComment = comments.comment(trailing.orElseThrow());
            return printedComment == Doc.EMPTY ? Doc.EMPTY : printedComment;
        }
        return Doc.text(rawComment.orElseThrow());
    }

    /**
     * Recovers the close-paren trailing line comment of an {@code if} statement that a whitespace perturbation moved onto
     * its own line below the {@code )} and re-bucketed onto the enclosing {@link IfStmt} as an orphan, so {@code rawClose
     * ParenTrailingLineCommentText} no longer finds it inline after the {@code )}.
     *
     * <p>At the {@code @default} shape the comment sits inline after {@code )}; the raw-slice path above renders it and
     * the {@code if} statement holds no such orphan, so this recovery fires only under perturbation and adds nothing at
     * default. The comment value comes from the orphan comment itself (not a raw slice): there is no inline slice to
     * match when the comment is on its own line, so requiring a raw-slice match here would silently drop it. The caller
     * still owns whether the recovered comment renders inline after {@code )} or on its own line.
     *
     * <p>This is intentionally scoped to {@link IfStmt}, the only construct with a distinct then-statement node that
     * bounds the {@code )}-to-body gap. Switch selectors are excluded: a comment between a switch selector and its first
     * entry is a switch-body leading comment that {@code SwitchPrinter} owns, not a close-paren tail, so recovering it
     * here would hoist it onto the {@code )} line and double-claim it away from the body.
     */
    private Doc recoveredCloseParenTrailingLineComment(Expression condition) {
        return closeParenTrailingOrphans(condition)
                .stream()
                .findFirst()
                .map(comments::comment)
                .filter(printed -> printed != Doc.EMPTY)
                .orElse(Doc.EMPTY);
    }

    private List<JavaCommentTrivia> closeParenTrailingOrphans(Expression condition) {
        Optional<IfStmt> controlStmt = condition.getParentNode()
                .filter(IfStmt.class::isInstance)
                .map(IfStmt.class::cast);
        if (controlStmt.isEmpty()) {
            return List.of();
        }
        return commentPlacement.trailingConditionComments(
            controlStmt.orElseThrow(),
            condition,
            controlStmt.orElseThrow().getThenStmt()
        );
    }

    private boolean lineCommentTrailsInsideCondition(Expression condition, Comment comment) {
        return lineCommentTrailsConditionContent(condition, comment)
            && rawTrailingLineCommentText(condition)
                    .filter(rawComment -> rawComment.equals(commentText(JavaFormatter.commentDoc(comment))))
                    .isPresent();
    }

    /**
     * Lays the condition on its own line inside the parentheses and defers the trailing line comment to a {@link
     * Doc#lineSuffix(Doc)} so it flushes at the line break before the closing paren. Emitting the comment as a suffix
     * rather than baking it into the condition's text literal keeps the comment out of width measurement (a trailing
     * comment never widens the line it sits on) while rendering byte-identically to {@code condition // comment}.
     */
    private Doc conditionWithTrailingLineComment(Expression condition, Doc comment) {
        Doc conditionDoc = Doc.concat(
            Doc.text(compact.apply(condition)),
            Doc.lineSuffix(Doc.concat(Doc.text(" "), comment))
        );
        return Doc.concat(
            Doc.text("("),
            Doc.indent(Doc.concat(Doc.HARD_LINE, conditionDoc)),
            Doc.HARD_LINE,
            Doc.text(")")
        );
    }

    private Optional<String> rawTrailingLineCommentText(Expression condition) {
        Optional<String> suffix = condition.getRange()
                .flatMap(conditionRange -> condition.getParentNode()
                            .flatMap(Node::getRange)
                            .map(parentRange -> sourceText.sliceAfterWithin(conditionRange, parentRange))
                );
        if (suffix.isEmpty()) {
            return Optional.empty();
        }
        String text = suffix.orElseThrow();
        int closeParen = text.indexOf(')');
        int commentStart = text.indexOf("//");
        if (
            closeParen < 0
            || commentStart < 0
            || closeParen < commentStart
            || !onlyHorizontalWhitespace(text, 0, commentStart)
        ) {
            return Optional.empty();
        }
        int commentEnd = lineEnd(text, commentStart);
        return Optional.of(stripTrailingHorizontalWhitespace(text.substring(commentStart, commentEnd)));
    }

    private Optional<String> rawCloseParenTrailingLineCommentText(Expression condition) {
        Optional<String> suffix = condition.getRange()
                .flatMap(conditionRange -> condition.getParentNode()
                            .flatMap(Node::getRange)
                            .map(parentRange -> sourceText.sliceAfterWithin(conditionRange, parentRange))
                );
        if (suffix.isEmpty()) {
            return Optional.empty();
        }
        String text = suffix.orElseThrow();
        int closeParen = text.indexOf(')');
        int commentStart = text.indexOf("//");
        if (
            closeParen < 0
            || commentStart < 0
            || commentStart < closeParen
            || !onlyHorizontalWhitespace(text, closeParen + 1, commentStart)
        ) {
            return Optional.empty();
        }
        int commentEnd = lineEnd(text, commentStart);
        return Optional.of(stripTrailingHorizontalWhitespace(text.substring(commentStart, commentEnd)));
    }

    private boolean onlyHorizontalWhitespace(String text, int begin, int end) {
        for (int index = begin; index < end; index++) {
            char value = text.charAt(index);
            if (value != ' ' && value != '\t' && value != '\f') {
                return false;
            }
        }
        return true;
    }

    private int lineEnd(String text, int begin) {
        int lineFeed = text.indexOf('\n', begin);
        int carriageReturn = text.indexOf('\r', begin);
        if (lineFeed < 0) {
            return carriageReturn < 0 ? text.length() : carriageReturn;
        }
        if (carriageReturn < 0) {
            return lineFeed;
        }
        return Math.min(lineFeed, carriageReturn);
    }

    private String stripTrailingHorizontalWhitespace(String text) {
        int end = text.length();
        while (end > 0) {
            char value = text.charAt(end - 1);
            if (value != ' ' && value != '\t' && value != '\f') {
                break;
            }
            end--;
        }
        return text.substring(0, end);
    }

    private boolean sourceMultilineLogicalCondition(Expression condition) {
        return sourceMultilineLogicalConditionExpression(condition)
            && sourceShapePolicy.sourceMultilineLogicalCondition(condition);
    }

    private boolean sourceMultilineLogicalConditionExpression(Expression condition) {
        return sourceShapePolicy.logicalConditionExpression(condition);
    }

    private boolean commentedLogicalCondition(Expression condition) {
        return sourceMultilineLogicalConditionExpression(condition)
            && condition.getAllContainedComments().stream().anyMatch(LineComment.class::isInstance);
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

    /**
     * Reports condition-contained line comments that must be rendered by the broken condition path.
     *
     * <p>JavaParser can expose a line comment written inside a control condition as contained trivia rather than the
     * condition's own comment. Compact condition text would either drop or misplace that standalone line, so these
     * comments force structured condition rendering. Comments that start inside the following body or selector-owned
     * sibling are excluded by comparing against the next direct child range after the condition.
     */
    private boolean hasDetachedConditionLineComment(Expression condition) {
        return !detachedConditionLineComments(condition).isEmpty();
    }

    private List<LineComment> detachedConditionLineComments(Expression condition) {
        return java.util.stream.Stream.concat(
                condition.getAllContainedComments().stream(),
                recoveredLeadingConditionComments(condition).stream()
            )
                .distinct()
                .filter(LineComment.class::isInstance)
                .map(LineComment.class::cast)
                .filter(comment -> !lineCommentTrailsConditionContent(condition, comment))
                .filter(comment -> lineCommentBelongsToCondition(condition, comment))
                .sorted(CommentIndex.sourceOrderComparator())
                .toList();
    }

    /**
     * Recovers the condition's leading line comments that a whitespace perturbation re-bucketed onto the enclosing
     * control statement as orphans, so {@link #detachedConditionLineComments(Expression)} sees them alongside the
     * condition's own contained trivia. At the {@code @default} shape a simple (non-logical) condition's control
     * statement holds no such orphan, so this widens the input set by nothing and the broken-condition render gate stays
     * byte-identical.
     *
     * <p>Logical {@code &&}/{@code ||} conditions are intentionally excluded: their operand-leading comments are rendered
     * by the operand-by-operand path ({@link #brokenLogicalCondition(Expression)} / {@code brokenExpressionLines}), and a
     * multi-line logical condition's first operand-leading comment is already a control-statement orphan at the
     * {@code @default} shape. Feeding it into the line-based leading-comment render gate would hijack the logical layout
     * and drop the per-operand comments, so the logical case keeps its existing handling.
     */
    private List<Comment> recoveredLeadingConditionComments(Expression condition) {
        if (sourceMultilineLogicalConditionExpression(condition)) {
            return List.of();
        }
        return condition.getParentNode()
                .map(parent -> commentPlacement.leadingConditionComments(parent, condition))
                .orElseGet(List::of)
                .stream()
                .map(JavaCommentTrivia::comment)
                .toList();
    }

    private int contentBeginLine(Node node, int fallback) {
        int nodeBegin = CommentIndex.beginLine(node, fallback);
        return node.getChildNodes()
                .stream()
                .filter(child -> !(child instanceof Comment))
                .mapToInt(child -> contentBeginLine(child, CommentIndex.beginLine(child, nodeBegin)))
                .min()
                .orElse(nodeBegin);
    }

    private boolean lineCommentBelongsToCondition(Expression condition, Comment comment) {
        return condition.getParentNode()
                .flatMap(parent -> condition.getRange().flatMap(
                        conditionRange -> parent.getChildNodes()
                                .stream()
                                .filter(child -> child != condition)
                                .flatMap(child -> child.getRange().stream())
                                .filter(range -> CommentIndex.startsBefore(conditionRange, range))
                                .min(this::compareRangeBegins)
                ))
                .flatMap(nextRange -> comment.getRange().map(
                        commentRange -> CommentIndex.startsBefore(
                            commentRange,
                            nextRange
                        )
                ))
                .orElse(true);
    }

    private int compareRangeBegins(com.github.javaparser.Range left, com.github.javaparser.Range right) {
        int line = Integer.compare(left.begin.line, right.begin.line);
        if (line != 0) {
            return line;
        }
        return Integer.compare(left.begin.column, right.begin.column);
    }

    private boolean lineCommentTrailsConditionContent(Expression condition, Comment comment) {
        return condition.getRange()
                .flatMap(conditionRange -> comment.getRange().map(
                        commentRange -> commentRange.begin.line == conditionRange.begin.line
                                && commentRange.begin.column > conditionRange.begin.column
                ))
                .orElse(false);
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
