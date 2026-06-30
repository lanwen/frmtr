package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.Optional;
import java.util.function.BiPredicate;
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

    private final BiPredicate<MethodCallExpr, ToIntFunction<String>> chainShouldBreak;

    BreakableArgumentExpressionPrinter(
            SourceShapePolicy sourceShapePolicy,
            FormatterOptions options,
            Function<Expression, Doc> expressionRenderer,
            Function<Expression, Optional<Doc>> brokenArgumentRenderer,
            Function<Expression, String> compact,
            ToIntFunction<String> continuationStatementWidth,
            BiPredicate<MethodCallExpr, ToIntFunction<String>> chainShouldBreak
    ) {
        this.sourceShapePolicy = sourceShapePolicy;
        this.options = options;
        this.expressionRenderer = expressionRenderer;
        this.brokenArgumentRenderer = brokenArgumentRenderer;
        this.compact = compact;
        this.conditionalProjection = new ConditionalExpressionLineProjection(compact);
        this.continuationStatementWidth = continuationStatementWidth;
        this.chainShouldBreak = chainShouldBreak;
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
        if (broken.isPresent() && containsRuleBreakingChain(argument)) {
            // A binary-wrapped fluent chain argument (for example {@code addRDN(CN, lookup().lookupClass()...() + suffix)})
            // must pick its chain shape from the SAME context-consistent rule everywhere, never from the enclosing
            // argument group's break state (issue #137). The {@code Doc.ifBreak(broken, flat)} arms below render the
            // contained chain with DIFFERENT policies: the broken arm puts {@code + suffix} on its own line while the flat
            // arm glues it to the last selector, so whichever arm the group selects flips the chain shape across passes.
            // When the chain breaks by the single chain-break decision, commit to the deterministic broken shape so the
            // argument no longer depends on the group, matching the FIXED broken/flat sourceMultilineArgument sibling.
            return broken.orElseThrow();
        }
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

    /**
     * Reports whether a binary-expression argument contains a fluent method-call operand that breaks one selector per
     * line by the single chain-break decision (PR-1's {@code chainShouldBreak}). The chain renders at the argument's
     * continuation column, so the chain's first line is measured there. Only {@code +}-joined operands and their
     * parenthesized inners are inspected, mirroring {@link #binaryPlusContainsSourceMultilineMethodCallArgument}: a
     * suffix glued to a breaking chain (the flip in issue #137) is always a string-concatenation tail.
     */
    private boolean containsRuleBreakingChain(Expression argument) {
        if (argument instanceof BinaryExpr binaryExpr) {
            return binaryExpr.getOperator() == BinaryExpr.Operator.PLUS
                && (containsRuleBreakingChain(binaryExpr.getLeft())
                    || containsRuleBreakingChain(binaryExpr.getRight()));
        }
        if (argument instanceof EnclosedExpr enclosedExpr) {
            return containsRuleBreakingChain(enclosedExpr.getInner());
        }
        return argument instanceof MethodCallExpr methodCall
            && methodCall.getAllContainedComments().isEmpty()
            && chainShouldBreak.test(methodCall, continuationStatementWidth);
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
