package dev.lanwen.frmtr.doc;

import java.util.IdentityHashMap;
import java.util.List;

/**
 * Single width authority for document layout: measures the flat-mode width of a {@link Doc} and answers whether it fits
 * in a given number of columns.
 *
 * <p>This helper owns only width arithmetic, intentionally separated from the rendering walk so that the renderer and
 * any observer of its decisions (such as {@link DocExplainRenderer}) compute fit identically. Centralizing it here means
 * a fit decision and the width number reported for it can never diverge. Each render/explain pass gets a fresh
 * {@link Measurement} so memoized widths are reused only inside that pass and cannot leak across renders. The boundary
 * deliberately excludes column tracking, indentation text, and line endings: those are render concerns owned by
 * {@link DocRenderer}, because indentation does not affect flat width.
 */
final class DocWidths {

    /** Sentinel flat width signalling that a document contains a forced break and cannot fit on one line. */
    static final int NO_FIT = Integer.MIN_VALUE;

    /** Internal sentinel for bounded measurements that stopped after overflow before finding a complete width. */
    private static final int OVERFLOW = Integer.MIN_VALUE + 1;

    private static final int UNBOUNDED = Integer.MAX_VALUE;

    private DocWidths() {}

    static Measurement measurement() {
        return new Measurement();
    }

    /**
     * Returns whether {@code doc} can be laid out flat within {@code remaining} columns.
     *
     * <p>A group fits only when its flat width is finite (no forced break) and no wider than the space left on the
     * current line.
     */
    static boolean fits(Doc doc, int remaining) {
        return measurement().fits(doc, remaining);
    }

    /**
     * Measures the flat-mode display width of {@code doc}, or {@link #NO_FIT} when it contains a forced break
     * ({@link Doc.HardLine} or {@link Doc.BreakParent}) and therefore cannot be laid out on a single line.
     *
     * <p>Indentation does not affect flat width, so it is intentionally not threaded here. Conditional {@link
     * Doc.IfBreak} nodes always contribute their flat branch and nested groups always measure flat, because this
     * measures the width a subtree would occupy if it were laid out on a single line. A {@link Doc.ConditionalGroup} is
     * measured by its first (most-flat) alternative, the representative layout an enclosing group reasons about. A
     * {@link Doc.LineSuffix} contributes zero width: its content is deferred to the line break, so it never widens the
     * line it sits on or forces an enclosing group to break.
     */
    static int flatWidth(Doc doc) {
        return measurement().flatWidth(doc);
    }

    /**
     * Per-render width measurement state.
     *
     * <p>The cache stores only complete flat widths, never the result of a bounded fit that stopped after overflow.
     * {@link Doc.IfBreak} is measured by its flat branch only, matching the renderer's group-fit question: "can this
     * group render on one line?"
     */
    static final class Measurement {

        private final IdentityHashMap<Doc, Integer> flatWidths = new IdentityHashMap<>();

        private Measurement() {}

        boolean fits(Doc doc, int remaining) {
            if (remaining < 0) {
                return false;
            }
            Integer cached = flatWidths.get(doc);
            if (cached != null) {
                return fitsWidth(cached, remaining);
            }
            int measured = measure(doc, remaining);
            return fitsWidth(measured, remaining);
        }

        /**
         * The per-separator FLAT-vs-BREAK fit decision a {@link Doc.Fill} makes for each separator, expressed as
         * "should this separator stay flat?". A fill keeps a separator flat when the separator together with the next
         * content still fits in the columns left on the current line, and breaks only that separator otherwise.
         *
         * <p>This boundary exists so the rendering walk ({@link DocRenderer#render(Doc)}) and the {@code --explain}
         * trace ({@link DocExplainRenderer}) ask the fit question through one place and cannot silently drift: both
         * must advance their column cursor by the same flat/break choice or the explained layout would diverge from the
         * emitted output. It centralizes only the correctness-relevant probe — the same {@link Doc#concat} of separator
         * and next content against the same shared width authority. The caller still owns supplying its own remaining
         * width (each renderer subtracts its own tracked column from the configured line width), mapping the returned
         * boolean onto its own flat/break mode, and the per-step action that follows (emitting text versus recording a
         * trace node).
         */
        boolean separatorFitsFlat(Doc separator, Doc nextContent, int remaining) {
            return fits(Doc.concat(separator, nextContent), remaining);
        }

        int flatWidth(Doc doc) {
            return measure(doc, UNBOUNDED);
        }

        private int measure(Doc doc, int remaining) {
            if (remaining < 0) {
                return OVERFLOW;
            }
            Integer cached = flatWidths.get(doc);
            if (cached != null) {
                return cached;
            }
            int measured = switch (doc) {
                case Doc.Text text -> text.value().length();
                case Doc.Concat concat -> measureSequence(concat.docs(), remaining);
                case Doc.Fill fill -> measureSequence(fill.parts(), remaining);
                // An enclosing group measures a conditional group by its first (most-flat) alternative, the layout the
                // renderer prefers when it fits.
                case Doc.ConditionalGroup conditionalGroup ->
                    measure(conditionalGroup.alternatives().getFirst(), remaining);
                case Doc.Line ignored -> 1;
                case Doc.SoftLine ignored -> 0;
                case Doc.HardLine ignored -> NO_FIT;
                case Doc.BreakParent ignored -> NO_FIT;
                case Doc.Indent indented -> measure(indented.doc(), remaining);
                case Doc.Group group -> measure(group.doc(), remaining);
                case Doc.IfBreak conditional -> measure(conditional.flatDoc(), remaining);
                case Doc.Label label -> measure(label.doc(), remaining);
                case Doc.LineSuffix ignored -> 0;
            };
            if (measured != OVERFLOW) {
                flatWidths.put(doc, measured);
            }
            return measured;
        }

        /**
         * Measures a child sequence as the sum of its parts' flat widths, bounded by {@code remaining}. Used for both
         * {@link Doc.Concat} and {@link Doc.Fill}: a fill's flat width is the concatenation of all its parts (each
         * separator counted at its flat width), which over-estimates the fill but is the safe width to report to an
         * enclosing group deciding its own mode — if the whole fill fits flat, so does any greedily packed layout of it.
         */
        private int measureSequence(List<Doc> parts, int remaining) {
            int total = 0;
            for (int i = 0; i < parts.size(); i++) {
                Doc child = parts.get(i);
                int measured = measure(child, remainingAfter(remaining, total));
                if (measured == NO_FIT) {
                    return NO_FIT;
                }
                if (measured == OVERFLOW) {
                    return OVERFLOW;
                }
                total += measured;
                if (exceeds(remaining, total) && i < parts.size() - 1) {
                    return OVERFLOW;
                }
            }
            return total;
        }

        private int remainingAfter(int remaining, int used) {
            return remaining == UNBOUNDED ? UNBOUNDED : remaining - used;
        }

        private boolean exceeds(int remaining, int width) {
            return remaining != UNBOUNDED && width > remaining;
        }

        private boolean fitsWidth(int measured, int remaining) {
            return measured >= 0 && measured <= remaining;
        }
    }
}
