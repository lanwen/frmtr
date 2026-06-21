package dev.lanwen.frmtr.doc;

import dev.lanwen.frmtr.FormatterOptions;
import java.util.ArrayList;
import java.util.List;

public final class DocRenderer {

    private final FormatterOptions options;

    private final StringBuilder out = new StringBuilder();

    /**
     * Trailing content parked by {@link Doc.LineSuffix} that has not yet been flushed. Each entry remembers the
     * indent/mode in scope where the suffix was reached, so it renders at the same layout it would have had inline,
     * and the list preserves document order so multiple suffixes on a line flush in the order they were buffered.
     */
    private final List<BufferedSuffix> lineSuffixes = new ArrayList<>();

    private int column;

    public DocRenderer(FormatterOptions options) {
        this.options = options;
    }

    public String render(Doc doc) {
        out.setLength(0);
        column = 0;
        lineSuffixes.clear();
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
            case Doc.Indent indented -> render(indented.doc(), indent + 1, mode, widths);
            case Doc.Group group -> {
                Mode next = widths.fits(group.doc(), options.lineWidth() - column) ? Mode.FLAT : Mode.BREAK;
                render(group.doc(), indent, next, widths);
            }
            case Doc.IfBreak conditional -> render(
                mode == Mode.BREAK ? conditional.breakDoc() : conditional.flatDoc(),
                indent,
                mode,
                widths
            );
            case Doc.Label label -> render(label.doc(), indent, mode, widths);
            case Doc.LineSuffix lineSuffix -> {
                requireSingleLineSuffix(lineSuffix.content());
                lineSuffixes.add(new BufferedSuffix(lineSuffix.content(), indent, mode));
            }
        }
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
            case Doc.Indent indented -> containsHardLine(indented.doc());
            case Doc.Group group -> containsHardLine(group.doc());
            case Doc.Label label -> containsHardLine(label.doc());
            case Doc.IfBreak conditional ->
                containsHardLine(conditional.breakDoc()) || containsHardLine(conditional.flatDoc());
            case Doc.LineSuffix lineSuffix -> containsHardLine(lineSuffix.content());
            case Doc.Text ignored -> false;
            case Doc.Line ignored -> false;
            case Doc.SoftLine ignored -> false;
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
