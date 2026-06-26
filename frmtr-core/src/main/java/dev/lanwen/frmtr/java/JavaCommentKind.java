package dev.lanwen.frmtr.java;

/**
 * Classifies the JavaParser comment node shapes that the Java formatter treats as printable trivia.
 *
 * <p>This enum owns only the parser-level comment kind vocabulary. The boundary exists so comment helpers and printers
 * can ask for line, block, or Javadoc trivia without repeating JavaParser subclass checks. It intentionally does not
 * decide where a comment is attached, how it should be spaced, or whether a syntax-specific formatter rule should keep
 * it inline or on its own line.
 */
enum JavaCommentKind {
    /**
     * A {@code //} line comment that normally owns the rest of its source line and may force a hard line in callers.
     */
    LINE,

    /**
     * A {@code /* ... *&#47;} block comment that may appear inline or on separate lines depending on caller layout.
     */
    BLOCK,

    /**
     * A {@code /** ... *&#47;} documentation comment whose raw token spelling may need to be preserved for single-line
     * and multi-line Javadoc output.
     */
    JAVADOC,

    /**
     * A JEP 467 Markdown documentation comment: a contiguous run of {@code ///} lines that JavaParser exposes as a single
     * multi-line node. It is a documentation comment like {@link #JAVADOC} for placement purposes, but its lines are
     * rendered from the {@code ///} line family rather than reflowed as a {@code /* ... *&#47;} block, so the formatter
     * gives it its own kind to drive that rendering choice.
     */
    MARKDOWN,

    /**
     * A comment implementation outside JavaParser's standard Java comment subclasses; callers should preserve it through
     * the generic comment rendering path rather than assigning line, block, or Javadoc-specific layout meaning.
     */
    UNKNOWN,
}
