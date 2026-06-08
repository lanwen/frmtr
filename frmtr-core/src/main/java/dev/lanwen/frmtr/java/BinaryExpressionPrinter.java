package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.InstanceOfExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
    private final JavaFormatter.CommentTracker comments;
    private final FormatterOptions options;
    private final JavaFormatRule<Expression> expressionRenderer;
    private final Function<MethodCallExpr, Doc> brokenMethodCallRenderer;
    private final Function<Node, String> compact;
    private final Function<Node, String> compactWithoutOwnComment;
    private final ToIntFunction<String> continuationStatementWidth;
    private final ToIntFunction<String> blockStatementWidth;

    BinaryExpressionPrinter(
            JavaFormatter.CommentTracker comments,
            FormatterOptions options,
            JavaFormatRule<Expression> expressionRenderer,
            Function<MethodCallExpr, Doc> brokenMethodCallRenderer,
            Function<Node, String> compact,
            Function<Node, String> compactWithoutOwnComment,
            ToIntFunction<String> continuationStatementWidth,
            ToIntFunction<String> blockStatementWidth) {
        this.comments = comments;
        this.options = options;
        this.expressionRenderer = expressionRenderer;
        this.brokenMethodCallRenderer = brokenMethodCallRenderer;
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
        if (binaryExpr.getOperator() == BinaryExpr.Operator.AND
                && operands.size() == 2
                && operands.getFirst() instanceof InstanceOfExpr instanceOfExpr
                && parenthesizedInnerWidth(compact.apply(instanceOfExpr)) > options.lineWidth()) {
            return Doc.concat(
                    expressionRenderer.format(instanceOfExpr),
                    Doc.text(" && "),
                    expressionRenderer.format(operands.getLast()));
        }
        if (options.binaryOperatorPosition() == FormatterOptions.BinaryOperatorPosition.END
                && operands.size() == 2
                && operands.getFirst() instanceof MethodCallExpr methodCall
                && shouldBreakEndPositionMethodCallOperand(binaryExpr.getOperator(), methodCall)
                && continuationStatementWidth.applyAsInt(") "
                                + binaryExpr.getOperator().asString()
                                + " "
                                + compact.apply(operands.getLast()))
                        <= options.lineWidth()) {
            return Doc.concat(
                    brokenMethodCallRenderer.apply(methodCall),
                    Doc.text(" " + binaryExpr.getOperator().asString() + " "),
                    expressionRenderer.format(operands.getLast()));
        }
        List<Doc> lines = new ArrayList<>();
        for (int i = 0; i < operands.size(); i++) {
            Expression operandExpression = operands.get(i);
            Doc operand = binaryExpressionLineOperand(binaryExpr.getOperator(), operandExpression);
            if (options.binaryOperatorPosition() == FormatterOptions.BinaryOperatorPosition.END
                    && i < operands.size() - 1
                    && shouldBreakEndPositionMethodCallOperand(binaryExpr.getOperator(), operandExpression)) {
                MethodCallExpr methodCall = (MethodCallExpr) operandExpression;
                operand = brokenMethodCallRenderer.apply(methodCall);
            }
            if (options.binaryOperatorPosition() == FormatterOptions.BinaryOperatorPosition.START && i > 0) {
                operand = Doc.concat(Doc.text(binaryExpr.getOperator().asString() + " "), operand);
            } else if (options.binaryOperatorPosition() == FormatterOptions.BinaryOperatorPosition.END && i < operands.size() - 1) {
                operand = Doc.concat(operand, Doc.text(" " + binaryExpr.getOperator().asString()));
            }
            lines.add(operand);
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
    private Doc binaryExpressionLineOperand(BinaryExpr.Operator operator, Expression operand) {
        if (operator == BinaryExpr.Operator.OR
                && operand instanceof BinaryExpr binaryOperand
                && binaryOperand.getOperator() == BinaryExpr.Operator.AND) {
            if (parenthesizedInnerWidth(compact.apply(binaryOperand)) > options.lineWidth()) {
                return Doc.concat(Doc.text("("), nestedLines(binaryOperand, true), Doc.text(")"));
            }
            return Doc.concat(Doc.text("("), expressionRenderer.format(binaryOperand), Doc.text(")"));
        }
        if (operand instanceof BinaryExpr binaryOperand
                && shouldParenthesizeNestedBinary(operator, binaryOperand.getOperator())) {
            return Doc.concat(Doc.text("("), expressionRenderer.format(binaryOperand), Doc.text(")"));
        }
        return expressionRenderer.format(operand);
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
    private boolean shouldBreakEndPositionMethodCallOperand(BinaryExpr.Operator operator, Expression operand) {
        return operand instanceof MethodCallExpr methodCall
                && !methodCall.getArguments().isEmpty()
                && continuationStatementWidth.applyAsInt(compact.apply(methodCall) + " " + operator.asString())
                        > options.lineWidth();
    }

    boolean hasLineComments(BinaryExpr expression) {
        return CommentIndex.hasContainedLineComments(expression);
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
            List<Comment> between = i < operands.size() - 1
                    ? CommentIndex.lineCommentsBetween(expression, operand, operands.get(i + 1))
                    : List.of();
            if (options.binaryOperatorPosition() == FormatterOptions.BinaryOperatorPosition.END) {
                List<Comment> sameLineComments = CommentIndex.commentsStartingOnEndLine(operand, between);
                for (Comment comment : sameLineComments) {
                    line = Doc.concat(line, Doc.text(" "), comments.comment(comment));
                }
                between = between.stream()
                        .filter(comment -> !sameLineComments.contains(comment))
                        .toList();
            }
            lines.add(line);
            if (i < operands.size() - 1) {
                lines.addAll(commentDocs(between));
            }
        }
        return Doc.join(Doc.HARD_LINE, lines);
    }

    private String binaryLineOperandText(BinaryExpr.Operator operator, Expression operand, int index, int operandCount) {
        String text = compactWithoutOwnComment.apply(operand);
        if (options.binaryOperatorPosition() == FormatterOptions.BinaryOperatorPosition.START) {
            return index == 0 ? text : operator.asString() + " " + text;
        }
        return index < operandCount - 1 ? text + " " + operator.asString() : text;
    }

    private List<Doc> commentDocs(List<Comment> sourceComments) {
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

    /**
     * Flattens only adjacent binary nodes that use the same operator.
     *
     * <p>This preserves source grouping for mixed-operator expressions while allowing long homogeneous chains like
     * {@code a && b && c} or {@code a + b + c} to align as one continuation list.
     */
    private void flattenBinaryExpression(
            Expression expression,
            BinaryExpr.Operator operator,
            List<Expression> operands) {
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
        Optional<LineComment> leftLineComment = expression.getLeft()
                .getComment()
                .filter(LineComment.class::isInstance)
                .map(LineComment.class::cast);
        if (leftLineComment.isEmpty()) {
            return Doc.concat(
                    binaryLeftOperand(expression),
                    Doc.text(" " + expression.getOperator().asString() + " "),
                    binaryRightOperand(expression));
        }
        return Doc.concat(
                Doc.text(compactWithoutOwnComment.apply(expression.getLeft()) + " " + expression.getOperator().asString() + " "),
                JavaFormatter.commentDoc(leftLineComment.orElseThrow()),
                Doc.indent(Doc.concat(Doc.HARD_LINE, binaryRightOperand(expression))));
    }

    private Doc binaryLeftOperand(BinaryExpr expression) {
        if (expression.getLeft() instanceof BinaryExpr leftBinary
                && (shouldParenthesizeLeftBinary(expression.getOperator(), leftBinary.getOperator())
                        || shouldParenthesizeNestedBinary(expression.getOperator(), leftBinary.getOperator()))) {
            return Doc.concat(Doc.text("("), expressionRenderer.format(leftBinary), Doc.text(")"));
        }
        return expressionRenderer.format(expression.getLeft());
    }

    private Doc binaryRightOperand(BinaryExpr expression) {
        if (expression.getRight() instanceof BinaryExpr rightBinary
                && shouldParenthesizeNestedBinary(expression.getOperator(), rightBinary.getOperator())) {
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
        if (isMultiplicativeOperator(outer)
                && (inner == BinaryExpr.Operator.DIVIDE || inner == BinaryExpr.Operator.REMAINDER)) {
            return true;
        }
        if (isAdditiveOperator(outer) && inner == BinaryExpr.Operator.REMAINDER) {
            return true;
        }
        if (isShiftOperator(outer) && (isArithmeticOperator(inner) || isShiftOperator(inner))) {
            return true;
        }
        if (isBitwiseOperator(outer)
                && (isShiftOperator(inner)
                        || isRelationalOperator(inner)
                        || isEqualityOperator(inner)
                        || outer == BinaryExpr.Operator.BINARY_OR
                                && (inner == BinaryExpr.Operator.BINARY_AND || inner == BinaryExpr.Operator.XOR)
                        || outer == BinaryExpr.Operator.XOR && inner == BinaryExpr.Operator.BINARY_AND)) {
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

    /**
     * Reports whether any nested binary in an expression needs explicit parentheses under the operator-family rules.
     *
     * <p>Callers use this before choosing a compact raw string for conditions or ternaries; when the predicate is true,
     * they ask expression rendering to rebuild the binary tree with the required parentheses instead.
     */
    boolean expressionHasParenthesizedNestedBinary(Expression expression) {
        return expression.findAll(BinaryExpr.class).stream().anyMatch(binary ->
                binary.getLeft() instanceof BinaryExpr leftBinary
                                && (shouldParenthesizeLeftBinary(binary.getOperator(), leftBinary.getOperator())
                                        || shouldParenthesizeNestedBinary(binary.getOperator(), leftBinary.getOperator()))
                        || binary.getRight() instanceof BinaryExpr rightBinary
                                && shouldParenthesizeNestedBinary(binary.getOperator(), rightBinary.getOperator()));
    }
}
