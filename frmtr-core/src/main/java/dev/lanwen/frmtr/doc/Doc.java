package dev.lanwen.frmtr.doc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public sealed interface Doc
    permits
        Doc.Concat,
        Doc.Group,
        Doc.HardLine,
        Doc.IfBreak,
        Doc.Indent,
        Doc.Label,
        Doc.Line,
        Doc.LineSuffix,
        Doc.SoftLine,
        Doc.Text {
    Doc EMPTY = new Text("");

    Doc LINE = new Line();

    Doc SOFT_LINE = new SoftLine();

    Doc HARD_LINE = new HardLine();

    static Doc text(String value) {
        return value.isEmpty() ? EMPTY : new Text(value);
    }

    static Doc concat(Doc... docs) {
        return concat(Arrays.asList(docs));
    }

    static Doc concat(List<Doc> docs) {
        List<Doc> flat = new ArrayList<>();
        flattenConcat(docs, flat);
        if (flat.isEmpty()) {
            return EMPTY;
        }
        if (flat.size() == 1) {
            return flat.getFirst();
        }
        return new Concat(List.copyOf(flat));
    }

    static Doc join(Doc separator, List<Doc> docs) {
        if (docs.isEmpty()) {
            return EMPTY;
        }
        List<Doc> joined = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            if (i > 0) {
                joined.add(separator);
            }
            joined.add(docs.get(i));
        }
        return concat(joined);
    }

    /**
     * Joins documents with the formatter's standard comma-plus-line separator.
     */
    static Doc joinComma(List<Doc> docs) {
        return join(concat(text(","), LINE), docs);
    }

    static Doc group(Doc doc) {
        return new Group(doc);
    }

    static Doc indent(Doc doc) {
        return new Indent(doc);
    }

    static Doc ifBreak(Doc breakDoc, Doc flatDoc) {
        return new IfBreak(breakDoc, flatDoc);
    }

    /**
     * Emits {@code doc} only when the surrounding group renders in break mode.
     */
    static Doc breakOnly(Doc doc) {
        return ifBreak(doc, EMPTY);
    }

    /**
     * Emits {@code doc} only when the surrounding group renders flat.
     */
    static Doc flatOnly(Doc doc) {
        return ifBreak(EMPTY, doc);
    }

    /**
     * Builds the grouped soft-line delimiter envelope used by list-like documents.
     */
    static Doc delimited(String open, String close, Doc content) {
        return group(concat(text(open), group(indent(concat(SOFT_LINE, content))), SOFT_LINE, text(close)));
    }

    static Doc label(String label, Doc doc) {
        return new Label(label, doc);
    }

    /**
     * Defers {@code content} to the end of the current line: it renders nothing at this position and flushes just before
     * the next line break (or at end of document). Used for trailing comments so the code preceding them is laid out and
     * width-measured as if the comment were absent — the comment can never push that code over the line width or change
     * which separator prints first.
     *
     * <p>Content is single-line only in this version; a {@link HardLine} inside it is rejected at render time.
     */
    static Doc lineSuffix(Doc content) {
        return new LineSuffix(content);
    }

    private static void flattenConcat(List<Doc> docs, List<Doc> out) {
        for (Doc doc : docs) {
            if (doc == EMPTY) {
                continue;
            }
            if (doc instanceof Concat(List<Doc> concat)) {
                flattenConcat(concat, out);
            } else {
                out.add(doc);
            }
        }
    }

    record Text(String value) implements Doc {}

    record Concat(List<Doc> docs) implements Doc {}

    record Line() implements Doc {}

    record SoftLine() implements Doc {}

    record HardLine() implements Doc {}

    record Indent(Doc doc) implements Doc {}

    record Group(Doc doc) implements Doc {}

    record IfBreak(Doc breakDoc, Doc flatDoc) implements Doc {}

    record Label(String label, Doc doc) implements Doc {}

    record LineSuffix(Doc content) implements Doc {}
}
