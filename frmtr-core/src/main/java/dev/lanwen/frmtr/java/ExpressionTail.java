package dev.lanwen.frmtr.java;

import dev.lanwen.frmtr.doc.Doc;

/**
 * Represents punctuation that belongs to an expression's syntactic owner.
 *
 * <p>Line comments make this more than a string append: a method call with a trailing {@code //} comment must print the
 * terminator or separator before that comment. Keeping the tail explicit gives expression callers one shared spelling
 * for semicolon and comma ownership without encoding those choices as ad-hoc suffix strings.
 */
record ExpressionTail(String text) {
    static final ExpressionTail EMPTY = new ExpressionTail("");

    static final ExpressionTail COMMA = new ExpressionTail(",");

    static final ExpressionTail SEMICOLON = new ExpressionTail(";");

    static ExpressionTail of(String text) {
        return text.isEmpty() ? EMPTY : new ExpressionTail(text);
    }

    boolean isEmpty() {
        return text.isEmpty();
    }

    Doc doc() {
        return Doc.text(text);
    }

    Doc appendTo(Doc doc) {
        return isEmpty() ? doc : Doc.concat(doc, doc());
    }

    @Override
    public String toString() {
        return text;
    }
}
