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
 * segment's compact form overflows the line ({@link #methodCallSegmentArgumentsShouldBreak}), where that compact form
 * actually lands depending on whether the segment sits alone on a continuation line or beside a preceding token
 * ({@link #finalSegmentRenderedWidth} / {@link #methodCallSegmentWidth}), the reconstructed argument-width text those
 * measurements consume ({@link #methodCallSegmentArgumentsWidthText}), and the two argument-shape predicates that gate the
 * break — a lone simple argument that need not reserve a statement terminator
 * ({@link #singleSimpleMethodCallSegmentArgument}) and an over-wide type-like multi-argument scope
 * ({@link #overwideTypeLikeScopeSegment}). The boundary exists so the chain printer keeps its segment-rendering grammar
 * while the source-relative width estimates — deliberately left source-column based, see {@link #methodCallSegmentWidth} —
 * live behind one seam.
 *
 * <p>The caller still owns the segment {@code Doc} construction and passes in the continuation-indent width function each
 * measurement uses ({@code ToIntFunction<String>}); this helper never renders a segment, only reports whether it fits and
 * how wide it is. Whether a scope "promotes" (the type-like receiver test {@link #overwideTypeLikeScopeSegment} keys on)
 * is likewise the caller's rule, injected as a predicate.
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
            ToIntFunction<String> compactSegmentWidth,
            boolean segmentOnOwnLine
    ) {
        if (
            reserveStatementTerminator
            && !singleSimpleMethodCallSegmentArgument(expression)
            && finalSegmentRenderedWidth(expression, compactSegment, compactSegmentWidth, segmentOnOwnLine)
                > options.lineWidth()
        ) {
            return true;
        }
        return overwideTypeLikeScopeSegment(expression)
            && compactSegmentWidth.applyAsInt(compactSegment) > options.lineWidth();
    }

    /**
     * Measures where the final chain segment's compact form will actually land.
     *
     * <p>A segment that the chain places on its own continuation line is measured purely at that continuation
     * indent ({@code compactSegmentWidth}), because nothing precedes it on the line. The source-column estimate in
     * {@link #methodCallSegmentWidth} only describes a segment kept beside a preceding token on the same line, so
     * applying it to a one-per-line segment overstates the width by the segment's stale source indentation. That
     * over-measurement is what made an already-flat-fitting trailing call (such as {@code .collect(Collectors.toSet())})
     * break apart on the first pass and then collapse on the second, so a standalone segment must ignore the source
     * column to converge in one pass.
     */
    int finalSegmentRenderedWidth(
            MethodCallExpr expression,
            String compactSegment,
            ToIntFunction<String> compactSegmentWidth,
            boolean segmentOnOwnLine
    ) {
        if (segmentOnOwnLine) {
            return compactSegmentWidth.applyAsInt(compactSegment);
        }
        return methodCallSegmentWidth(expression, compactSegment, compactSegmentWidth);
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

    /**
     * Estimates a chain segment's width when it is kept beside a preceding token on the same line.
     *
     * <p>Deliberately left source-relative. The reconstruction — the name token's source column minus its
     * offset within the segment — recovers where the whole segment starts <em>beside its preceding token</em> (see
     * {@link #finalSegmentRenderedWidth}), a source-shaped position that depends on what shares the line, not on the
     * segment's own block/type nesting depth. {@link LayoutWidth#nodeIndentWidth} measures only that nesting depth and
     * so cannot express the beside-a-token column, which is why the one-per-line caller already routes around this via
     * {@code segmentOnOwnLine}. The source column remains the faithful estimate for the beside-a-token case; a correct
     * rendered-column migration would need the same leading-offset machinery the root gates await.
     */
    int methodCallSegmentWidth(
            MethodCallExpr expression,
            String segment,
            ToIntFunction<String> fallbackWidth
    ) {
        return expression.getName()
                .getRange()
                .map(range -> {
                    int nameOffset = segment.indexOf(expression.getNameAsString());
                    if (nameOffset < 0) {
                        return fallbackWidth.applyAsInt(segment);
                    }
                    int leadingColumns = Math.max(0, range.begin.column - 1 - nameOffset);
                    return leadingColumns + segment.length();
                })
                .orElseGet(() -> fallbackWidth.applyAsInt(segment));
    }
}
