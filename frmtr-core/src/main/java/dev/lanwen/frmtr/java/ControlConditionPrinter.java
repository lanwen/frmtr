package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
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

    private final ControlConditionCommentLayout commentLayout;

    private final ToIntFunction<String> currentIndentedWidth;

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
        this.layoutWidth = layoutWidth;
        this.layoutDecisions = layoutDecisions;
        this.methodCallLayout = new ControlConditionMethodCallLayout(
            sourceShapePolicy,
            expressionRenderer,
            compact,
            compactJoin
        );
        this.commentLayout = new ControlConditionCommentLayout(
            comments,
            commentPlacement,
            sourceText,
            compact,
            compactWithoutOwnComment
        );
    }

    /**
     * Every comment-bearing route returns before the flat/broken pick, so the pick is comment-free by construction —
     * the same shape {@link #ifCondition(Expression)} already ranks. {@code closing} is the caller's trailing
     * literal (for example {@code " {}"}), reserved so the ranking sees the true rendered column.
     */
    Doc controlCondition(Expression expression, String closing) {
        if (commentedLogicalCondition(expression)) {
            return brokenCondition(expression);
        }
        Optional<Doc> commented = commentLayout.lineCommentCondition(expression);
        if (commented.isPresent()) {
            return commented.orElseThrow();
        }
        if (hasDetachedConditionLineComment(expression)) {
            return brokenCondition(expression);
        }
        String flat = compactWithOwnBlockComment(expression);
        Doc flatCandidate = Doc.text("(" + flat + ")");
        return Doc.reserving(
            Doc.conditionalGroup(List.of(flatCandidate, brokenCondition(expression))),
            closing.length() - 1
        );
    }

    /**
     * Renders the parenthesized condition for an {@code if} statement after the statement printer has selected if/else
     * grammar.
     *
     * <p>The width gate includes the {@code if} keyword and an empty block because if conditions have a slightly wider
     * surrounding line than loop tails. A logical condition that overflows its budget breaks through
     * {@link #brokenCondition(Expression)}, whose operand-by-operand binary layout explodes each over-wide operand by
     * width.
     */
    Doc ifCondition(Expression expression) {
        if (commentedLogicalCondition(expression)) {
            return brokenCondition(expression);
        }
        Optional<Doc> commented = commentLayout.commentedCondition(expression);
        if (commented.isPresent()) {
            return commented.orElseThrow();
        }
        if (hasDetachedConditionLineComment(expression)) {
            return brokenCondition(expression);
        }
        String flat = compact.apply(expression);
        int flatWidth = ifConditionLineWidth(expression, "if (" + flat + ") {}");
        if (
            expression instanceof MethodCallExpr methodCall
            && marginPreemptsMultiArgBreak(methodCall, flatWidth)
            && methodCallLayout.brokenConditionEligible(methodCall)
        ) {
            return brokenVerdict(expression);
        }
        // flatWidth still feeds explain attribution (recordIfConditionWidthBreak) as a deliberate diagnostics decouple;
        // the render verdict below ranks flat-vs-broken at the true rendered column instead.
        recordIfConditionWidthBreak(flat, flatWidth);
        Doc flatCandidate = expressionHasParenthesizedNestedBinary.test(expression)
            ? Doc.concat(Doc.text("("), expressionRenderer.apply(expression), Doc.text(")"))
            : Doc.text("(" + flat + ")");
        return Doc.reserving(
            Doc.conditionalGroup(List.of(flatCandidate, brokenVerdict(expression))),
            " {}".length()
        );
    }

    /**
     * Whether a multi-argument method-call condition should pre-emptively break before it strictly overflows: either it
     * already does, or a complex argument leaves too little margin to the width. Readability preference, not a width
     * verdict, so it stays a build-time probe.
     */
    private boolean marginPreemptsMultiArgBreak(MethodCallExpr methodCall, int flatWidth) {
        return methodCall.getArguments().size() > 1
            && (flatWidth > options.lineWidth()
                || (methodCallLayout.hasComplexArgument(methodCall)
                    && flatWidth > options.lineWidth() - options.indentUnit().length()));
    }

    /**
     * Ranks the broken candidates by whether their own opener fits the true rendered column — a broken method-call
     * condition, else a complemented method-call chain, else the generic broken condition, which always fits — instead
     * of pre-filtering with a {@code blockStatementWidth} text estimate. Every candidate forces its own line break, so
     * {@link Doc#bestFittingFirstLine(List, int[])} (first-line fit, not whole-doc flat fit) is the combinator that can
     * actually distinguish between them; {@link Doc#conditionalGroup} cannot, since a forced break never fits flat.
     */
    private Doc brokenVerdict(Expression expression) {
        List<Doc> alternatives = new ArrayList<>();
        List<Integer> priorities = new ArrayList<>();
        if (expression instanceof MethodCallExpr methodCall && methodCallLayout.brokenConditionEligible(methodCall)) {
            alternatives.add(methodCallLayout.brokenCondition(methodCall));
            priorities.add(2);
        }
        complementedMethodCallChainCondition(expression).ifPresent(chain -> {
            alternatives.add(chain);
            priorities.add(1);
        });
        alternatives.add(brokenCondition(expression));
        priorities.add(0);
        return alternatives.size() == 1
            ? alternatives.getFirst()
            : Doc.bestFittingFirstLine(alternatives, priorities.stream().mapToInt(Integer::intValue).toArray());
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
     * <p>Deriving the width from {@code range.begin.column} under-measured a tab-indented over-width condition (JavaParser
     * counts a leading {@code \t} as one column), so it was emitted flat, then genuinely overflowed and broke on pass two
     * — {@code format(format(x)) != format(x)}. Counting the enclosing block/type nesting through
     * {@link LayoutWidth#nodeLine} reproduces the rendered indentation regardless of source, so the fit/break decision is
     * stable across passes. The {@code currentIndentedWidth} floor keeps a condition directly under a member (no enclosing
     * block) measured against at least one unit.
     */
    private int ifConditionLineWidth(Expression expression, String line) {
        return Math.max(
            layoutWidth.nodeLine(expression, line),
            currentIndentedWidth.applyAsInt(line)
        );
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
                // A detached condition line comment is also offered by a neighboring condition render path. Anchoring
                // this slot to the distinct (expression, INTERLEAVED) key lets ownership disambiguate: if a neighbor owns
                // it, comment(...) returns Doc.EMPTY here (caught below); a comment no neighbor claimed is owned and
                // placed here — no build-order isPrinted skip needed.
                .map(comment -> comments.comment(comment, expression, OwnerSlot.INTERLEAVED))
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

    Doc closeParenTrailingLineComment(Expression condition) {
        return commentLayout.closeParenTrailingLineComment(condition);
    }

    private boolean sourceMultilineLogicalConditionExpression(Expression condition) {
        return sourceShapePolicy.logicalConditionExpression(condition);
    }

    private boolean commentedLogicalCondition(Expression condition) {
        return sourceMultilineLogicalConditionExpression(condition)
            && condition.getAllContainedComments().stream().anyMatch(LineComment.class::isInstance);
    }

    String compactWithOwnBlockComment(Expression expression) {
        return commentLayout.compactWithOwnBlockComment(expression);
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
                .filter(comment -> !commentLayout.lineCommentTrailsConditionContent(condition, comment))
                .filter(comment -> lineCommentBelongsToCondition(condition, comment))
                .sorted(CommentIndex.sourceOrderComparator())
                .toList();
    }

    /**
     * Recovers the condition's leading line comments that a whitespace perturbation re-bucketed onto the enclosing
     * control statement as orphans, so {@link #detachedConditionLineComments(Expression)} sees them alongside the
     * condition's own trivia. At {@code @default} a simple (non-logical) condition's statement holds no such orphan, so
     * this adds nothing and the render gate stays byte-identical.
     *
     * <p>Logical {@code &&}/{@code ||} conditions are excluded: their operand-leading comments render via the
     * operand-by-operand path ({@code brokenExpressionLines}), so feeding a logical condition's first operand-leading
     * comment into this line-based gate would hijack the logical layout and drop the per-operand comments.
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

}
