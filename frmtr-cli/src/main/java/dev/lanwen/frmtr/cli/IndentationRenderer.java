package dev.lanwen.frmtr.cli;

/**
 * Visualizes the leading indentation of already-formatted source by replacing each leading whitespace character with a
 * middle-dot ({@code ·}, U+00B7). This is the CLI-only rendering behind {@code --render-indentation}: a display aid that
 * makes it obvious how deeply each line is indented and why, without shifting any column.
 *
 * <p>The boundary is deliberately at the terminal-presentation layer, not the formatter pipeline. The transform runs on
 * the final rendered text {@link Main} is about to print, so it owns no formatting policy and cannot change which lines
 * wrap or how far they indent — the formatter has already decided that. It only substitutes glyphs for the leading
 * whitespace run of each line, preserving the byte-for-byte structure everywhere else.
 *
 * <p>Decisions left to the caller: <em>when</em> to apply this (which run modes expose the flag) and how the result is
 * emitted. Callers should only feed it text destined for a human-readable stream (stdout print / {@code --stdin}
 * output), never file writes, because dotted indentation is not valid Java.
 */
final class IndentationRenderer {

    /** The glyph substituted for each leading whitespace character. U+00B7 MIDDLE DOT. */
    static final char INDENTATION_DOT = '·';

    private IndentationRenderer() {}

    /**
     * Returns {@code text} with the leading whitespace of every line replaced by {@link #INDENTATION_DOT}, one dot per
     * whitespace character so column positions are preserved exactly.
     *
     * <p>"Leading whitespace" is the maximal run of spaces and tab characters at the start of a line; the run stops at
     * the first non-whitespace character, so whitespace inside the line (including inside string literals) is never
     * touched. Line endings ({@code \n}) are preserved verbatim, including a blank final line with no terminator.
     * Because the substitution is one glyph per character, a line that is entirely whitespace (a blank indented line) is
     * rendered as all dots — this is intentional: it shows indentation the formatter emitted even on otherwise-empty
     * lines.
     */
    static String render(String text) {
        StringBuilder rendered = new StringBuilder(text.length());
        int cursor = 0;
        int length = text.length();
        while (cursor < length) {
            int lineEnd = text.indexOf('\n', cursor);
            int contentEnd = lineEnd >= 0 ? lineEnd : length;
            int indentEnd = cursor;
            while (indentEnd < contentEnd && isLeadingWhitespace(text.charAt(indentEnd))) {
                indentEnd++;
            }
            rendered.append(String.valueOf(INDENTATION_DOT).repeat(indentEnd - cursor));
            rendered.append(text, indentEnd, contentEnd);
            if (lineEnd >= 0) {
                rendered.append('\n');
            }
            cursor = lineEnd >= 0 ? lineEnd + 1 : length;
        }
        return rendered.toString();
    }

    private static boolean isLeadingWhitespace(char character) {
        return character == ' ' || character == '\t';
    }
}
