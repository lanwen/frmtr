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

    /**
     * Discards all recorded width decisions so the next render starts with an empty log.
     *
     * <p>Used by the record-only dry-run pre-pass (see {@link CommentTracker#endRecordingAndReset}): the scratch pass
     * runs the same width-deciding printers and appends to this log, so it must be cleared before the real pass to keep
     * {@code --explain} from reporting each wrap twice. {@link #format} runs never trigger the dry-run, so the log
     * behaves exactly as before for them.
     */
    void reset() {
        wraps.clear();
    }

    /**
     * Returns the number of width decisions recorded so far.
     *
     * <p>Paired with {@link #truncateTo(int)} so the speculative scope in {@link CommentTracker#speculatively} can
     * snapshot the log length on entry and roll back to it when a discarded probe is abandoned. A probe that measures a
     * width-breaking construct appends a wrap as a side effect; if its candidate loses, that wrap describes a layout the
     * renderer never emits and must be dropped so {@code --explain} stays free of phantom decisions.
     */
    int size() {
        return wraps.size();
    }

    /**
     * Drops every width decision recorded after {@code size}, restoring the log to a length captured earlier by
     * {@link #size()}.
     *
     * <p>The log only ever grows by appending, so truncating from the tail removes exactly the decisions a discarded
     * probe added and leaves the decisions that preceded the speculative scope intact. {@code size} must come from a
     * prior {@link #size()} on this same log; a value at or past the current length is a no-op.
     */
    void truncateTo(int size) {
        if (size < wraps.size()) {
            wraps.subList(size, wraps.size()).clear();
        }
    }
}
