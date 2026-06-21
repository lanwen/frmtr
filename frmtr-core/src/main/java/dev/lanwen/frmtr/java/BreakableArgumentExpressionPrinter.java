package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Renders expression arguments that may need their own broken form inside a broken argument list.
 *
 * <p>This helper owns the shared decision used by method-call and constructor argument lists: once an enclosing
 * argument list breaks, a source-multiline or over-wide expression argument should be allowed to render through its
 * expression-specific broken form instead of collapsing back to a single argument line. The boundary keeps method-call
 * and object-creation printers focused on their delimiters, suffixes, and comment-specific list handling.
 *
 * <p>Callers still decide list separators, argument suffix ownership, and syntax-specific cases such as method-call
 * tails or object-creation suffixes.
 */
final class BreakableArgumentExpressionPrinter {

    private final SourceShapePolicy sourceShapePolicy;

    private final FormatterOptions options;

    private final Function<Expression, Doc> expressionRenderer;

    private final Function<Expression, Optional<Doc>> brokenArgumentRenderer;

    private final Function<Expression, String> compact;

    private final ConditionalExpressionLineProjection conditionalProjection;

    private final ToIntFunction<String> continuationStatementWidth;

    BreakableArgumentExpressionPrinter(
            SourceShapePolicy sourceShapePolicy,
            FormatterOptions options,
            Function<Expression, Doc> expressionRenderer,
            Function<Expression, Optional<Doc>> brokenArgumentRenderer,
            Function<Expression, String> compact,
            ToIntFunction<String> continuationStatementWidth
    ) {
        this.sourceShapePolicy = sourceShapePolicy;
        this.options = options;
        this.expressionRenderer = expressionRenderer;
        this.brokenArgumentRenderer = brokenArgumentRenderer;
        this.compact = compact;
        this.conditionalProjection = new ConditionalExpressionLineProjection(compact);
        this.continuationStatementWidth = continuationStatementWidth;
    }

    Doc argument(Expression argument) {
        return argument(argument, "");
    }

    /**
     * Keeps an expression argument breakable when its source shape or rendered continuation would otherwise overflow.
     *
     * <p>The suffix is only part of the width probe; callers still own rendering commas, semicolons, or call tails.
     */
    Doc argument(Expression argument, String suffix) {
        Doc flat = expressionRenderer.apply(argument);
        Optional<Doc> broken = brokenArgument(argument);
        if (
            broken.isPresent()
            && (sourceShapePolicy.wasMultiline(argument)
                || conditionalArgumentLineOverflows(argument, suffix)
                || continuationStatementWidth.applyAsInt(compact.apply(argument) + suffix) > options.lineWidth())
        ) {
            return Doc.ifBreak(broken.orElseThrow(), flat);
        }
        return flat;
    }

    Doc sourceMultilineArgument(Expression argument) {
        return sourceMultilineArgument(argument, "");
    }

    Doc sourceMultilineArgument(Expression argument, String suffix) {
        Doc flat = expressionRenderer.apply(argument);
        if (binaryPlusContainsSourceMultilineMethodCallArgument(argument)) {
            return flat;
        }
        Optional<Doc> broken = brokenArgument(argument);
        if (
            broken.isPresent()
            && (sourceShapePolicy.wasMultiline(argument)
                || sourceMultilineMethodCallArguments(argument)
                || conditionalArgumentLineOverflows(argument, suffix)
                || continuationStatementWidth.applyAsInt(compact.apply(argument) + suffix) > options.lineWidth())
        ) {
            return broken.orElseThrow();
        }
        return flat;
    }

    private Optional<Doc> brokenArgument(Expression argument) {
        if (!argument.getAllContainedComments().isEmpty()) {
            return Optional.empty();
        }
        return brokenArgumentRenderer.apply(argument);
    }

    private boolean conditionalArgumentLineOverflows(Expression argument, String suffix) {
        if (!(argument instanceof ConditionalExpr conditionalExpr)) {
            return false;
        }
        String line = conditionalProjection.line(conditionalExpr) + suffix;
        return continuationStatementWidth.applyAsInt(line) > options.lineWidth();
    }

    private boolean sourceMultilineMethodCallArguments(Expression argument) {
        return argument instanceof MethodCallExpr methodCall
            && sourceShapePolicy.methodCallArgumentsSpanMultipleLines(methodCall);
    }

    private boolean binaryPlusContainsSourceMultilineMethodCallArgument(Expression argument) {
        if (argument instanceof BinaryExpr binaryExpr) {
            return binaryExpr.getOperator() == BinaryExpr.Operator.PLUS
                && (binaryPlusContainsSourceMultilineMethodCallArgument(binaryExpr.getLeft())
                    || binaryPlusContainsSourceMultilineMethodCallArgument(binaryExpr.getRight()));
        }
        if (argument instanceof EnclosedExpr enclosedExpr) {
            return binaryPlusContainsSourceMultilineMethodCallArgument(enclosedExpr.getInner());
        }
        return sourceMultilineMethodCallArguments(argument);
    }
}
