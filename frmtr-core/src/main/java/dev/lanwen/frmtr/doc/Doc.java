package dev.lanwen.frmtr.doc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public sealed interface Doc
        permits Doc.Concat, Doc.Group, Doc.HardLine, Doc.IfBreak, Doc.Indent, Doc.Label, Doc.Line, Doc.SoftLine, Doc.Text {
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

    static Doc group(Doc doc) {
        return new Group(doc);
    }

    static Doc indent(Doc doc) {
        return new Indent(doc);
    }

    static Doc ifBreak(Doc breakDoc, Doc flatDoc) {
        return new IfBreak(breakDoc, flatDoc);
    }

    static Doc label(String label, Doc doc) {
        return new Label(label, doc);
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
}
