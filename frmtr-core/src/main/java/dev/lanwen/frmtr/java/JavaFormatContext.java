package dev.lanwen.frmtr.java;

import dev.lanwen.frmtr.FormatterOptions;

/**
 * Carries formatter-wide state and source helpers that every Java printer should share for one formatting run.
 *
 * <p>The context owns dependencies whose identity matters across helper boundaries: options, printed-comment tracking,
 * formatter pragma state, raw source recovery, compact source text, and source-position comment placement. The boundary
 * exists so {@link JavaPrinter} can remain the composition root without threading the same shared collaborators through
 * every helper constructor separately.
 *
 * <p>Helpers should still keep syntax-specific callbacks and layout decisions in their own constructors. This context
 * is not a service locator for printers, dispatchers, or renderer policy; it only provides the common stateful
 * formatter dependencies for the current Java source file.
 */
final class JavaFormatContext {
    final FormatterOptions options;
    final JavaFormatter.CommentTracker comments;
    final FormatterPragmas formatterPragmas;
    final RawSource rawSource;
    final CompactSourceText compactSource;
    final CommentPlacement commentPlacement;

    JavaFormatContext(FormatterOptions options) {
        this.options = options;
        this.comments = new JavaFormatter.CommentTracker();
        this.formatterPragmas = new FormatterPragmas();
        this.rawSource = new RawSource(options);
        this.compactSource = new CompactSourceText(rawSource);
        this.commentPlacement = new CommentPlacement(comments);
    }
}
