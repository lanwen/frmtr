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
                case Doc.Concat concat -> measureConcat(concat, remaining);
                case Doc.Line ignored -> 1;
                case Doc.SoftLine ignored -> 0;
                case Doc.HardLine ignored -> NO_FIT;
                case Doc.Indent indented -> measure(indented.doc(), remaining);
                case Doc.Group group -> measure(group.doc(), remaining);
                case Doc.IfBreak conditional -> measure(conditional.flatDoc(), remaining);
                case Doc.Label label -> measure(label.doc(), remaining);
            };
            if (measured != OVERFLOW) {
                flatWidths.put(doc, measured);
            }
            return measured;
        }

        private int measureConcat(Doc.Concat concat, int remaining) {
            int total = 0;
            for (int i = 0; i < concat.docs().size(); i++) {
                Doc child = concat.docs().get(i);
                int measured = measure(child, remainingAfter(remaining, total));
                if (measured == NO_FIT) {
                    return NO_FIT;
                }
                if (measured == OVERFLOW) {
                    return OVERFLOW;
                }
                total += measured;
                if (exceeds(remaining, total) && i < concat.docs().size() - 1) {
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
