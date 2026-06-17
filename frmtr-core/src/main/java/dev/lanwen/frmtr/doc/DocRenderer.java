package dev.lanwen.frmtr.doc;

import dev.lanwen.frmtr.FormatterOptions;

public final class DocRenderer {

    private final FormatterOptions options;

    private final StringBuilder out = new StringBuilder();

    private int column;

    public DocRenderer(FormatterOptions options) {
        this.options = options;
    }

    public String render(Doc doc) {
        out.setLength(0);
        column = 0;
        DocWidths.Measurement widths = DocWidths.measurement();
        render(doc, 0, Mode.BREAK, widths);
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
                    newline(indent);
                }
            }
            case Doc.SoftLine ignored -> {
                if (mode == Mode.BREAK) {
                    newline(indent);
                }
            }
            case Doc.HardLine ignored -> newline(indent);
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
        BREAK,
    }
}
