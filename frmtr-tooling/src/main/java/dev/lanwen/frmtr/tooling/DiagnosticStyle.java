package dev.lanwen.frmtr.tooling;

/**
 * Identifies the semantic role of a diagnostic text span so callers can render
 * terminal styling without parsing diagnostic glyphs or source text.
 */
public enum DiagnosticStyle {
    /**
     * Marks outline, connector, and indentation glyphs that frame diagnostic content without being the failing source.
     */
    BORDER_GUTTER,

    /**
     * Marks human-readable failure text that explains the formatter or parser problem.
     */
    ERROR_TEXT,

    /**
     * Marks source line numbers shown beside diagnostic context lines.
     */
    LINE_NUMBER,

    /**
     * Marks verbatim source code text included to show the area around a formatter failure.
     */
    SOURCE_TEXT,

    /**
     * Marks caret and connector glyphs that point from diagnostic context to the exact problem location.
     */
    POINTER,

    /**
     * Marks elision rows that indicate omitted source lines or omitted diagnostic blocks.
     */
    GAP,
}
