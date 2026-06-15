package dev.lanwen.frmtr.doc;

/**
 * A width decision a Java printer made for itself, recorded at the point the printer chose a broken layout because a
 * measured flat candidate did not fit a width budget.
 *
 * <p>This is the authoritative answer to "why did this wrap on width?" for the constructs developers actually debug:
 * method chains, argument lists, ternaries, and control conditions are pre-measured by their printers and emitted as
 * {@link Doc.HardLine}s, so by the time the renderer walks the document the width arithmetic is already gone. The
 * renderer only sees a forced break and cannot say how wide the construct was or what budget it blew. Capturing the
 * decision where it is made preserves the real numbers and a human construct name.
 *
 * <p>It is recorded by a pure side channel during printing and never influences the {@link Doc} that is built or the
 * text the renderer emits; it only lets {@link DocExplanation} report a true width reason instead of an opaque "forced
 * break". The {@code construct} is a friendly, user-facing kind ("method chain", "argument list", "ternary", "if
 * condition"); {@code label} carries the same {@code java.*:} rule provenance as the renderer trace so the two can be
 * matched and de-duplicated; {@code preview} is a short human snippet of the construct (for example
 * {@code foo().bar()…}); {@code flatWidth} is the single-line width the printer measured; {@code available} is the width
 * budget it was measured against; and {@code segments} is how many pieces the broken layout produced (one per line),
 * or zero when a construct does not break into countable segments.
 *
 * @param construct friendly construct kind shown to the user
 * @param label the {@code java.*:} rule label that owns the construct, for matching against the renderer trace
 * @param preview a short snippet of the construct for the headline
 * @param flatWidth the single-line width the printer measured for the construct
 * @param available the width budget the flat candidate was measured against
 * @param segments how many segments the broken layout produced, or zero when not applicable
 */
public record PrinterWrap(
    String construct,
    String label,
    String preview,
    int flatWidth,
    int available,
    int segments
) {}
