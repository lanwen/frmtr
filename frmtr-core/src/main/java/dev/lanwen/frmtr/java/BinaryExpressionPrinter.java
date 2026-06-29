package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.InstanceOfExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Renders binary expressions and the broken binary continuation lines reused by surrounding syntax printers.
 *
 * <p>This helper owns binary-expression layout: flat binary expression rendering, same-operator operand flattening,
 * start-versus-end operator placement, line comments between operands, precedence-preserving parentheses, end-position
 * method-call operand breaks, line comments before the first broken operand, and the cast-division continuation
 * exception used by assignment and initializer callers. The boundary exists because {@link JavaPrinter} selects binary
 * expressions through broad expression dispatch, while statements, switch guards, conditional expressions, lambdas,
 * method-call arguments, annotations, and field initializers all need the same binary continuation policy after their
 * own context has decided to break.
 *
 * <p>{@link JavaPrinter} still owns broad expression dispatch, assignment and annotation routing, parenthesized
 * wrapping, pragma/raw gates, and the caller-specific decision that a binary expression should be forced onto multiple
 * lines. {@link MethodCallPrinter} still owns method-call layout; this helper only asks for a broken call after it has
 * identified a binary operand that should break before an end-position operator.
 */
final class BinaryExpressionPrinter {

    private final CommentTracker comments;

    private final JavaCommentPlacementPolicy commentPlacement;

    private final FormatterOptions options;

    private final JavaFormatRule<Expression> expressionRenderer;

    private final Function<MethodCallExpr, Doc> brokenMethodCallRenderer;

    private final BiFunction<MethodCallExpr, String, Doc> brokenMethodCallWithClosingLineRenderer;

    private final Function<MethodCallExpr, Optional<Doc>> forcedMethodCallChainRenderer;

    private final SourceShapePolicy sourceShapePolicy;

    private final Function<Node, String> compact;

    private final Function<Node, String> compactWithoutOwnComment;

    private final ToIntFunction<String> continuationStatementWidth;

    private final ToIntFunction<String> blockStatementWidth;

    BinaryExpressionPrinter(
            CommentTracker comments,
            JavaCommentPlacementPolicy commentPlacement,
            FormatterOptions options,
            JavaFormatRule<Expression> expressionRenderer,
            Function<MethodCallExpr, Doc> brokenMethodCallRenderer,
            BiFunction<MethodCallExpr, String, Doc> brokenMethodCallWithClosingLineRenderer,
            Function<MethodCallExpr, Optional<Doc>> forcedMethodCallChainRenderer,
            SourceShapePolicy sourceShapePolicy,
            Function<Node, String> compact,
            Function<Node, String> compactWithoutOwnComment,
            ToIntFunction<String> continuationStatementWidth,
            ToIntFunction<String> blockStatementWidth
    ) {
        this.comments = comments;
        this.commentPlacement = commentPlacement;
        this.options = options;
        this.expressionRenderer = expressionRenderer;
        this.brokenMethodCallRenderer = brokenMethodCallRenderer;
        this.brokenMethodCallWithClosingLineRenderer = brokenMethodCallWithClosingLineRenderer;
        this.forcedMethodCallChainRenderer = forcedMethodCallChainRenderer;
        this.sourceShapePolicy = sourceShapePolicy;
        this.compact = compact;
        this.compactWithoutOwnComment = compactWithoutOwnComment;
        this.continuationStatementWidth = continuationStatementWidth;
        this.blockStatementWidth = blockStatementWidth;
    }

    Doc lines(Expression expression) {
        return lines(expression, false);
    }

    Doc lines(Expression expression, boolean forceBreak) {
        return lines(expression, forceBreak, false);
    }

    Doc nestedLines(Expression expression, boolean forceBreak) {
        return lines(expression, forceBreak, true);
    }

    Doc conditionLines(Expression expression, boolean forceBreak) {
        if (expression instanceof BinaryExpr binaryExpr && !isLogicalOperator(binaryExpr.getOperator())) {
            return nestedLines(expression, forceBreak);
        }
        return lines(expression, forceBreak);
    }

    /**
     * Builds the multi-line binary layout after a caller has decided the expression should break.
     *
     * <p>Only nested binaries with the same operator are flattened into one operand list. Mixed operators keep their own
     * expression docs so precedence-sensitive parentheses remain local to the operand renderer. The nested continuation
     * form indents every line after the first; callers use it when a binary expression is already subordinate to another
     * expression, such as a ternary condition or parenthesized {@code a || (b && c)} operand.
     */
    private Doc lines(Expression expression, boolean forceBreak, boolean nestedContinuation) {
        if (!(expression instanceof BinaryExpr binaryExpr)) {
            return expressionRenderer.format(expression);
        }
        if (!forceBreak && parenthesizedInnerWidth(compact.apply(binaryExpr)) <= options.lineWidth()) {
            return binaryExpression(binaryExpr);
        }
        if (hasLineComments(binaryExpr)) {
            return commentAwareLines(binaryExpr, nestedContinuation);
        }
        List<Expression> operands = new ArrayList<>();
        flattenBinaryExpression(binaryExpr, binaryExpr.getOperator(), operands);
        if (
            binaryExpr.getOperator() == BinaryExpr.Operator.AND
            && operands.size() == 2
            && operands.getFirst() instanceof InstanceOfExpr instanceOfExpr
            && parenthesizedInnerWidth(compact.apply(instanceOfExpr)) > options.lineWidth()
        ) {
            return Doc.concat(
                expressionRenderer.format(instanceOfExpr),
                Doc.text(" && "),
                expressionRenderer.format(operands.getLast())
            );
        }
        BinaryExpressionLine firstLine = binaryExpressionLine(binaryExpr.getOperator(), 0, operands.size());
        if (
            options.binaryOperatorPosition() == FormatterOptions.BinaryOperatorPosition.END
            && operands.size() == 2
            && operands.getFirst() instanceof MethodCallExpr methodCall
            && shouldBreakEndPositionMethodCallOperand(firstLine, methodCall)
            && continuationStatementWidth.applyAsInt(
                ") "
                    + binaryExpr.getOperator().asString()
                    + " "
                    + compact.apply(operands.getLast())
            ) <= options.lineWidth()
        ) {
            return Doc.concat(
                brokenMethodCallRenderer.apply(methodCall),
                Doc.text(" " + binaryExpr.getOperator().asString() + " "),
                expressionRenderer.format(operands.getLast())
            );
        }
        List<Doc> lines = new ArrayList<>();
        for (int i = 0; i < operands.size(); i++) {
            Expression operandExpression = operands.get(i);
            BinaryExpressionLine binaryLine = binaryExpressionLine(binaryExpr.getOperator(), i, operands.size());
            Doc operand = binaryExpressionLineOperand(binaryLine, operandExpression, nestedContinuation && i > 0);
            if (shouldBreakEndPositionMethodCallOperand(binaryLine, operandExpression)) {
                MethodCallExpr methodCall = (MethodCallExpr) operandExpression;
                operand = brokenMethodCallRenderer.apply(methodCall);
            }
            lines.add(binaryLine.format(operand));
        }
        if (nestedContinuation) {
            List<Doc> nestedLines = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                Doc line = lines.get(i);
                nestedLines.add(i == 0 ? line : Doc.indent(Doc.concat(Doc.HARD_LINE, line)));
            }
            return Doc.concat(nestedLines);
        }
        return Doc.join(Doc.HARD_LINE, lines);
    }

    /**
     * Renders one operand in a broken binary line while preserving necessary parentheses.
     *
     * <p>{@code ||} operands that are {@code &&} groups are parenthesized as a readability boundary, and they use nested
     * binary lines when the inner group itself is too wide. Other nested binary operands are parenthesized only when the
     * operator-family rules require it to preserve the source expression's grouping.
     */
    private Doc binaryExpressionLineOperand(
            BinaryExpressionLine binaryLine,
            Expression operand,
            boolean nestedContinuationLine
    ) {
        if (
            binaryLine.operator() == BinaryExpr.Operator.OR
            && operand instanceof BinaryExpr binaryOperand
            && binaryOperand.getOperator() == BinaryExpr.Operator.AND
        ) {
            if (
                parenthesizedBinaryOperandWidth(binaryLine.operator(), compact.apply(binaryOperand))
                    > options.lineWidth()
            ) {
                return Doc.concat(Doc.text("("), nestedLines(binaryOperand, true), Doc.text(")"));
            }
            return Doc.concat(Doc.text("("), expressionRenderer.format(binaryOperand), Doc.text(")"));
        }
        if (
            operand instanceof EnclosedExpr enclosedOperand
            && enclosedOperand.getInner() instanceof BinaryExpr binaryOperand
            && (sourceShapePolicy.wasMultiline(enclosedOperand)
                || binaryLine.width(compact.apply(enclosedOperand)) > options.lineWidth())
        ) {
            return Doc.concat(Doc.text("("), nestedLines(binaryOperand, true), Doc.text(")"));
        }
        if (leadingOperatorMethodCallBinarySuffixCanAlign(binaryLine, operand)) {
            BinaryExpr binaryOperand = (BinaryExpr) operand;
            return brokenMethodCallWithClosingLineRenderer.apply(
                (MethodCallExpr) binaryOperand.getLeft(),
                methodCallBinaryClosingLine(binaryLine, binaryOperand)
            );
        }
        if (
            operand instanceof BinaryExpr binaryOperand
            && leadingOperatorMethodCallBinaryOperandShouldNest(binaryLine, binaryOperand)
        ) {
            return nestedLines(binaryOperand, true);
        }
        if (
            operand instanceof BinaryExpr binaryOperand
            && shouldParenthesizeNestedBinary(binaryLine.operator(), binaryOperand.getOperator())
        ) {
            return Doc.concat(Doc.text("("), expressionRenderer.format(binaryOperand), Doc.text(")"));
        }
        if (operand instanceof MethodCallExpr && operand.getAllContainedComments().isEmpty()) {
            MethodCallExpr methodCall = (MethodCallExpr) operand;
            if (methodCallOperandShouldBreak(binaryLine, methodCall, nestedContinuationLine)) {
                return forcedMethodCallChainRenderer.apply(methodCall)
                        .orElseGet(() -> brokenMethodCallRenderer.apply(methodCall));
            }
            String flat = compact.apply(operand);
            if (binaryLine.width(flat, nestedContinuationLine) <= options.lineWidth()) {
                return Doc.text(flat);
            }
            if (!binaryLine.hasLeadingOperator()) {
                return expressionRenderer.format(operand);
            }
            return forcedMethodCallChainRenderer.apply(methodCall)
                    .orElseGet(() -> brokenMethodCallRenderer.apply(methodCall));
        }
        return expressionRenderer.format(operand);
    }

    /**
     * Reports whether a leading-operator binary operand whose left is a breaking method call can keep its trailing
     * operator and right operand on the call's closing-paren line ({@code ) op rhs}) rather than nesting them onto a
     * separate indented continuation.
     *
     * <p>This is the operand-level sibling of {@link ReturnBinaryExpressionLayout}'s direct-return method-call-left
     * suffix: when {@code A && call(\n  args\n) > rhs} appears as a chain operand, the {@code > rhs} suffix should hug the
     * closing paren the same way a bare {@code return call(...) > rhs} does. The caller checks this <em>before</em>
     * {@link #leadingOperatorMethodCallBinaryOperandShouldNest}, so the closing-line suffix wins whenever it fits and the
     * call and right operand carry no comments; the nest form remains the fallback when the suffix cannot align (the
     * closing line would overflow) or comments force the operand through the comment-aware renderer. Keeping the suffix on
     * the closing line is also what makes the layout width-deterministic: it no longer depends on whether the source had
     * the call's argument list flat or pre-broken (issue #119), because both shapes converge on this single closing-line
     * suffix.
     */
    private boolean leadingOperatorMethodCallBinarySuffixCanAlign(BinaryExpressionLine binaryLine, Expression operand) {
        if (
            !binaryLine.hasLeadingOperator()
            || !(operand instanceof BinaryExpr binaryOperand)
            || !(binaryOperand.getLeft() instanceof MethodCallExpr methodCall)
            || !methodCall.getAllContainedComments().isEmpty()
            || !binaryOperand.getRight().getAllContainedComments().isEmpty()
            || !methodCallBinaryOperandShouldBreak(binaryLine, binaryOperand)
        ) {
            return false;
        }
        return continuationStatementWidth.applyAsInt(
            methodCallBinaryClosingLine(binaryLine, binaryOperand)
        ) <= options.lineWidth();
    }

    /**
     * Reports whether a broken-binary method-call operand should explode its argument list rather than render flat.
     *
     * <p>The decision is width- and AST-deterministic on purpose. A binary operand breaks its args only when the flat
     * operand would overflow the available width on its broken line, or — for a nested continuation whose call carries
     * breakable structure — when it sits within one indent unit of overflowing. It deliberately does <em>not</em> key off
     * {@code sourceShapePolicy.methodCallArgumentsSpanMultipleLines(methodCall)}: that is a source-shape signal (true iff
     * the call's args were already broken across lines in the input bytes), not a fixed property of the AST. Keying the
     * break on it made the layout depend on the previous pass's incidental line shape, so once a pass exploded an
     * operand's args the next pass re-observed the broken shape and re-exploded, producing two different stable outputs
     * for the same tree (flat vs args-exploded) and, near the width boundary, a layout that never converged. This is the
     * same source-shape-coupling root cause as the #98/#117 wrap-convergence family (issue #119); the fit decision must
     * be computed from the flat width, not from how the author happened to wrap the call.
     */
    private boolean methodCallOperandShouldBreak(
            BinaryExpressionLine binaryLine,
            MethodCallExpr methodCall,
            boolean nestedContinuationLine
    ) {
        String flat = compact.apply(methodCall);
        return binaryLine.width(flat, nestedContinuationLine) > options.lineWidth()
            || (nestedContinuationLine
                && methodCallHasBreakableStructure(methodCall)
                && binaryLine.width(flat, nestedContinuationLine) > options.lineWidth() - options.indentUnit().length());
    }

    /**
     * Reports whether a leading-operator method-call binary operand should break its left call's argument list.
     *
     * <p>Like {@link #methodCallOperandShouldBreak}, this is width-deterministic and intentionally ignores the
     * source-shape {@code methodCallArgumentsSpanMultipleLines} signal (see that method for the #119 rationale): the
     * operand explodes its args only when the flat operand overflows the broken line, or sits at the boundary while the
     * call carries breakable structure.
     */
    private boolean methodCallBinaryOperandShouldBreak(BinaryExpressionLine binaryLine, BinaryExpr binaryOperand) {
        MethodCallExpr methodCall = (MethodCallExpr) binaryOperand.getLeft();
        return binaryLine.width(compact.apply(binaryOperand)) > options.lineWidth()
            || (methodCallHasBreakableStructure(methodCall)
                && binaryLine.width(compact.apply(binaryOperand)) >= options.lineWidth());
    }

    private boolean methodCallHasBreakableStructure(MethodCallExpr methodCall) {
        return methodCall.getArguments().size() > 1
            || methodCall
                    .getArguments()
                    .stream()
                    .anyMatch(argument -> argument instanceof BinaryExpr
                            || argument instanceof MethodCallExpr
                            || argument instanceof ObjectCreationExpr
                    )
            || methodCall.getScope().filter(MethodCallExpr.class::isInstance).isPresent();
    }

    /**
     * Reports whether a leading-operator method-call binary operand should nest its inner chain rather than align its
     * closing line with the trailing operator.
     *
     * <p>This break-shape decision is width-deterministic and intentionally ignores the source-shape
     * {@code methodCallArgumentsSpanMultipleLines} signal (see {@link #methodCallOperandShouldBreak} for the #119
     * rationale). The previous {@code !methodCallArgumentsSpanMultipleLines(...)} guard nested only when the call's args
     * were <em>not</em> already broken in the input, so two identical trees nested differently depending on how the
     * author had wrapped the call — the same source-shape coupling that prevented convergence. The operand now nests
     * whenever it is a leading-operator multi-argument call that overflows the available width, independent of the input
     * line shape.
     *
     * <p>This is the fallback for {@link #leadingOperatorMethodCallBinarySuffixCanAlign}, which the caller checks first:
     * when the operator and right operand can ride the call's closing-paren line ({@code ) op rhs}) that suffix shape
     * wins, and the operand only nests here when that suffix cannot align (the closing line would overflow).
     */
    private boolean leadingOperatorMethodCallBinaryOperandShouldNest(
            BinaryExpressionLine binaryLine,
            BinaryExpr binaryOperand
    ) {
        return binaryLine.hasLeadingOperator()
            && binaryOperand.getLeft() instanceof MethodCallExpr methodCall
            && methodCall.getArguments().size() > 1
            && binaryLine.width(compact.apply(binaryOperand)) > options.lineWidth();
    }

    private String methodCallBinaryClosingLine(BinaryExpressionLine binaryLine, BinaryExpr binaryOperand) {
        String padding = binaryLine.hasFollowingOperand() ? " ".repeat(binaryLine.leadingOperatorWidth()) : "";
        return padding + ") " + binaryOperand.getOperator().asString() + " " + compact.apply(binaryOperand.getRight());
    }

    private BinaryExpressionLine binaryExpressionLine(
            BinaryExpr.Operator operator,
            int index,
            int operandCount
    ) {
        if (options.binaryOperatorPosition() == FormatterOptions.BinaryOperatorPosition.START) {
            return new BinaryExpressionLine(
                operator,
                index == 0 ? "" : operator.asString() + " ",
                "",
                index < operandCount - 1
            );
        }
        return new BinaryExpressionLine(
            operator,
            "",
            index < operandCount - 1 ? " " + operator.asString() : "",
            index < operandCount - 1
        );
    }

    /**
     * Keeps cast-division assignments on a flat continuation when the post-break continuation still fits.
     *
     * <p>For {@code x = (Type) value / divisor}, breaking the binary tree can make the cast look detached from the value
     * being divided. Callers use this predicate to put the whole binary expression on the continuation line instead.
     */
    boolean shouldKeepCastDivisionContinuationFlat(BinaryExpr expression) {
        return expression.getOperator() == BinaryExpr.Operator.DIVIDE
            && expression.getLeft() instanceof CastExpr
            && blockStatementWidth.applyAsInt(compact.apply(expression)) <= options.lineWidth();
    }

    /**
     * Reports whether a method-call operand should break before an end-position operator.
     *
     * <p>With trailing operators, a wide call followed by {@code +}, {@code &&}, or another binary operator can make the
     * operator appear visually separated from the operand. This check lets the binary renderer first break the call
     * arguments, then attach the operator after that broken operand.
     *
     * <p>The break fires only for a multi-argument call that overflows the available width. It deliberately does not key
     * off {@code sourceShapePolicy.methodCallArgumentsSpanMultipleLines(methodCall)} (see
     * {@link #methodCallOperandShouldBreak} for the #119 rationale): keying a single-argument call's break on whether the
     * author had already wrapped its one argument made the operand shape depend on the input's incidental line shape
     * rather than a fixed property of the AST. A single-argument overflowing call now falls through to the deterministic
     * operand renderer instead.
     */
    private boolean shouldBreakEndPositionMethodCallOperand(BinaryExpressionLine binaryLine, Expression operand) {
        return binaryLine.hasTrailingOperator()
            && operand instanceof MethodCallExpr methodCall
            && methodCall.getArguments().size() > 1
            && binaryLine.width(compact.apply(methodCall)) > options.lineWidth();
    }

    private final class BinaryExpressionLine {

        private final BinaryExpr.Operator operator;

        private final String leadingOperator;

        private final String trailingOperator;

        private final boolean followingOperand;

        private BinaryExpressionLine(
                BinaryExpr.Operator operator,
                String leadingOperator,
                String trailingOperator,
                boolean followingOperand
        ) {
            this.operator = operator;
            this.leadingOperator = leadingOperator;
            this.trailingOperator = trailingOperator;
            this.followingOperand = followingOperand;
        }

        BinaryExpr.Operator operator() {
            return operator;
        }

        boolean hasLeadingOperator() {
            return !leadingOperator.isEmpty();
        }

        int leadingOperatorWidth() {
            return leadingOperator.length();
        }

        boolean hasFollowingOperand() {
            return followingOperand;
        }

        boolean hasTrailingOperator() {
            return !trailingOperator.isEmpty();
        }

        Doc format(Doc operand) {
            return Doc.concat(Doc.text(leadingOperator), operand, Doc.text(trailingOperator));
        }

        int width(String operand) {
            return width(operand, false);
        }

        int width(String operand, boolean nestedContinuationLine) {
            String line = leadingOperator + operand + trailingOperator;
            if (nestedContinuationLine) {
                return continuationStatementWidth.applyAsInt(options.indentUnit() + line);
            }
            return continuationStatementWidth.applyAsInt(line);
        }
    }

    /**
     * Reports whether a binary chain carries between-operand comments that only the comment-aware multi-line render can
     * place, so callers route it through {@link #linesWithComments(BinaryExpr)} instead of the comment-stripped layouts.
     *
     * <p>Despite the name kept for the original line-comment gate this is the broader "would the plain shape drop a
     * comment" question. It fires on any contained {@code //} line comment (the historic case) and, additionally, on an
     * inline {@code /* ... *}{@code /} block comment that sits between two flattened operands. The block arm is what makes
     * issue #93 route correctly: JavaParser parks such a block comment as the following operand's own trivia, which the
     * comment-stripped {@code lines}/flat layouts discard, while {@link #commentedBinaryLines(BinaryExpr)} re-offers it on
     * the operand boundary. A node's own leading or trailing-after-last block comment is not between two operands, so it
     * is left to its existing slot and this gate stays {@code false} for it.
     */
    boolean hasLineComments(BinaryExpr expression) {
        return commentPlacement.hasContainedLineComments(expression)
            || hasBetweenOperandBlockComments(expression);
    }

    private boolean hasBetweenOperandBlockComments(BinaryExpr expression) {
        List<Expression> operands = new ArrayList<>();
        flattenBinaryExpression(expression, expression.getOperator(), operands);
        for (int i = 0; i < operands.size() - 1; i++) {
            if (!commentPlacement.blockCommentsBetween(expression, operands.get(i), operands.get(i + 1)).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Rebuilds broken binary lines when line comments appear between operands.
     *
     * <p>JavaParser attaches comments to nearby nodes rather than to operator tokens. The formatter therefore flattens
     * same-operator operands, finds comments that fall between each neighboring pair, keeps same-line trailing comments
     * next to the previous operand for end-position operators, and emits the remaining comments as their own lines.
     */
    Doc linesWithComments(BinaryExpr expression) {
        return Doc.join(Doc.HARD_LINE, commentedBinaryLines(expression));
    }

    /**
     * Renders the comment-aware broken binary lines for the forced/nested continuation entry points, choosing the join
     * policy that matches the comment-free {@code lines}/{@code nestedLines} shape it replaces.
     *
     * <p>The broken {@code lines(...)} path (taken once the fit branch is ruled out — i.e. the caller forced a break or
     * the flat shape overflows) builds the comment-<em>stripped</em> layout: it flattens operands and emits one operand
     * per line without ever consulting {@link #betweenOperandComments(BinaryExpr, Expression, Expression)}. Only the fit
     * branch routes through {@link #binaryExpression(BinaryExpr)} and its comment gate, so a binary forced to break by a
     * lambda body or annotation member value (callers that always pass {@code forceBreak}) dropped every between-operand
     * comment. Routing the broken path through {@link #commentedBinaryLines(BinaryExpr)} here lets all such callers
     * preserve those comments without each re-checking the gate, the same way the field-initializer and
     * enclosed-expression callers already do via {@link #linesWithComments(BinaryExpr)}. The check sits after the fit
     * branch so a flat-fitting binary still delegates to {@link #binaryExpression(BinaryExpr)}, which keeps the
     * lone-immediate-left line-comment binary on its flat-with-continuation shape unchanged.
     *
     * <p>The two forms differ only in indentation: the top-level join ({@link #linesWithComments(BinaryExpr)}) places
     * every line at the same column, while the nested-continuation form indents every line after the first — mirroring
     * the comment-free {@code nestedContinuation} branch of {@code lines(...)} and the nested broken sub-chain shape in
     * {@link #nestedMixedOperatorOperandDoc}.
     */
    private Doc commentAwareLines(BinaryExpr expression, boolean nestedContinuation) {
        if (!nestedContinuation) {
            return linesWithComments(expression);
        }
        List<Doc> commentedLines = commentedBinaryLines(expression);
        List<Doc> nestedLines = new ArrayList<>();
        for (int i = 0; i < commentedLines.size(); i++) {
            Doc line = commentedLines.get(i);
            nestedLines.add(i == 0 ? line : Doc.indent(Doc.concat(Doc.HARD_LINE, line)));
        }
        return Doc.concat(nestedLines);
    }

    /**
     * Renders a comment-bearing binary chain flat on a single line when every between-operand comment is an inline
     * {@code /* ... *}{@code /} block comment that trails its operand on the operand's own source line.
     *
     * <p>This is the flat sibling of {@link #linesWithComments(BinaryExpr)}: a chain whose only contained comments are
     * inline block comments does not have to break, because a {@code /* ... *}{@code /} comment can sit on the line between
     * two operands ({@code a /* x *}{@code / && b}) without swallowing the rest of the line the way a {@code //} comment
     * would. Callers measure {@link #flatLineWithCommentsWidth(BinaryExpr)} first and use this only when the flat line
     * fits; the same caller falls back to {@link #linesWithComments(BinaryExpr)} otherwise. Returns {@link Optional#empty()}
     * when any comment cannot live inline (a {@code //} line comment, or a block comment that source placed on its own line
     * rather than trailing its operand), so such a chain still breaks one operand per line.
     */
    Optional<Doc> flatLineWithComments(BinaryExpr expression) {
        return flatCommentAwareDoc(expression, comments::comment);
    }

    /**
     * Measures the rendered width of {@link #flatLineWithComments(BinaryExpr)} (comment-stripped operand text, operators,
     * and the inline block-comment spellings exactly as they render), or {@link Integer#MAX_VALUE} when the chain cannot
     * render flat with comments at all so the caller never picks the flat shape for it.
     *
     * <p>Measurement must not claim a comment, so it renders each inline comment through the non-claiming
     * {@link JavaFormatter#commentDoc(JavaCommentTrivia)} rather than the claim-once {@link CommentTracker#comment} the doc
     * builder uses; otherwise measuring the flat width would consume the comment before {@link #flatLineWithComments}
     * re-offers it for the committed render, leaving the committed render with an empty comment.
     */
    int flatLineWithCommentsWidth(BinaryExpr expression) {
        return flatCommentAwareDoc(expression, JavaFormatter::commentDoc)
                .map(BinaryExpressionPrinter::flatDocWidth)
                .orElse(Integer.MAX_VALUE);
    }

    private static int flatDocWidth(Doc doc) {
        return switch (doc) {
            case Doc.Text text -> text.value().length();
            case Doc.Concat concat -> concat.docs().stream().mapToInt(BinaryExpressionPrinter::flatDocWidth).sum();
            default -> Integer.MAX_VALUE;
        };
    }

    /**
     * Builds the flat comment-aware chain (operands joined by operators with each inline block comment trailing its
     * operand on the same line), rendering comments through {@code commentRenderer}, or {@link Optional#empty()} when any
     * operand carries a {@code //} line comment, a leading-first comment, or a between-operand comment that source did not
     * place inline on the previous operand's end line — i.e. any comment that cannot live on one flat line. The renderer is
     * a parameter so the committed render claims comments while the width measurement does not.
     */
    private Optional<Doc> flatCommentAwareDoc(
            BinaryExpr expression,
            Function<JavaCommentTrivia, Doc> commentRenderer
    ) {
        List<Expression> operands = new ArrayList<>();
        flattenBinaryExpression(expression, expression.getOperator(), operands);
        if (!lineCommentsBeforeFirstOperand(expression, operands.getFirst()).isEmpty()) {
            return Optional.empty();
        }
        List<Doc> pieces = new ArrayList<>();
        for (int i = 0; i < operands.size(); i++) {
            Expression operand = operands.get(i);
            if (operand.getAllContainedComments().stream().anyMatch(LineComment.class::isInstance)) {
                return Optional.empty();
            }
            if (i > 0) {
                pieces.add(Doc.text(" " + expression.getOperator().asString() + " "));
            }
            pieces.add(Doc.text(compactWithoutOwnComment.apply(operand)));
            if (i < operands.size() - 1) {
                List<JavaCommentTrivia> between = betweenOperandComments(expression, operand, operands.get(i + 1));
                List<JavaCommentTrivia> inline = commentPlacement.commentsStartingOnEndLine(operand, between);
                if (inline.size() != between.size() || inline.stream().anyMatch(JavaCommentTrivia::isLine)) {
                    return Optional.empty();
                }
                for (JavaCommentTrivia comment : inline) {
                    pieces.add(Doc.text(" "));
                    pieces.add(commentRenderer.apply(comment));
                }
            }
        }
        return Optional.of(Doc.concat(pieces));
    }

    /**
     * Builds the comment-aware broken lines for a binary chain, in source order, with no enclosing join policy.
     *
     * <p>Both the top-level {@link #linesWithComments(BinaryExpr)} (which joins with {@link Doc#HARD_LINE}) and the
     * nested-operand form {@link #nestedMixedOperatorOperandDoc} (which indents every line after the first to mirror the
     * comment-free {@code nestedLines(binaryOperand, true)} continuation) share this builder so the operand and
     * between-operand comment logic stays in one place.
     */
    private List<Doc> commentedBinaryLines(BinaryExpr expression) {
        List<Expression> operands = new ArrayList<>();
        flattenBinaryExpression(expression, expression.getOperator(), operands);
        List<Doc> lines = new ArrayList<>();
        if (!operands.isEmpty()) {
            lines.addAll(commentDocs(lineCommentsBeforeFirstOperand(expression, operands.getFirst())));
        }
        for (int i = 0; i < operands.size(); i++) {
            Expression operand = operands.get(i);
            Doc operandDoc = binaryLineOperandBody(expression.getOperator(), operand);
            List<JavaCommentTrivia> between = i < operands.size() - 1
                ? betweenOperandComments(expression, operand, operands.get(i + 1))
                : List.of();
            List<JavaCommentTrivia> sameLineComments = commentPlacement.commentsStartingOnEndLine(operand, between);
            Doc line = binaryLineWithOperatorAndComments(
                expression.getOperator(),
                operandDoc,
                i,
                operands.size(),
                sameLineComments
            );
            between = between.stream()
                    .filter(comment -> !sameLineComments.contains(comment))
                    .toList();
            lines.add(line);
            if (i < operands.size() - 1) {
                lines.addAll(commentDocs(between));
            }
        }
        return lines;
    }

    /**
     * Assembles one broken binary line from its operand body, the operator slot, and the comments that trail the operand
     * on its own source line.
     *
     * <p>The operator and the inline same-line comments interleave differently per operator position and per comment kind,
     * which is the whole reason this is not a plain "operand then operator then comment" concat:
     *
     * <ul>
     *   <li><b>Start position</b> ({@code || operand}): the operator is a prefix on this line and every same-line comment
     *       trails the operand, so the line reads {@code || operand /* c *}{@code /}. Both comment kinds sit after the
     *       operand identically.
     *   <li><b>End position</b> ({@code operand ||}): an inline {@code /* ... *}{@code /} block comment keeps its source
     *       position <em>before</em> the trailing operator ({@code operand /* c *}{@code / ||}), while a {@code //} line
     *       comment must stay last on the line ({@code operand || // c}) because it runs to end-of-line and would
     *       otherwise swallow the operator. That split is why block and line same-line comments are placed on opposite
     *       sides of the end-position operator.
     * </ul>
     */
    private Doc binaryLineWithOperatorAndComments(
            BinaryExpr.Operator operator,
            Doc operandDoc,
            int index,
            int operandCount,
            List<JavaCommentTrivia> sameLineComments
    ) {
        boolean hasTrailingOperator =
            options.binaryOperatorPosition() == FormatterOptions.BinaryOperatorPosition.END
            && index < operandCount - 1;
        Doc line = options.binaryOperatorPosition() == FormatterOptions.BinaryOperatorPosition.START
            ? (index == 0 ? operandDoc : Doc.concat(Doc.text(operator.asString() + " "), operandDoc))
            : operandDoc;
        for (JavaCommentTrivia comment : sameLineComments) {
            if (hasTrailingOperator && comment.isBlock()) {
                line = Doc.concat(line, Doc.text(" "), comments.comment(comment));
            }
        }
        if (hasTrailingOperator) {
            line = Doc.concat(line, Doc.text(" " + operator.asString()));
        }
        for (JavaCommentTrivia comment : sameLineComments) {
            if (!(hasTrailingOperator && comment.isBlock())) {
                line = Doc.concat(line, Doc.text(" "), comments.comment(comment));
            }
        }
        return line;
    }

    /**
     * Collects the line <em>and</em> block comments that sit between two neighboring operands, in source order.
     *
     * <p>JavaParser attaches a {@code //} line comment between operands as a contained orphan of the chain, but it parks
     * an inline {@code /* ... *}{@code /} block comment ({@code a == 1 /* note *}{@code / || a == 2}) as the own comment
     * of the operand that follows it. The line-only {@link JavaCommentPlacementPolicy#lineCommentsBetween(Node, Node,
     * Node)} query never sees that block comment, and {@link #binaryLineOperandDoc} renders the following operand from
     * comment-stripped compact text, so the block comment is dropped on re-wrap. Unioning both kinds here surfaces them
     * to the same between-operand placement {@link #commentedBinaryLines(BinaryExpr)} already uses for line comments:
     * a comment that starts on {@code operand}'s end line trails it inline, otherwise it stands on its own line.
     */
    private List<JavaCommentTrivia> betweenOperandComments(BinaryExpr expression, Expression operand, Expression next) {
        return java.util.stream.Stream.concat(
                commentPlacement.lineCommentsBetween(expression, operand, next).stream(),
                commentPlacement.blockCommentsBetween(expression, operand, next).stream()
            )
                .distinct()
                .sorted(
                    java.util.Comparator.comparing(
                        JavaCommentTrivia::comment,
                        CommentIndex.sourceOrderComparator()
                    )
                )
                .toList();
    }

    private List<JavaCommentTrivia> lineCommentsBeforeFirstOperand(BinaryExpr expression, Expression firstOperand) {
        List<JavaCommentTrivia> beforeFirst = new ArrayList<>();
        beforeFirst.addAll(commentPlacement.adjacentLeadingLineComments(expression));
        commentPlacement.ownComment(expression, JavaCommentTrivia::isLine)
                .filter(comment -> comment.startsBefore(firstOperand))
                .ifPresent(beforeFirst::add);
        beforeFirst.addAll(commentPlacement.lineCommentsBeforeFirst(expression, firstOperand));
        beforeFirst.addAll(leadingControlConditionLineComments(expression));
        return beforeFirst.stream()
                .distinct()
                .sorted(
                    java.util.Comparator.comparing(
                        JavaCommentTrivia::comment,
                        CommentIndex.sourceOrderComparator()
                    )
                )
                .toList();
    }

    /**
     * Recovers the line comments that lead this logical condition's first operand but that a whitespace perturbation
     * re-bucketed onto the enclosing control statement as orphans rather than leaving as the condition's own contained
     * trivia.
     *
     * <p>This is the operand-by-operand renderer's source-order sibling of the three line-keyed sources above. A
     * multi-line {@code &&}/{@code ||} condition renders here (via {@link #linesWithComments(BinaryExpr)}), not through
     * the line-based broken-condition gate, so the leading-condition orphan recovery {@link ControlConditionPrinter}
     * deliberately skips for logical conditions must be applied at this operand boundary instead. We add it only when the
     * binary expression is the condition/selector of its enclosing {@link IfStmt}/{@link WhileStmt}/{@link SwitchStmt}/
     * {@link SwitchExpr}, so a nested binary never claims its parent statement's leading comments.
     *
     * <p>At the {@code @default} shape the first operand-leading comment is either already recovered by the line-keyed
     * sources (same comment identity, removed by {@code distinct()}) or is the binary expression's own contained trivia
     * (which the control statement does not hold as an orphan), so this adds nothing and the layout stays byte-identical.
     */
    private List<JavaCommentTrivia> leadingControlConditionLineComments(BinaryExpr expression) {
        return expression.getParentNode()
                .filter(parent -> controlConditionIs(parent, expression))
                .map(parent -> commentPlacement.leadingConditionComments(parent, expression))
                .orElseGet(List::of);
    }

    private boolean controlConditionIs(Node parent, BinaryExpr expression) {
        return switch (parent) {
            case IfStmt ifStmt -> ifStmt.getCondition() == expression;
            case WhileStmt whileStmt -> whileStmt.getCondition() == expression;
            case SwitchStmt switchStmt -> switchStmt.getSelector() == expression;
            case SwitchExpr switchExpr -> switchExpr.getSelector() == expression;
            default -> false;
        };
    }

    /**
     * Renders one operand's body for a comment-aware broken binary line, without the surrounding operator slot.
     *
     * <p>The operator (start prefix or end suffix) and the operand's inline same-line comments are interleaved by
     * {@link #binaryLineWithOperatorAndComments} so an end-position block comment can keep its source position before the
     * operator; this method only produces the operand text, recursing for nested mixed-operator sub-chains, enclosed
     * leading-line-comment operands, and line-commented operands the same way the original line builder did.
     */
    private Doc binaryLineOperandBody(BinaryExpr.Operator operator, Expression operand) {
        return nestedMixedOperatorOperandDoc(operator, operand)
            .or(() -> enclosedOperandWithLeadingLineComments(operand))
            .orElseGet(
                () ->
                    operand.getAllContainedComments().stream().anyMatch(LineComment.class::isInstance)
                        ? expressionRenderer.format(operand)
                        : Doc.text(compactWithoutOwnComment.apply(operand))
            );
    }

    /**
     * Breaks a nested mixed-operator logical sub-chain ({@code a && b} under an enclosing {@code ||}, or vice versa)
     * inside the comment-aware operand renderer so its own inter-operand line comments can surface and so it never
     * produces an over-width line.
     *
     * <p>{@link #linesWithComments(BinaryExpr)} flattens only the enclosing same-operator chain, so a nested chain that
     * uses the <em>other</em> logical operator arrives here as one opaque operand. Routing it through
     * {@link #expressionRenderer} (or compact text) would render it flat on a single line: any {@code //} comment that
     * sits between the inner operands would be dropped, and a wide sub-chain would overflow
     * {@link FormatterOptions#lineWidth()} and force a non-idempotent second-pass re-wrap. We therefore parenthesize it
     * (the readability boundary the comment-free {@link #binaryExpressionLineOperand} path already adds for
     * {@code ||(&&)}) and rebuild it through the comment-aware {@link #commentedBinaryLines(BinaryExpr)} builder — not
     * {@link #binaryExpression(BinaryExpr)} — so the inner chain is itself flattened and its {@code lineCommentsBetween}
     * query runs, emitting the inter-operand comment on its own line. The parentheses hug the content the same way the
     * comment-free {@code nestedLines(binaryOperand, true)} form does: {@code (} precedes the first operand and {@code )}
     * trails the last, with every line after the first indented as a continuation.
     *
     * <p>The gate is essential to source-shape preservation and golden stability: we break here ONLY when the sub-chain
     * carries line comments or would overflow when parenthesized. A comment-free, width-fitting nested sub-chain returns
     * {@link Optional#empty()} so the caller keeps its existing inline rendering byte-for-byte.
     *
     * <p>The {@link EnclosedExpr} arm is what makes the fix idempotent: our own first-pass output parenthesizes the
     * broken sub-chain, so a second pass re-parses it as an enclosed logical binary whose inter-operand comment again
     * sits between the inner operands (not before the inner expression, so {@link #enclosedOperandWithLeadingLineComments}
     * does not see it). We unwrap that enclosure and rebuild the same hugging-paren broken form. We only take this arm
     * when the enclosed inner is itself a logical binary, so an enclosed single operand with a leading comment stays with
     * {@link #enclosedOperandWithLeadingLineComments}.
     */
    private Optional<Doc> nestedMixedOperatorOperandDoc(BinaryExpr.Operator operator, Expression operand) {
        if (!isLogicalOperator(operator)) {
            return Optional.empty();
        }
        BinaryExpr nestedBinary;
        if (
            operand instanceof BinaryExpr bareBinary
            && isLogicalOperator(bareBinary.getOperator())
            && bareBinary.getOperator() != operator
        ) {
            nestedBinary = bareBinary;
        } else if (
            operand instanceof EnclosedExpr enclosed
            && enclosed.getInner() instanceof BinaryExpr enclosedBinary
            && isLogicalOperator(enclosedBinary.getOperator())
        ) {
            nestedBinary = enclosedBinary;
        } else {
            return Optional.empty();
        }
        boolean overflows =
            parenthesizedBinaryOperandWidth(operator, compact.apply(nestedBinary)) > options.lineWidth();
        if (!hasLineComments(nestedBinary) && !overflows) {
            return Optional.empty();
        }
        List<Doc> innerLines = commentedBinaryLines(nestedBinary);
        List<Doc> nestedLines = new ArrayList<>();
        for (int i = 0; i < innerLines.size(); i++) {
            Doc line = innerLines.get(i);
            nestedLines.add(i == 0 ? line : Doc.indent(Doc.concat(Doc.HARD_LINE, line)));
        }
        return Optional.of(Doc.concat(Doc.text("("), Doc.concat(nestedLines), Doc.text(")")));
    }

    private Optional<Doc> enclosedOperandWithLeadingLineComments(Expression operand) {
        if (!(operand instanceof EnclosedExpr enclosedOperand)) {
            return Optional.empty();
        }
        List<JavaCommentTrivia> leading = commentPlacement.lineCommentsBeforeFirst(
            enclosedOperand,
            enclosedOperand.getInner()
        );
        if (leading.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(Doc.concat(
            Doc.text("("),
            Doc.indent(Doc.concat(
                Doc.HARD_LINE,
                Doc.join(
                    Doc.HARD_LINE,
                    java.util.stream.Stream.concat(
                        commentDocs(leading).stream(),
                        java.util.stream.Stream.of(expressionRenderer.format(enclosedOperand.getInner()))
                    ).toList()
                )
            )),
            Doc.HARD_LINE,
            Doc.text(")")
        ));
    }

    private List<Doc> commentDocs(List<JavaCommentTrivia> sourceComments) {
        // Operand comments can be reached from more than one binary render (a flat measurement render and the committed
        // broken render share the same operand subtree). Skip comments already printed by an earlier traversal path so
        // this render does not duplicate-claim them; output is unchanged because the first claimant placed the comment and
        // a re-offer only ever rendered empty.
        return sourceComments.stream()
                .filter(trivia -> !comments.isPrinted(trivia))
                .map(comments::comment)
                .filter(doc -> doc != Doc.EMPTY)
                .toList();
    }

    /**
     * Measures an expression as if it were inside a broken parenthesized continuation.
     */
    private int parenthesizedInnerWidth(String text) {
        return (options.indentUnit().length() * 2) + text.length();
    }

    private int parenthesizedBinaryOperandWidth(BinaryExpr.Operator operator, String text) {
        return continuationStatementWidth.applyAsInt(operator.asString() + " (" + text + ")");
    }

    /**
     * Flattens only adjacent binary nodes that use the same operator.
     *
     * <p>This preserves source grouping for mixed-operator expressions while allowing long homogeneous chains like
     * {@code a && b && c} or {@code a + b + c} to align as one continuation list.
     */
    private void flattenBinaryExpression(
            Expression expression,
            BinaryExpr.Operator operator,
            List<Expression> operands
    ) {
        if (expression instanceof BinaryExpr binaryExpr && binaryExpr.getOperator() == operator) {
            flattenBinaryExpression(binaryExpr.getLeft(), operator, operands);
            flattenBinaryExpression(binaryExpr.getRight(), operator, operands);
            return;
        }
        operands.add(expression);
    }

    /**
     * Renders the flat binary shape selected by normal expression dispatch.
     *
     * <p>When a line comment is attached to the left operand, the operator stays on the first line with that comment
     * and the right operand moves to an indented continuation so the comment remains visually attached to the same
     * operand as it was in source.
     *
     * <p>The flat shape can only carry the single immediate-left operand line comment. A {@code //} comment that sits
     * deeper in a same-operator chain — between any other neighboring operand pair, such as {@code a + // x}
     * {@code b + // y} {@code c} — has no place in this layout and would otherwise be silently dropped. Whenever a
     * contained line comment exists that this flat shape would not emit, we hand off to the comment-aware
     * {@link #commentedBinaryLines(BinaryExpr)} multi-line render, which flattens the chain and offers every
     * between-operand comment. A binary with no line comments (or whose only line comment is the immediate-left one the
     * flat shape already prints) is untouched and renders byte-for-byte as before.
     */
    Doc binaryExpression(BinaryExpr expression) {
        Optional<JavaCommentTrivia> leftLineComment = commentPlacement.ownComment(
            expression.getLeft(),
            JavaCommentTrivia::isLine
        );
        if (flatRenderWouldDropLineComment(expression, leftLineComment)) {
            return linesWithComments(expression);
        }
        if (leftLineComment.isEmpty()) {
            return Doc.concat(
                binaryLeftOperand(expression),
                Doc.text(" " + expression.getOperator().asString() + " "),
                binaryRightOperand(expression)
            );
        }
        String operator = " " + expression.getOperator().asString() + " ";
        return Doc.concat(
            Doc.text(compactWithoutOwnComment.apply(expression.getLeft()) + operator),
            comments.comment(leftLineComment.orElseThrow()),
            Doc.indent(Doc.concat(Doc.HARD_LINE, binaryRightOperand(expression)))
        );
    }

    /**
     * Reports whether the flat {@link #binaryExpression(BinaryExpr)} layout would drop a contained comment.
     *
     * <p>The flat layout can render at most one comment: the immediate-left operand's own line comment
     * ({@code leftLineComment}). Any other comment contained in the chain — most commonly a between-operand comment that
     * JavaParser attached to a deeper operand of a flattened same-operator chain — would have nowhere to go. When such a
     * comment exists we route the binary through the comment-aware multi-line render instead. The check is intentionally
     * conservative: it returns {@code false} for a comment-free binary and for the lone-left-comment binary the flat shape
     * already preserves, so neither case changes layout.
     *
     * <p>A contained comment counts as "already emitted by the flat shape" only when it is the <em>same</em> JavaParser
     * node as {@code leftLineComment} — compared by parser identity ({@code comment() ==}), not by
     * {@link JavaCommentTrivia#equals(Object)} value/structural equality. When a between-operand comment has the same
     * text as a present left-operand comment (and there is no other distinct-text sibling), value equality would treat
     * the duplicate-text comment as the already-printed left comment, keep the flat shape, and silently drop the
     * between-operand comment. Identity comparison routes such a binary through the comment-aware multi-line render so
     * both same-text comments survive — the same value-vs-identity hazard the chain printer dedups against.
     *
     * <p>The {@link #hasBetweenOperandBlockComments(BinaryExpr)} arm covers the issue #93 case: an inline
     * {@code /* ... *}{@code /} block comment between operands ({@code a == 1 /* x *}{@code / || a == 2}) is parked by
     * JavaParser as the following operand's own trivia and the flat shape renders that operand from comment-stripped
     * compact text, so without this arm the block comment is dropped while the chain stays flat. Routing it to the
     * comment-aware render keeps the established convention that any between-operand comment, line or block, makes the
     * chain break one operand per line so each comment surfaces beside its operand.
     */
    private boolean flatRenderWouldDropLineComment(
            BinaryExpr expression,
            Optional<JavaCommentTrivia> leftLineComment
    ) {
        return commentPlacement.containedComments(expression).stream()
                .filter(JavaCommentTrivia::isLine)
                .anyMatch(comment -> leftLineComment
                        .filter(left -> left.comment() == comment.comment())
                        .isEmpty())
            || hasBetweenOperandBlockComments(expression);
    }

    private Doc binaryLeftOperand(BinaryExpr expression) {
        if (
            expression.getLeft() instanceof BinaryExpr leftBinary
            && (shouldParenthesizeLeftBinary(expression.getOperator(), leftBinary.getOperator())
                || shouldParenthesizeNestedBinary(expression.getOperator(), leftBinary.getOperator()))
        ) {
            return Doc.concat(Doc.text("("), expressionRenderer.format(leftBinary), Doc.text(")"));
        }
        return expressionRenderer.format(expression.getLeft());
    }

    private Doc binaryRightOperand(BinaryExpr expression) {
        if (
            expression.getRight() instanceof BinaryExpr rightBinary
            && shouldParenthesizeNestedBinary(expression.getOperator(), rightBinary.getOperator())
        ) {
            return Doc.concat(Doc.text("("), expressionRenderer.format(rightBinary), Doc.text(")"));
        }
        return expressionRenderer.format(expression.getRight());
    }

    /**
     * Handles the left side of division and remainder, where normal left associativity still needs extra grouping.
     *
     * <p>Cases such as {@code (a * b) / c} and {@code (a % b) / c} are only source-equivalent when the left nested
     * operation keeps its parentheses.
     */
    private boolean shouldParenthesizeLeftBinary(BinaryExpr.Operator outer, BinaryExpr.Operator inner) {
        return (outer == BinaryExpr.Operator.DIVIDE || outer == BinaryExpr.Operator.REMAINDER)
            && (inner == BinaryExpr.Operator.MULTIPLY || inner == BinaryExpr.Operator.REMAINDER);
    }

    /**
     * Decides whether a nested binary operator must keep explicit parentheses under an outer operator.
     *
     * <p>The branches mirror Java precedence and associativity groups in simple families: multiplicative, additive,
     * shift, bitwise, and equality. Each true branch means flattening or raw compact text would change how the
     * expression reads, so the nested expression stays wrapped.
     */
    private boolean shouldParenthesizeNestedBinary(BinaryExpr.Operator outer, BinaryExpr.Operator inner) {
        if (
            isMultiplicativeOperator(outer)
            && (inner == BinaryExpr.Operator.DIVIDE || inner == BinaryExpr.Operator.REMAINDER)
        ) {
            return true;
        }
        if (isAdditiveOperator(outer) && inner == BinaryExpr.Operator.REMAINDER) {
            return true;
        }
        if (isShiftOperator(outer) && (isArithmeticOperator(inner) || isShiftOperator(inner))) {
            return true;
        }
        if (
            isBitwiseOperator(outer)
            && (isShiftOperator(inner)
                || isRelationalOperator(inner)
                || isEqualityOperator(inner)
                || (outer == BinaryExpr.Operator.BINARY_OR
                    && (inner == BinaryExpr.Operator.BINARY_AND || inner == BinaryExpr.Operator.XOR))
                || (outer == BinaryExpr.Operator.XOR && inner == BinaryExpr.Operator.BINARY_AND))
        ) {
            return true;
        }
        return isEqualityOperator(outer) && isEqualityOperator(inner);
    }

    private boolean isShiftOperator(BinaryExpr.Operator operator) {
        return operator == BinaryExpr.Operator.LEFT_SHIFT
            || operator == BinaryExpr.Operator.SIGNED_RIGHT_SHIFT
            || operator == BinaryExpr.Operator.UNSIGNED_RIGHT_SHIFT;
    }

    private boolean isArithmeticOperator(BinaryExpr.Operator operator) {
        return operator == BinaryExpr.Operator.PLUS
            || operator == BinaryExpr.Operator.MINUS
            || operator == BinaryExpr.Operator.MULTIPLY
            || operator == BinaryExpr.Operator.DIVIDE
            || operator == BinaryExpr.Operator.REMAINDER;
    }

    private boolean isAdditiveOperator(BinaryExpr.Operator operator) {
        return operator == BinaryExpr.Operator.PLUS || operator == BinaryExpr.Operator.MINUS;
    }

    private boolean isMultiplicativeOperator(BinaryExpr.Operator operator) {
        return operator == BinaryExpr.Operator.MULTIPLY
            || operator == BinaryExpr.Operator.DIVIDE
            || operator == BinaryExpr.Operator.REMAINDER;
    }

    private boolean isRelationalOperator(BinaryExpr.Operator operator) {
        return operator == BinaryExpr.Operator.LESS
            || operator == BinaryExpr.Operator.GREATER
            || operator == BinaryExpr.Operator.LESS_EQUALS
            || operator == BinaryExpr.Operator.GREATER_EQUALS;
    }

    private boolean isBitwiseOperator(BinaryExpr.Operator operator) {
        return operator == BinaryExpr.Operator.BINARY_AND
            || operator == BinaryExpr.Operator.XOR
            || operator == BinaryExpr.Operator.BINARY_OR;
    }

    private boolean isEqualityOperator(BinaryExpr.Operator operator) {
        return operator == BinaryExpr.Operator.EQUALS || operator == BinaryExpr.Operator.NOT_EQUALS;
    }

    private boolean isLogicalOperator(BinaryExpr.Operator operator) {
        return operator == BinaryExpr.Operator.AND || operator == BinaryExpr.Operator.OR;
    }

    /**
     * Reports whether any nested binary in an expression needs explicit parentheses under the operator-family rules.
     *
     * <p>Callers use this before choosing a compact raw string for conditions or ternaries; when the predicate is true,
     * they ask expression rendering to rebuild the binary tree with the required parentheses instead.
     */
    boolean expressionHasParenthesizedNestedBinary(Expression expression) {
        return expression.findAll(BinaryExpr.class).stream().anyMatch(binary ->
            (binary.getLeft() instanceof BinaryExpr leftBinary
                && (shouldParenthesizeLeftBinary(binary.getOperator(), leftBinary.getOperator())
                    || shouldParenthesizeNestedBinary(binary.getOperator(), leftBinary.getOperator())))
                || (binary.getRight() instanceof BinaryExpr rightBinary
                    && shouldParenthesizeNestedBinary(binary.getOperator(), rightBinary.getOperator()))
        );
    }
}
