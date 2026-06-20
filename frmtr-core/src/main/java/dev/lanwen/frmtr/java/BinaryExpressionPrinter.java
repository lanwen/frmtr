package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.InstanceOfExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
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
 * method-call operand breaks, and the cast-division continuation exception used by assignment and initializer callers.
 * The boundary exists because {@link JavaPrinter} selects binary expressions through broad expression dispatch, while
 * statements, switch guards, conditional expressions, lambdas, method-call arguments, annotations, and field
 * initializers all need the same binary continuation policy after their own context has decided to break.
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

    private final SourceShape sourceShape;

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
            SourceShape sourceShape,
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
        this.sourceShape = sourceShape;
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
            && (sourceShape.spansMultipleLines(enclosedOperand)
                || binaryLine.width(compact.apply(enclosedOperand)) > options.lineWidth())
        ) {
            return Doc.concat(Doc.text("("), nestedLines(binaryOperand, true), Doc.text(")"));
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
        if (leadingOperatorMethodCallBinarySuffixCanAlign(binaryLine, operand)) {
            BinaryExpr binaryOperand = (BinaryExpr) operand;
            return brokenMethodCallWithClosingLineRenderer.apply(
                (MethodCallExpr) binaryOperand.getLeft(),
                methodCallBinaryClosingLine(binaryLine, binaryOperand)
            );
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

    private boolean methodCallOperandShouldBreak(
            BinaryExpressionLine binaryLine,
            MethodCallExpr methodCall,
            boolean nestedContinuationLine
    ) {
        String flat = compact.apply(methodCall);
        return sourceShape.methodCallArgumentsSpanMultipleLines(methodCall)
            || binaryLine.width(flat, nestedContinuationLine) > options.lineWidth()
            || (nestedContinuationLine
                && methodCallHasBreakableStructure(methodCall)
                && binaryLine.width(flat, nestedContinuationLine) > options.lineWidth() - options.indentUnit().length());
    }

    private boolean methodCallBinaryOperandShouldBreak(BinaryExpressionLine binaryLine, BinaryExpr binaryOperand) {
        MethodCallExpr methodCall = (MethodCallExpr) binaryOperand.getLeft();
        return sourceShape.methodCallArgumentsSpanMultipleLines(methodCall)
            || binaryLine.width(compact.apply(binaryOperand)) > options.lineWidth()
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

    private boolean leadingOperatorMethodCallBinaryOperandShouldNest(
            BinaryExpressionLine binaryLine,
            BinaryExpr binaryOperand
    ) {
        return binaryLine.hasLeadingOperator()
            && binaryOperand.getLeft() instanceof MethodCallExpr methodCall
            && methodCall.getArguments().size() > 1
            && !sourceShape.methodCallArgumentsSpanMultipleLines(methodCall)
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
     */
    private boolean shouldBreakEndPositionMethodCallOperand(BinaryExpressionLine binaryLine, Expression operand) {
        return binaryLine.hasTrailingOperator()
            && operand instanceof MethodCallExpr methodCall
            && !methodCall.getArguments().isEmpty()
            && (methodCall.getArguments().size() > 1 || sourceShape.methodCallArgumentsSpanMultipleLines(methodCall))
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

    boolean hasLineComments(BinaryExpr expression) {
        return commentPlacement.hasContainedLineComments(expression);
    }

    /**
     * Rebuilds broken binary lines when line comments appear between operands.
     *
     * <p>JavaParser attaches comments to nearby nodes rather than to operator tokens. The formatter therefore flattens
     * same-operator operands, finds comments that fall between each neighboring pair, keeps same-line trailing comments
     * next to the previous operand for end-position operators, and emits the remaining comments as their own lines.
     */
    Doc linesWithComments(BinaryExpr expression) {
        List<Expression> operands = new ArrayList<>();
        flattenBinaryExpression(expression, expression.getOperator(), operands);
        List<Doc> lines = new ArrayList<>();
        for (int i = 0; i < operands.size(); i++) {
            Expression operand = operands.get(i);
            Doc line = Doc.text(binaryLineOperandText(expression.getOperator(), operand, i, operands.size()));
            List<JavaCommentTrivia> between = i < operands.size() - 1
                ? commentPlacement.lineCommentsBetween(expression, operand, operands.get(i + 1))
                : List.of();
            List<JavaCommentTrivia> sameLineComments = commentPlacement.commentsStartingOnEndLine(operand, between);
            for (JavaCommentTrivia comment : sameLineComments) {
                line = Doc.concat(line, Doc.text(" "), comments.comment(comment));
            }
            between = between.stream()
                    .filter(comment -> !sameLineComments.contains(comment))
                    .toList();
            lines.add(line);
            if (i < operands.size() - 1) {
                lines.addAll(commentDocs(between));
            }
        }
        return Doc.join(Doc.HARD_LINE, lines);
    }

    private String binaryLineOperandText(
            BinaryExpr.Operator operator,
            Expression operand,
            int index,
            int operandCount
    ) {
        String text = compactWithoutOwnComment.apply(operand);
        if (options.binaryOperatorPosition() == FormatterOptions.BinaryOperatorPosition.START) {
            return index == 0 ? text : operator.asString() + " " + text;
        }
        return index < operandCount - 1 ? text + " " + operator.asString() : text;
    }

    private List<Doc> commentDocs(List<JavaCommentTrivia> sourceComments) {
        return sourceComments.stream()
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
     */
    Doc binaryExpression(BinaryExpr expression) {
        Optional<JavaCommentTrivia> leftLineComment = commentPlacement.ownComment(
            expression.getLeft(),
            JavaCommentTrivia::isLine
        );
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
