package dev.lanwen.frmtr;

import java.util.List;

/**
 * Formatted source paired with a per-line structural indentation signal, produced by {@link Frmtr#formatIndented}.
 *
 * <p>{@link #text()} is byte-for-byte identical to {@link Frmtr#format(String, FormatterOptions)} for the same input —
 * asking for the indentation signal never changes the formatting. What it adds is {@link #lines()}: one {@link Line}
 * per output line (splitting {@code text} on line feeds, in order), each carrying whether that line's leading
 * whitespace is a structural indent the formatter chose and, if so, at which indent <em>level</em>.
 *
 * <p>This exists so a visualization such as the CLI {@code --render-indentation} can distinguish a block indent from a
 * continuation indent — a distinction the finished text cannot express, because a block level and a continuation offset
 * are both just leading whitespace, and tabs make column arithmetic ambiguous. The renderer, by contrast, knows the
 * true level at every newline. Deciding whether a given structural indent reads as a block or a continuation is left to
 * the consumer: that is a presentation policy, and this result carries only the structural facts behind it.
 *
 * @param text the formatted source, identical to {@link Frmtr#format(String, FormatterOptions)} for the same input
 * @param lines the structural indentation fact for each output line, in order
 */
public record IndentedSource(String text, List<Line> lines) {

    public IndentedSource {
        lines = List.copyOf(lines);
    }

    /**
     * Structural indentation fact for one output line.
     *
     * <p>{@link #structural()} is true when the line's leading whitespace was emitted by the formatter as a chosen
     * indent, in which case {@link #level()} is the indent-unit count of that indent (independent of tab width). It is
     * false for text-block interior lines, whose leading whitespace is literal program data rather than layout; for
     * those {@code level} is not meaningful and a visualization should leave the indentation exactly as emitted.
     *
     * @param structural whether the leading whitespace is a formatter-chosen indent (vs literal text-block content)
     * @param level the indent-unit level of that structural indent; not meaningful when {@code structural} is false
     */
    public record Line(boolean structural, int level) {}
}
