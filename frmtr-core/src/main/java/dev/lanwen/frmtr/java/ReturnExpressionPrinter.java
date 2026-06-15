package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Renders return-statement expressions after statement dispatch has already selected {@code return value;} syntax.
 *
 * <p>This helper owns the return-specific expression decision tree: the whole-return-line width gate, forced method-call
 * chains, forced conditional breaks, and parenthesized continuations for logical complements, enclosed expressions, and
 * binary expressions. The boundary exists because these choices depend on the surrounding {@code return} keyword and
 * semicolon, but the return statement itself still belongs to {@link StatementPrinter}.
 *
 * <p>{@link JavaPrinter} and the existing expression helpers still own broad expression dispatch, compact source text,
 * method-call chain layout, conditional layout, parenthesized expression breaks, and width calculations. This helper
 * keeps only the return-context branch order and receives every reusable formatting decision as a callback.
 */
final class ReturnExpressionPrinter {

    private final FormatterOptions options;

    private final ObjectCreationLayoutPolicy objectCreationLayoutPolicy;

    private final Function<Expression, Doc> expression;

    private final Function<LambdaExpr, Doc> brokenLambdaExpression;

    private final Function<Expression, String> compact;

    private final ToIntFunction<String> currentIndentedWidth;

    private final Function<MethodCallExpr, Optional<Doc>> sourceMultilineExpressionLambda;

    private final Function<MethodCallExpr, Optional<Doc>> sourceMultilineMethodCall;

    private final Function<MethodCallExpr, Optional<Doc>> compactRootWithBrokenFinalChainSegment;

    private final Function<MethodCallExpr, Optional<Doc>> forcedMethodCallChain;

    private final Function<MethodCallExpr, Doc> brokenMethodCall;

    private final BiFunction<MethodCallExpr, String, Doc> brokenMethodCallWithClosingLine;

    private final Function<MethodCallExpr, String> methodCallPrefix;

    private final Predicate<MethodCallExpr> methodCallChainIsSourceMultiline;

    private final Function<ObjectCreationExpr, Doc> brokenObjectCreation;

    private final BiFunction<ObjectCreationExpr, String, Doc> objectCreationWithSuffix;

    private final BiFunction<ConditionalExpr, Boolean, Doc> conditionalExpression;

    private final BiFunction<Expression, Boolean, Doc> binaryLines;

    private final BiFunction<Expression, Boolean, Doc> parenthesizedBreak;

    ReturnExpressionPrinter(
            FormatterOptions options,
            ObjectCreationLayoutPolicy objectCreationLayoutPolicy,
            Function<Expression, Doc> expression,
            Function<LambdaExpr, Doc> brokenLambdaExpression,
            Function<Expression, String> compact,
            ToIntFunction<String> currentIndentedWidth,
            Function<MethodCallExpr, Optional<Doc>> sourceMultilineExpressionLambda,
            Function<MethodCallExpr, Optional<Doc>> sourceMultilineMethodCall,
            Function<MethodCallExpr, Optional<Doc>> compactRootWithBrokenFinalChainSegment,
            Function<MethodCallExpr, Optional<Doc>> forcedMethodCallChain,
            Function<MethodCallExpr, Doc> brokenMethodCall,
            BiFunction<MethodCallExpr, String, Doc> brokenMethodCallWithClosingLine,
            Function<MethodCallExpr, String> methodCallPrefix,
            Predicate<MethodCallExpr> methodCallChainIsSourceMultiline,
            Function<ObjectCreationExpr, Doc> brokenObjectCreation,
            BiFunction<ObjectCreationExpr, String, Doc> objectCreationWithSuffix,
            BiFunction<ConditionalExpr, Boolean, Doc> conditionalExpression,
            BiFunction<Expression, Boolean, Doc> binaryLines,
            BiFunction<Expression, Boolean, Doc> parenthesizedBreak
    ) {
        this.options = options;
        this.objectCreationLayoutPolicy = objectCreationLayoutPolicy;
        this.expression = expression;
        this.brokenLambdaExpression = brokenLambdaExpression;
        this.compact = compact;
        this.currentIndentedWidth = currentIndentedWidth;
        this.sourceMultilineExpressionLambda = sourceMultilineExpressionLambda;
        this.sourceMultilineMethodCall = sourceMultilineMethodCall;
        this.compactRootWithBrokenFinalChainSegment = compactRootWithBrokenFinalChainSegment;
        this.forcedMethodCallChain = forcedMethodCallChain;
        this.brokenMethodCall = brokenMethodCall;
        this.brokenMethodCallWithClosingLine = brokenMethodCallWithClosingLine;
        this.methodCallPrefix = methodCallPrefix;
        this.methodCallChainIsSourceMultiline = methodCallChainIsSourceMultiline;
        this.brokenObjectCreation = brokenObjectCreation;
        this.objectCreationWithSuffix = objectCreationWithSuffix;
        this.conditionalExpression = conditionalExpression;
        this.binaryLines = binaryLines;
        this.parenthesizedBreak = parenthesizedBreak;
    }

    Doc returnStatement(Expression expression) {
        if (expression instanceof ObjectCreationExpr objectCreation) {
            return Doc.concat(Doc.text("return "), objectCreationWithSuffix.apply(objectCreation, ";"));
        }
        return Doc.concat(Doc.text("return "), returnExpression(expression), Doc.text(";"));
    }

    /**
     * Prints the return value flat unless the complete {@code return value;} line is too wide.
     *
     * <p>The gate checks the keyword and semicolon with the value because a value that fits by itself can still overflow
     * once it is placed inside a return statement. When the whole statement fits, expression dispatch keeps its ordinary
     * shape; only overflowing return lines enter the return-specific break tree.
     */
    Doc returnExpression(Expression expression) {
        Optional<BinaryExpr> sourceMultilineEnclosedBinary = sourceMultilineEnclosedBinary(expression);
        if (sourceMultilineEnclosedBinary.isPresent()) {
            BinaryExpr binaryExpr = sourceMultilineEnclosedBinary.orElseThrow();
            return directBinaryReturn(binaryExpr).orElseGet(() -> parenthesizedBreak.apply(binaryExpr, true));
        }
        if (sourceMultilineObjectCreation(expression)) {
            return brokenObjectCreation.apply((ObjectCreationExpr) expression);
        }
        if (returnLineFits(expression)) {
            return this.expression.apply(expression);
        }
        return brokenReturnExpression(expression).orElseGet(() -> this.expression.apply(expression));
    }

    private Optional<BinaryExpr> sourceMultilineEnclosedBinary(Expression expression) {
        if (
            expression instanceof EnclosedExpr enclosedExpr
            && enclosedExpr.getInner() instanceof BinaryExpr binaryExpr
            && expression.getRange().map(range -> range.begin.line < range.end.line).orElse(false)
        ) {
            return Optional.of(binaryExpr);
        }
        return Optional.empty();
    }

    private boolean sourceMultilineObjectCreation(Expression expression) {
        return expression instanceof ObjectCreationExpr objectCreationExpr
            && objectCreationLayoutPolicy.shouldPreserveSourceMultilineArguments(objectCreationExpr);
    }

    private boolean returnLineFits(Expression expression) {
        String line = "return " + compact.apply(expression) + ";";
        return returnLineWidth(expression, line) <= options.lineWidth();
    }

    private int returnLineWidth(Expression expression, String line) {
        return expression.getRange()
                .map(range -> Math.max(0, range.begin.column - "return ".length() - 1) + line.length())
                .orElseGet(() -> currentIndentedWidth.applyAsInt(line));
    }

    /**
     * Tries the width-triggered return branches in the same order as the old inline printer.
     *
     * <p>Method calls and conditionals are tried first because their helpers already know how to force a useful break for
     * the whole expression. Parenthesized-looking values are handled next so the long part moves inside parentheses
     * instead of leaving a wide value directly after {@code return}.
     */
    private Optional<Doc> brokenReturnExpression(Expression expression) {
        Optional<Doc> methodCallChain = returnWithForcedMethodCallChain(expression);
        if (methodCallChain.isPresent()) {
            return methodCallChain;
        }
        Optional<Doc> conditionalBreak = returnWithForcedConditionalBreak(expression);
        if (conditionalBreak.isPresent()) {
            return conditionalBreak;
        }
        Optional<Doc> lambdaBreak = returnWithForcedLambdaBreak(expression);
        if (lambdaBreak.isPresent()) {
            return lambdaBreak;
        }
        Optional<Doc> logicalComplementBreak = returnWithLogicalComplementBreak(expression);
        if (logicalComplementBreak.isPresent()) {
            return logicalComplementBreak;
        }
        return returnWithParenthesizedValueBreak(expression);
    }

    private Optional<Doc> returnWithForcedMethodCallChain(Expression expression) {
        if (!(expression instanceof MethodCallExpr methodCall)) {
            return Optional.empty();
        }
        Optional<Doc> expressionLambda = sourceMultilineExpressionLambda.apply(methodCall);
        if (expressionLambda.isPresent()) {
            return expressionLambda;
        }
        if (!methodCallChainIsSourceMultiline.test(methodCall)) {
            Optional<Doc> sourceMultilineCall = sourceMultilineMethodCall.apply(methodCall);
            if (sourceMultilineCall.isPresent()) {
                return sourceMultilineCall;
            }
        }
        return compactRootWithBrokenFinalChainSegment.apply(methodCall)
                .or(() -> forcedMethodCallChain.apply(methodCall))
                .or(() -> Optional.of(brokenMethodCall.apply(methodCall)));
    }

    private Optional<Doc> returnWithForcedConditionalBreak(Expression expression) {
        if (!(expression instanceof ConditionalExpr conditionalExpr)) {
            return Optional.empty();
        }
        return Optional.of(conditionalExpression.apply(conditionalExpr, true));
    }

    private Optional<Doc> returnWithForcedLambdaBreak(Expression expression) {
        if (!(expression instanceof LambdaExpr lambdaExpr) || lambdaExpr.getExpressionBody().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(brokenLambdaExpression.apply(lambdaExpr));
    }

    /**
     * Keeps {@code !} attached while breaking the enclosed operand inside its existing parentheses.
     *
     * <p>The logical-complement case is separate from ordinary enclosed expressions because the prefix operator should
     * stay visible at the return value start; only the inner parenthesized expression needs the multi-line shape.
     */
    private Optional<Doc> returnWithLogicalComplementBreak(Expression expression) {
        if (
            !(expression instanceof UnaryExpr unaryExpr)
            || unaryExpr.getOperator() != UnaryExpr.Operator.LOGICAL_COMPLEMENT
            || !(unaryExpr.getExpression() instanceof EnclosedExpr enclosedExpr)
        ) {
            return Optional.empty();
        }
        return Optional.of(Doc.concat(Doc.text("!"), parenthesizedBreak.apply(enclosedExpr.getInner(), false)));
    }

    /**
     * Breaks grouped return values by moving the long expression inside parentheses and direct binary values as
     * continuation lines.
     *
     * <p>Already enclosed expressions keep their source grouping and break only the inner value. Direct binary values use
     * the binary-expression policy directly unless comments inside the binary need the parenthesized shape to keep their
     * ownership obvious.
     */
    private Optional<Doc> returnWithParenthesizedValueBreak(Expression expression) {
        if (expression instanceof EnclosedExpr enclosedExpr) {
            if (enclosedExpr.getInner() instanceof BinaryExpr binaryExpr) {
                Optional<Doc> directBinary = directBinaryReturn(binaryExpr);
                if (directBinary.isPresent()) {
                    return directBinary;
                }
            }
            return Optional.of(parenthesizedBreak.apply(enclosedExpr.getInner(), false));
        }
        if (expression instanceof BinaryExpr binaryExpr) {
            Optional<Doc> directBinary = directBinaryReturn(binaryExpr);
            if (directBinary.isPresent()) {
                return directBinary;
            }
            return Optional.of(parenthesizedBreak.apply(binaryExpr, false));
        }
        return Optional.empty();
    }

    private Optional<Doc> directBinaryReturn(BinaryExpr binaryExpr) {
        if (!binaryExpr.getAllContainedComments().isEmpty()) {
            return Optional.empty();
        }
        if (directBinaryReturnLineFits(binaryExpr)) {
            return Optional.of(this.expression.apply(binaryExpr));
        }
        Optional<Doc> methodCallLeft = directBinaryReturnWithMethodCallLeft(binaryExpr);
        if (methodCallLeft.isPresent()) {
            return methodCallLeft;
        }
        if (directBinaryReturnFirstLineFits(binaryExpr)) {
            return Optional.of(Doc.indent(binaryLines.apply(binaryExpr, true)));
        }
        return Optional.empty();
    }

    private boolean directBinaryReturnLineFits(BinaryExpr expression) {
        String line = "return " + compact.apply(expression) + ";";
        return currentIndentedWidth.applyAsInt(line) <= options.lineWidth();
    }

    private Optional<Doc> directBinaryReturnWithMethodCallLeft(BinaryExpr binaryExpr) {
        if (
            !(binaryExpr.getLeft() instanceof MethodCallExpr methodCall)
            || methodCall.getArguments().isEmpty()
            || !methodCall.getAllContainedComments().isEmpty()
            || !binaryExpr.getRight().getAllContainedComments().isEmpty()
            || !directBinaryReturnMethodCallFirstLineFits(methodCall)
            || !directBinaryReturnMethodCallClosingLineFits(binaryExpr)
        ) {
            return Optional.empty();
        }
        return Optional.of(
            brokenMethodCallWithClosingLine.apply(methodCall, methodCallBinaryReturnClosingLine(binaryExpr))
        );
    }

    private boolean directBinaryReturnMethodCallFirstLineFits(MethodCallExpr methodCall) {
        String line = "return " + methodCallPrefix.apply(methodCall) + "(";
        return currentIndentedWidth.applyAsInt(line) <= options.lineWidth();
    }

    private boolean directBinaryReturnMethodCallClosingLineFits(BinaryExpr binaryExpr) {
        return currentIndentedWidth.applyAsInt(methodCallBinaryReturnClosingLine(binaryExpr)) <= options.lineWidth();
    }

    private String methodCallBinaryReturnClosingLine(BinaryExpr binaryExpr) {
        return ") " + binaryExpr.getOperator().asString() + " " + compact.apply(binaryExpr.getRight());
    }

    private boolean directBinaryReturnFirstLineFits(BinaryExpr expression) {
        String line = "return " + compact.apply(firstBinaryOperand(expression));
        return currentIndentedWidth.applyAsInt(line) <= options.lineWidth();
    }

    private Expression firstBinaryOperand(BinaryExpr expression) {
        Expression left = expression.getLeft();
        while (left instanceof BinaryExpr leftBinary && leftBinary.getOperator() == expression.getOperator()) {
            left = leftBinary.getLeft();
        }
        return left;
    }
}
