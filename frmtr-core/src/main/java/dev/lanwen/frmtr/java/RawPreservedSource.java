package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import dev.lanwen.frmtr.doc.Doc;

/**
 * Emits raw-preserved source text while atomically accounting for the comments preserved by that text.
 *
 * <p>This helper owns the boundary between raw/source-derived output and debug comment accounting. The boundary exists
 * so formatter fallbacks cannot emit {@link Doc.Text} from JavaParser token text without also recording that any
 * contained comments reached the output through the raw path.
 *
 * <p>Callers still decide when raw preservation is the right fallback, whether a node's own attached comment has
 * already been emitted separately, and how any already-computed raw text should be transformed before it is handed to
 * this helper.
 */
final class RawPreservedSource {

    private final RawSource rawSource;

    private final CommentTracker comments;

    RawPreservedSource(RawSource rawSource, CommentTracker comments) {
        this.rawSource = rawSource;
        this.comments = comments;
    }

    /**
     * Emits the node's raw source text and accounts for every contained comment as raw-preserved output.
     */
    Doc raw(Node node) {
        return raw(node, rawSource.raw(node));
    }

    /**
     * Emits already-computed source-derived text and accounts for every contained comment as raw-preserved output.
     */
    Doc raw(Node node, String text) {
        comments.accountRaw(node);
        return Doc.text(text);
    }

    /**
     * Emits raw source text after excluding the node's own attached comment from the recovered source.
     *
     * <p>Use this when the caller has already claimed and emitted the own comment through structured comment rendering.
     * Nested and orphan comments still remain part of the raw-preserved output and are accounted here.
     */
    Doc rawWithoutOwnComment(Node node) {
        return rawWithoutOwnComment(node, rawSource.rawWithoutOwnComment(node));
    }

    /**
     * Emits already-computed source-derived text while treating the node's own comment as separately handled.
     *
     * <p>Use this for raw fallback printers that transform source text before returning it, but still rely on raw
     * preservation for comments inside the node body.
     */
    Doc rawWithoutOwnComment(Node node, String text) {
        comments.accountRawWithoutOwnComment(node);
        return Doc.text(text);
    }
}
