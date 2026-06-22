package dev.lanwen.frmtr.doc;

import dev.lanwen.frmtr.FormatterOptions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class DocRenderer {

    private final FormatterOptions options;

    private final StringBuilder out = new StringBuilder();

    /**
     * Trailing content parked by {@link Doc.LineSuffix} that has not yet been flushed. Each entry remembers the
     * indent/mode in scope where the suffix was reached, so it renders at the same layout it would have had inline,
     * and the list preserves document order so multiple suffixes on a line flush in the order they were buffered.
     */
    private final List<BufferedSuffix> lineSuffixes = new ArrayList<>();

    /**
     * Mode chosen by each identified {@link Doc.Group}, keyed by its {@code groupId}. A dependent {@link Doc.IfBreak}
     * with a matching id reads this map instead of the ambient mode, so a closing delimiter can follow the break/flat
     * decision of an opener group it does not enclose. Populated as each identified group renders and reset per render,
     * which requires the identified group to render before the {@code IfBreak} that targets it.
     */
    private final Map<String, Mode> groupModes = new HashMap<>();

    private int column;

    public DocRenderer(FormatterOptions options) {
        this.options = options;
    }

    public String render(Doc doc) {
        out.setLength(0);
        column = 0;
        lineSuffixes.clear();
        groupModes.clear();
        DocWidths.Measurement widths = DocWidths.measurement();
        render(doc, 0, Mode.BREAK, widths);
        flushLineSuffixes(widths);
        String rendered = out.toString();
        if (options.trailingNewline() && !rendered.endsWith(options.lineEnding().value())) {
            rendered += options.lineEnding().value();
        }
        return rendered;
    }

    private void render(Doc doc, int indent, Mode mode, DocWidths.Measurement widths) {
        switch (doc) {
            case Doc.Text text -> append(text.value());
            case Doc.Concat concat -> concat.docs().forEach(child -> render(child, indent, mode, widths));
            case Doc.Line ignored -> {
                if (mode == Mode.FLAT) {
                    append(" ");
                } else {
                    newline(indent, widths);
                }
            }
            case Doc.SoftLine ignored -> {
                if (mode == Mode.BREAK) {
                    newline(indent, widths);
                }
            }
            case Doc.HardLine ignored -> newline(indent, widths);
            case Doc.BreakParent ignored -> {
                // Emits nothing: the break it forces already happened when the enclosing group measured this marker
                // and chose BREAK mode (see DocWidths, where BreakParent yields NO_FIT exactly like HardLine).
            }
            case Doc.Indent indented -> render(indented.doc(), indent + 1, mode, widths);
            case Doc.Group group -> {
                Mode next = widths.fits(group.doc(), options.lineWidth() - column) ? Mode.FLAT : Mode.BREAK;
                if (group.groupId() != null) {
                    groupModes.put(group.groupId(), next);
                }
                render(group.doc(), indent, next, widths);
            }
            case Doc.Fill fill -> renderFill(fill.parts(), indent, widths);
            case Doc.ConditionalGroup conditionalGroup -> renderConditionalGroup(conditionalGroup.alternatives(), indent, widths);
            case Doc.IfBreak conditional -> {
                // An identified IfBreak follows the recorded mode of its target group (which must have rendered first);
                // an anonymous IfBreak follows the ambient mode. A target that has not rendered yet is treated as flat.
                Mode effective = conditional.groupId() == null
                    ? mode
                    : groupModes.getOrDefault(conditional.groupId(), Mode.FLAT);
                render(
                    effective == Mode.BREAK ? conditional.breakDoc() : conditional.flatDoc(),
                    indent,
                    mode,
                    widths
                );
            }
            case Doc.Label label -> render(label.doc(), indent, mode, widths);
            case Doc.LineSuffix lineSuffix -> {
                requireSingleLineSuffix(lineSuffix.content());
                lineSuffixes.add(new BufferedSuffix(lineSuffix.content(), indent, mode));
            }
        }
    }

    /**
     * Renders a {@link Doc.Fill}'s alternating {@code [content, separator, …]} parts with greedy per-separator packing:
     * every content piece renders flat, and each separator is laid out flat when the separator plus the next content
     * would still fit on the current line and broken otherwise. The fit probe reuses the shared {@link DocWidths}
     * authority, so it stays compatible with the renderer's memoized, bounded width measurement: each lookahead measures
     * only one separator and one following content (their flat widths, individually memoized by node identity), never the
     * whole tail, which keeps the walk linear in the number of parts rather than quadratic.
     */
    private void renderFill(List<Doc> parts, int indent, DocWidths.Measurement widths) {
        if (parts.isEmpty()) {
            return;
        }
        render(parts.getFirst(), indent, Mode.FLAT, widths);
        for (int i = 1; i + 1 < parts.size(); i += 2) {
            Doc separator = parts.get(i);
            Doc nextContent = parts.get(i + 1);
            // Decide this separator from the column reached after the preceding content via the shared fit helper, so
            // the renderer and the --explain trace make the identical per-separator flat/break choice.
            Mode separatorMode = widths.separatorFitsFlat(separator, nextContent, options.lineWidth() - column)
                ? Mode.FLAT
                : Mode.BREAK;
            render(separator, indent, separatorMode, widths);
            render(nextContent, indent, Mode.FLAT, widths);
        }
    }

    /**
     * Renders a {@link Doc.ConditionalGroup} by choosing the first alternative (in order, including the last) whose flat
     * layout fits the space left on the current line and rendering it flat; if no alternative fits, the last is rendered
     * in break mode as the unconditional fallback. This mirrors the {@link Doc.Group} fit decision but over an ordered
     * list of candidates: each candidate is probed with the shared {@link DocWidths} authority (the same question a group
     * asks itself), so a conditional group composes with the renderer's memoized, bounded width measurement. Because
     * every non-last alternative is chosen purely by flat fit, an alternative containing a forced break never fits and is
     * skipped, so only the last alternative is reachable as a broken layout (see {@link Doc#conditionalGroup}). The
     * factory rejects an empty list, so this walk always has at least one alternative to fall back on.
     */
    private void renderConditionalGroup(List<Doc> alternatives, int indent, DocWidths.Measurement widths) {
        for (Doc alternative : alternatives) {
            if (widths.fits(alternative, options.lineWidth() - column)) {
                render(alternative, indent, Mode.FLAT, widths);
                return;
            }
        }
        // No alternative fits flat, so render the last one in break mode as the unconditional fallback.
        render(alternatives.getLast(), indent, Mode.BREAK, widths);
    }

    private void append(String value) {
        out.append(value);
        int lastLineBreak = value.lastIndexOf('\n');
        if (lastLineBreak >= 0) {
            column = value.length() - lastLineBreak - 1;
        } else {
            column += value.length();
        }
    }

    private void newline(int indent, DocWidths.Measurement widths) {
        flushLineSuffixes(widths);
        trimTrailingHorizontalWhitespace();
        out.append(options.lineEnding().value())
                .repeat(options.indentUnit(), indent);
        column = options.indentUnit().length() * indent;
    }

    /**
     * Renders every buffered {@link Doc.LineSuffix} at its captured indent/mode, in document order, then empties the
     * buffer. A suffix that itself buffers another suffix would re-enter this method, so the buffer is drained until
     * empty rather than iterated once; restricting suffix content to single lines keeps that drain finite.
     */
    private void flushLineSuffixes(DocWidths.Measurement widths) {
        while (!lineSuffixes.isEmpty()) {
            List<BufferedSuffix> pending = List.copyOf(lineSuffixes);
            lineSuffixes.clear();
            for (BufferedSuffix suffix : pending) {
                render(suffix.content(), suffix.indent(), suffix.mode(), widths);
            }
        }
    }

    /**
     * Guards the version-one restriction that line-suffix content is single-line: a {@link Doc.HardLine} buried in a
     * suffix would, once flushed at a line break, emit a second break and could retroactively change a layout already
     * decided around the (zero-width) suffix. All trailing-comment call sites produce single-line content.
     */
    private static void requireSingleLineSuffix(Doc content) {
        if (containsHardLine(content)) {
            throw new IllegalArgumentException("LineSuffix content must be single-line, but contained a hard line break");
        }
    }

    private static boolean containsHardLine(Doc doc) {
        return switch (doc) {
            case Doc.HardLine ignored -> true;
            case Doc.Concat concat -> concat.docs().stream().anyMatch(DocRenderer::containsHardLine);
            case Doc.Fill fill -> fill.parts().stream().anyMatch(DocRenderer::containsHardLine);
            case Doc.ConditionalGroup conditionalGroup ->
                conditionalGroup.alternatives().stream().anyMatch(DocRenderer::containsHardLine);
            case Doc.Indent indented -> containsHardLine(indented.doc());
            case Doc.Group group -> containsHardLine(group.doc());
            case Doc.Label label -> containsHardLine(label.doc());
            case Doc.IfBreak conditional ->
                containsHardLine(conditional.breakDoc()) || containsHardLine(conditional.flatDoc());
            case Doc.LineSuffix lineSuffix -> containsHardLine(lineSuffix.content());
            case Doc.Text ignored -> false;
            case Doc.Line ignored -> false;
            case Doc.SoftLine ignored -> false;
            // BreakParent forces a group break but emits no newline, so it does not violate the single-line restriction.
            case Doc.BreakParent ignored -> false;
        };
    }

    private void trimTrailingHorizontalWhitespace() {
        while (!out.isEmpty()) {
            char last = out.charAt(out.length() - 1);
            if (last != ' ' && last != '\t') {
                break;
            }
            out.setLength(out.length() - 1);
        }
    }

    private enum Mode {
        FLAT,
        BREAK,
    }

    private record BufferedSuffix(Doc content, int indent, Mode mode) {}
}
