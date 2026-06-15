package dev.lanwen.frmtr.doc;

/**
 * Single width authority for document layout: measures the flat-mode width of a {@link Doc} and answers whether it fits
 * in a given number of columns.
 *
 * <p>This helper owns only width arithmetic, intentionally separated from the rendering walk so that the renderer and
 * any observer of its decisions (such as {@link DocExplainRenderer}) compute fit identically. Centralizing it here means
 * a fit decision and the width number reported for it can never diverge. The boundary deliberately excludes column
 * tracking, indentation text, and line endings: those are render concerns owned by {@link DocRenderer}, because
 * indentation does not affect flat width.
 */
final class DocWidths {

    /** Sentinel flat width signalling that a document contains a forced break and cannot fit on one line. */
    static final int NO_FIT = -1;

    private DocWidths() {}

    /**
     * Returns whether {@code doc} can be laid out flat within {@code remaining} columns.
     *
     * <p>A group fits only when its flat width is finite (no forced break) and no wider than the space left on the
     * current line.
     */
    static boolean fits(Doc doc, int remaining) {
        int width = flatWidth(doc);
        return width != NO_FIT && width <= remaining;
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
        return switch (doc) {
            case Doc.Text text -> text.value().length();
            case Doc.Concat concat -> {
                int width = 0;
                for (Doc child : concat.docs()) {
                    int childWidth = flatWidth(child);
                    if (childWidth == NO_FIT) {
                        yield NO_FIT;
                    }
                    width += childWidth;
                }
                yield width;
            }
            case Doc.Line _ -> 1;
            case Doc.SoftLine _ -> 0;
            case Doc.HardLine _ -> NO_FIT;
            case Doc.Indent indented -> flatWidth(indented.doc());
            case Doc.Group group -> flatWidth(group.doc());
            case Doc.IfBreak conditional -> flatWidth(conditional.flatDoc());
            case Doc.Label label -> flatWidth(label.doc());
        };
    }
}
