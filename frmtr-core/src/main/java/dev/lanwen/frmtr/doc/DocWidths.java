package dev.lanwen.frmtr.doc;

import java.util.IdentityHashMap;

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
     * ({@link Doc.HardLine}) and therefore cannot be laid out on a single line.
     *
     * <p>Indentation does not affect flat width, so it is intentionally not threaded here. Conditional {@link
     * Doc.IfBreak} nodes always contribute their flat branch and nested groups always measure flat, because this
     * measures the width a subtree would occupy if it were laid out on a single line.
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
            return remaining >= 0 && measure(doc, Budget.bounded(remaining)).fitsWithin(remaining);
        }

        int flatWidth(Doc doc) {
            MeasureResult measured = measure(doc, Budget.unbounded());
            return measured.forcedBreak() ? NO_FIT : measured.width();
        }

        private MeasureResult measure(Doc doc, Budget budget) {
            if (budget.overflowed()) {
                return MeasureResult.overflow();
            }
            Integer cached = flatWidths.get(doc);
            if (cached != null) {
                return cached == NO_FIT ? MeasureResult.hardLine() : MeasureResult.complete(cached);
            }
            MeasureResult measured = switch (doc) {
                case Doc.Text text -> MeasureResult.complete(text.value().length());
                case Doc.Concat concat -> measureConcat(concat, budget);
                case Doc.Line _ -> MeasureResult.complete(1);
                case Doc.SoftLine _ -> MeasureResult.complete(0);
                case Doc.HardLine _ -> MeasureResult.hardLine();
                case Doc.Indent indented -> measure(indented.doc(), budget);
                case Doc.Group group -> measure(group.doc(), budget);
                case Doc.IfBreak conditional -> measure(conditional.flatDoc(), budget);
                case Doc.Label label -> measure(label.doc(), budget);
            };
            if (measured.complete()) {
                flatWidths.put(doc, measured.forcedBreak() ? NO_FIT : measured.width());
            }
            return measured;
        }

        private MeasureResult measureConcat(Doc.Concat concat, Budget budget) {
            int total = 0;
            for (int i = 0; i < concat.docs().size(); i++) {
                Doc child = concat.docs().get(i);
                MeasureResult measured = measure(child, budget.after(total));
                if (measured.forcedBreak()) {
                    return MeasureResult.hardLine();
                }
                if (!measured.complete()) {
                    return MeasureResult.overflow();
                }
                total += measured.width();
                if (budget.exceededBy(total) && i < concat.docs().size() - 1) {
                    return MeasureResult.overflow();
                }
            }
            return MeasureResult.complete(total);
        }
    }

    private record Budget(boolean bounded, int remaining) {

        private static Budget bounded(int remaining) {
            return new Budget(true, remaining);
        }

        private static Budget unbounded() {
            return new Budget(false, Integer.MAX_VALUE);
        }

        private Budget after(int used) {
            return bounded ? bounded(remaining - used) : this;
        }

        private boolean exceededBy(int width) {
            return bounded && width > remaining;
        }

        private boolean overflowed() {
            return bounded && remaining < 0;
        }
    }

    private record MeasureResult(int width, boolean complete, boolean forcedBreak) {

        private static MeasureResult complete(int width) {
            return new MeasureResult(width, true, false);
        }

        private static MeasureResult overflow() {
            return new MeasureResult(0, false, false);
        }

        private static MeasureResult hardLine() {
            return new MeasureResult(NO_FIT, true, true);
        }

        private boolean fitsWithin(int remaining) {
            return complete && !forcedBreak && width <= remaining;
        }
    }
}
