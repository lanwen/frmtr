package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import dev.lanwen.frmtr.FormatterException;
import dev.lanwen.frmtr.doc.Doc;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;

/**
 * Turns planned recovered list gaps into safe raw document islands.
 *
 * <p>This helper owns the source-region plumbing shared by recovered list printers: trimming a final source line break
 * out of a raw gap when the surrounding formatter should own the next separator, validating raw regions against
 * JavaParser comment boundaries, converting comment-boundary failures into the caller's recovery exception, and
 * emitting labeled raw docs. The boundary exists so syntax-specific printers can keep their own list sequencing,
 * separators, and extra trivia rules while relying on one recovery-safe raw-gap path.
 *
 * <p>Callers still decide which planned lists are supported, whether a raw gap needs syntax-specific pre-adjustment,
 * what diagnostic label to use, and where each raw doc belongs in the surrounding formatted output.
 */
final class RecoveredRawGapPrinter {

    private final SourceText sourceText;

    private final RecoveredSourceRegions recoveredSourceRegions;

    private final BiFunction<String, Throwable, FormatterException> recoveryFailure;

    RecoveredRawGapPrinter(
            JavaFormatContext context,
            BiFunction<String, Throwable, FormatterException> recoveryFailure
    ) {
        this.sourceText = context.sourceText;
        this.recoveredSourceRegions = context.recoveredSourceRegions;
        this.recoveryFailure = Objects.requireNonNull(recoveryFailure, "recoveryFailure");
    }

    /**
     * Returns the raw-gap regions from a safe plan after applying shared trailing-line-break ownership rules.
     */
    <N extends Node> List<RawGapRegion> rawGapRegions(RecoveredListPlanner.Plan<N> plan) {
        return rawGapRegions(plan, region -> region);
    }

    /**
     * Returns raw-gap regions after applying a caller-owned source adjustment and shared trailing-line-break ownership.
     *
     * <p>The adjustment hook is for syntax trivia that the caller has already emitted separately, such as a line comment
     * after an opening brace. This helper deliberately applies only that requested adjustment before trimming; it does
     * not infer syntax-specific regions by itself.
     */
    <N extends Node> List<RawGapRegion> rawGapRegions(
            RecoveredListPlanner.Plan<N> plan,
            UnaryOperator<SourceRegion> adjustRegion
    ) {
        Objects.requireNonNull(adjustRegion, "adjustRegion");
        return plan.entries()
                .stream()
                .filter(RecoveredListPlanner.RawGap.class::isInstance)
                .map(entry -> (RecoveredListPlanner.RawGap<?>) entry)
                .map(entry -> rawGapRegion(adjustRegion.apply(entry.region())))
                .toList();
    }

    /**
     * Returns only the source regions from planned raw gaps for comment filtering in surrounding printers.
     */
    List<SourceRegion> regions(List<RawGapRegion> rawGapRegions) {
        return rawGapRegions.stream().map(RawGapRegion::region).toList();
    }

    /**
     * Validates that every non-empty raw gap can account comments without splitting or losing one.
     *
     * <p>This method has no comment-accounting side effects. It lets callers reject the whole recovered list before any
     * raw doc claims comments through {@link #raw(Node, RawGapRegion, String)}.
     */
    void requireRecoverableRawRegions(Node owner, List<RawGapRegion> rawGapRegions) {
        regions(rawGapRegions)
                .stream()
                .filter(region -> region.beginOffset() < region.endOffset())
                .forEach(region -> {
                    try {
                        recoveredSourceRegions.commentAccounting(owner, region).requireNoCrossing(region);
                    } catch (RecoveredSourceRegions.CrossingCommentBoundaryException exception) {
                        throw recoveryFailure(exception);
                    }
                });
    }

    /**
     * Emits a labeled raw doc for one planned gap and accounts comments fully contained by that gap.
     */
    Doc raw(Node owner, RawGapRegion rawGapRegion, String diagnosticKind) {
        return raw(owner, rawGapRegion.region(), diagnosticKind);
    }

    /**
     * Emits a labeled raw doc for a source region and converts comment-boundary failures into recovery failures.
     */
    Doc raw(Node owner, SourceRegion region, String diagnosticKind) {
        try {
            return recoveredSourceRegions.raw(owner, region, diagnosticKind);
        } catch (RecoveredSourceRegions.CrossingCommentBoundaryException exception) {
            throw recoveryFailure(exception);
        }
    }

    /**
     * Reports whether {@code region} fully contains {@code nested} by source offset.
     */
    static boolean contains(SourceRegion region, SourceRegion nested) {
        return region.beginOffset() <= nested.beginOffset() && nested.endOffset() <= region.endOffset();
    }

    private RawGapRegion rawGapRegion(SourceRegion region) {
        String raw = sourceText.slice(region);
        int cursor = raw.length();
        while (cursor > 0 && isHorizontalWhitespace(raw.charAt(cursor - 1))) {
            cursor--;
        }
        int lineBreakStart = trailingLineBreakStart(raw, cursor);
        if (lineBreakStart < 0) {
            return new RawGapRegion(region, false);
        }
        return new RawGapRegion(sourceText.region(region.beginOffset(), region.beginOffset() + lineBreakStart), true);
    }

    private FormatterException recoveryFailure(RecoveredSourceRegions.CrossingCommentBoundaryException exception) {
        return Objects.requireNonNull(
            recoveryFailure.apply(exception.getMessage(), exception),
            "recoveryFailure result"
        );
    }

    private static int trailingLineBreakStart(String raw, int endExclusive) {
        if (endExclusive <= 0) {
            return -1;
        }
        char last = raw.charAt(endExclusive - 1);
        if (last == '\n') {
            return endExclusive > 1 && raw.charAt(endExclusive - 2) == '\r' ? endExclusive - 2 : endExclusive - 1;
        }
        if (last == '\r') {
            return endExclusive - 1;
        }
        return -1;
    }

    private static boolean isHorizontalWhitespace(char value) {
        return value != '\r' && value != '\n' && Character.isWhitespace(value);
    }

    /**
     * Describes the raw source region to emit and whether its final line break moved to formatter-owned docs.
     */
    record RawGapRegion(SourceRegion region, boolean trailingBreakReplaced) {}
}
