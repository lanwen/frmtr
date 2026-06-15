package dev.lanwen.frmtr.java;

import dev.lanwen.frmtr.doc.PrinterWrap;
import java.util.ArrayList;
import java.util.List;

/**
 * Per-run side channel where width-deciding Java printers record the flat-candidate measurements behind a broken
 * layout, so explain can report real width arithmetic for constructs the renderer never width-fits.
 *
 * <p>Method chains, argument lists, ternaries, and control conditions choose between a flat and a broken shape inside
 * their printer by measuring a compact candidate against a {@link LayoutWidth} budget, then emit the chosen shape as
 * {@link dev.lanwen.frmtr.doc.Doc.HardLine}s. That measurement is the actual "why it wrapped" reason, but it is gone by
 * the time the renderer walks the document, which only sees a forced break. This log captures the decision at the
 * point it is made.
 *
 * <p>The boundary is deliberately observational: a printer appends to the log <em>after</em> it has already decided to
 * break for width, so recording cannot change which {@link dev.lanwen.frmtr.doc.Doc} is built or what the renderer
 * emits. The same {@link JavaFormatContext} owns one log per formatting run; {@link #format} runs never read it, so the
 * recording is free of behavioral consequence and the rendered output stays byte-for-byte identical. Callers decide
 * which constructs are worth recording and supply the friendly construct name; this helper only stores and hands back
 * the recorded decisions.
 */
final class LayoutDecisionLog {

    private final List<PrinterWrap> wraps = new ArrayList<>();

    /**
     * Records that a printer chose a broken layout because {@code flatWidth} exceeded {@code available}.
     *
     * <p>Recording is idempotent in spirit but not de-duplicated here: a printer should call this once per construct it
     * breaks for width, at the single point where it commits to the broken shape. The {@code label} should match the
     * {@code java.*:} rule label the renderer trace attributes the same construct to, so explain can merge the two and
     * report each wrap once.
     */
    void recordWidthBreak(
            String construct,
            String label,
            String preview,
            int flatWidth,
            int available,
            int segments
    ) {
        wraps.add(new PrinterWrap(construct, label, preview, flatWidth, available, segments));
    }

    /**
     * Returns the recorded width decisions in the order printers committed to them, which is the order the constructs
     * appear in source.
     */
    List<PrinterWrap> wraps() {
        return List.copyOf(wraps);
    }
}
