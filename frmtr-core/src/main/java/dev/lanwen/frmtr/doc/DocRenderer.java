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
                Mode next = fits(group.doc(), indent, options.lineWidth() - column) ? Mode.FLAT : Mode.BREAK;
                render(group.doc(), indent, next);
            }
            case Doc.IfBreak conditional -> render(mode == Mode.BREAK ? conditional.breakDoc() : conditional.flatDoc(), indent, mode);
            case Doc.Label label -> render(label.doc(), indent, mode);
        }
    }

    private boolean fits(Doc doc, int indent, int remaining) {
        return fits(doc, indent, remaining, Mode.FLAT);
    }

    private boolean fits(Doc doc, int indent, int remaining, Mode mode) {
        if (remaining < 0) {
            return false;
        }
        return switch (doc) {
            case Doc.Text text -> text.value().length() <= remaining;
            case Doc.Concat concat -> {
                int rest = remaining;
                boolean ok = true;
                for (Doc child : concat.docs()) {
                    int width = flatWidth(child, indent, mode);
                    if (width < 0 || width > rest) {
                        ok = false;
                        break;
                    }
                    rest -= width;
                }
                yield ok;
            }
            case Doc.Line _ -> remaining >= 1;
            case Doc.SoftLine _ -> true;
            case Doc.HardLine _ -> false;
            case Doc.Indent indented -> fits(indented.doc(), indent + 1, remaining, mode);
            case Doc.Group group -> fits(group.doc(), indent, remaining, Mode.FLAT);
            case Doc.IfBreak conditional -> fits(mode == Mode.BREAK ? conditional.breakDoc() : conditional.flatDoc(), indent, remaining, mode);
            case Doc.Label label -> fits(label.doc(), indent, remaining, mode);
        };
    }

    private int flatWidth(Doc doc, int indent, Mode mode) {
        return switch (doc) {
            case Doc.Text text -> text.value().length();
            case Doc.Concat concat -> {
                int width = 0;
                for (Doc child : concat.docs()) {
                    int childWidth = flatWidth(child, indent, mode);
                    if (childWidth < 0) {
                        yield -1;
                    }
                    width += childWidth;
                }
                yield width;
            }
            case Doc.Line _ -> 1;
            case Doc.SoftLine _ -> 0;
            case Doc.HardLine _ -> -1;
            case Doc.Indent indented -> flatWidth(indented.doc(), indent + 1, mode);
            case Doc.Group group -> flatWidth(group.doc(), indent, Mode.FLAT);
            case Doc.IfBreak conditional -> flatWidth(mode == Mode.BREAK ? conditional.breakDoc() : conditional.flatDoc(), indent, mode);
            case Doc.Label label -> flatWidth(label.doc(), indent, mode);
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
