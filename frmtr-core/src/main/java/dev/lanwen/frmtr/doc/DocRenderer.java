package dev.lanwen.frmtr.doc;

import dev.lanwen.frmtr.FormatterOptions;

public final class DocRenderer {
    /** Sentinel flat width signalling that a document contains a forced break and cannot fit on one line. */
    private static final int NO_FIT = -1;

    private final FormatterOptions options;
    private final StringBuilder out = new StringBuilder();
    private int column;

    public DocRenderer(FormatterOptions options) {
        this.options = options;
    }

    public String render(Doc doc) {
        out.setLength(0);
        column = 0;
        render(doc, 0, Mode.BREAK);
        String rendered = out.toString();
        if (options.trailingNewline() && !rendered.endsWith(options.lineEnding().value())) {
            rendered += options.lineEnding().value();
        }
        return rendered;
    }

    private void render(Doc doc, int indent, Mode mode) {
        switch (doc) {
            case Doc.Text text -> append(text.value());
            case Doc.Concat concat -> concat.docs().forEach(child -> render(child, indent, mode));
            case Doc.Line _ -> {
                if (mode == Mode.FLAT) {
                    append(" ");
                } else {
                    newline(indent);
                }
            }
            case Doc.SoftLine _ -> {
                if (mode == Mode.BREAK) {
                    newline(indent);
                }
            }
            case Doc.HardLine _ -> newline(indent);
            case Doc.Indent indented -> render(indented.doc(), indent + 1, mode);
            case Doc.Group group -> {
                Mode next = fits(group.doc(), options.lineWidth() - column) ? Mode.FLAT : Mode.BREAK;
                render(group.doc(), indent, next);
            }
            case Doc.IfBreak conditional -> render(mode == Mode.BREAK ? conditional.breakDoc() : conditional.flatDoc(), indent, mode);
            case Doc.Label label -> render(label.doc(), indent, mode);
        }
    }

    /**
     * Returns whether {@code doc} can be laid out flat within {@code remaining} columns.
     *
     * <p>A group fits only when its flat width is finite (no forced break) and no wider than the space left on the
     * current line.
     */
    private boolean fits(Doc doc, int remaining) {
        int width = measureFlat(doc, Mode.FLAT);
        return width != NO_FIT && width <= remaining;
    }

    /**
     * Measures the flat-mode display width of {@code doc}, or {@link #NO_FIT} when it contains a forced break
     * ({@link Doc.HardLine}) and therefore cannot be laid out on a single line.
     *
     * <p>This is the single width authority for the renderer: {@link #fits(Doc, int)} compares its result against the
     * remaining columns, so fit decisions and width arithmetic never diverge. Indentation does not affect flat width,
     * so it is intentionally not threaded here. {@code mode} only selects the {@link Doc.IfBreak} branch; nested groups
     * always measure flat.
     */
    private int measureFlat(Doc doc, Mode mode) {
        return switch (doc) {
            case Doc.Text text -> text.value().length();
            case Doc.Concat concat -> {
                int width = 0;
                for (Doc child : concat.docs()) {
                    int childWidth = measureFlat(child, mode);
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
            case Doc.Indent indented -> measureFlat(indented.doc(), mode);
            case Doc.Group group -> measureFlat(group.doc(), Mode.FLAT);
            case Doc.IfBreak conditional -> measureFlat(mode == Mode.BREAK ? conditional.breakDoc() : conditional.flatDoc(), mode);
            case Doc.Label label -> measureFlat(label.doc(), mode);
        };
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

    private void newline(int indent) {
        trimTrailingHorizontalWhitespace();
        out.append(options.lineEnding().value())
            .repeat(options.indentUnit(), indent);
        column = options.indentUnit().length() * indent;
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
        BREAK
    }
}
