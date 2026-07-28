package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import dev.lanwen.frmtr.FormatterOptions;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Measures how wide a method-call chain SEGMENT renders and decides whether its argument list must break, for
 * {@link MethodCallChainPrinter}.
 *
 * <p>This helper owns the segment-level width arithmetic the chain printer's segment renderer consults: whether a final
 * segment's compact form overflows the line ({@link #methodCallSegmentArgumentsShouldBreak}), the reconstructed
 * argument-width text that measurement consumes ({@link #methodCallSegmentArgumentsWidthText}), and the two
 * argument-shape predicates that gate the break — a lone simple argument that need not reserve a statement terminator
 * ({@link #singleSimpleMethodCallSegmentArgument}) and an over-wide type-like multi-argument scope
 * ({@link #overwideTypeLikeScopeSegment}). The boundary exists so the chain printer keeps its segment-rendering grammar
 * while the width arithmetic lives behind one seam.
 *
 * <p>The caller still owns the segment {@code Doc} construction and, crucially, the rendered column: every measurement
 * runs through the {@code ToIntFunction<String>} the caller supplies, which already folds in the indentation and any
 * preceding token the segment is glued to. Nothing here reads source geometry, so an identical AST at an identical
 * rendered position always yields the same verdict. Whether a scope "promotes" (the type-like receiver test
 * {@link #overwideTypeLikeScopeSegment} keys on) is likewise the caller's rule, injected as a predicate.
 */
final class ChainSegmentWidthLayout {

    private final FormatterOptions options;

    private final Function<Expression, String> compactWithoutOwnComment;

    private final Predicate<Expression> promotesFirstCall;

    ChainSegmentWidthLayout(
            FormatterOptions options,
            Function<Expression, String> compactWithoutOwnComment,
            Predicate<Expression> promotesFirstCall
    ) {
        this.options = options;
        this.compactWithoutOwnComment = compactWithoutOwnComment;
        this.promotesFirstCall = promotesFirstCall;
    }

    boolean methodCallSegmentArgumentsShouldBreak(
            MethodCallExpr expression,
            boolean reserveStatementTerminator,
            String compactSegment,
            ToIntFunction<String> compactSegmentWidth
    ) {
        if (compactSegmentWidth.applyAsInt(compactSegment) <= options.lineWidth()) {
            return false;
        }
        return (reserveStatementTerminator && !singleSimpleMethodCallSegmentArgument(expression))
            || overwideTypeLikeScopeSegment(expression);
    }

    boolean singleSimpleMethodCallSegmentArgument(MethodCallExpr expression) {
        if (expression.getArguments().size() != 1) {
            return false;
        }
        Expression argument = expression.getArgument(0);
        return argument.isNameExpr()
            || argument.isFieldAccessExpr()
            || argument.isThisExpr()
            || argument.isSuperExpr()
            || argument.isLiteralExpr();
    }

    boolean overwideTypeLikeScopeSegment(MethodCallExpr expression) {
        return expression.getArguments().size() > 1
            && expression.getScope().filter(promotesFirstCall).isPresent();
    }

    String methodCallSegmentArgumentsWidthText(NodeList<Expression> arguments) {
        // Reconstruct each argument SOURCE-NEUTRALLY (compactWithoutOwnComment), not via
        // normalizeWhitespace(rawWithoutOwnComment): the latter turns an argument's source newlines into spaces, so an
        // already-wrapped argument measures wider than its flat form and the segment-break gate flips between passes.
        return arguments.stream()
                .map(compactWithoutOwnComment)
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
    }
}
