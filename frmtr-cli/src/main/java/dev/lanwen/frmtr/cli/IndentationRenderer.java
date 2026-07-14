package dev.lanwen.frmtr.cli;

import dev.lanwen.frmtr.IndentedSource;
import java.util.List;

/**
 * Visualizes the leading indentation of already-formatted source so a reader can see <em>why</em> each line is indented,
 * distinguishing the two kinds of indentation that plain text renders identically: a <strong>block</strong> indent
 * (opening a new brace-delimited body) and a <strong>continuation</strong> indent (a wrap aligned to a logical parent —
 * a broken method-chain selector, an assignment or return continuation, wrapped arguments). This is the CLI-only
 * rendering behind {@code --render-indentation}: a display aid that never shifts a column.
 *
 * <p>Block indentation renders only the columns it <em>adds</em> over the previous line, as middle-dots
 * ({@code ·}, U+00B7); the indentation it shares with the line above stays blank. Continuation indentation renders as a
 * vertical ellipsis ({@code ⋮}, U+22EE) at the enclosing statement's indent followed by dots for the rest of its
 * continuation offset, on every continuation line — the {@code ⋮} says "this line is aligned with the construct above,"
 * which is exactly what a block indent is not.
 *
 * <p>The boundary is deliberately at the terminal-presentation layer, not the formatter pipeline. The transform runs on
 * the final rendered text {@link Main} is about to print, so it owns no formatting policy and cannot change which lines
 * wrap or how far they indent — the formatter has already decided that. It only substitutes glyphs for the leading
 * whitespace run of each line, preserving the byte-for-byte structure everywhere else.
 *
 * <h2>Block vs continuation: a heuristic over a structural signal</h2>
 *
 * <p>Telling a block indent from a continuation indent is <em>not</em> recoverable from the finished text alone: a block
 * level and a continuation offset are both just leading whitespace. This renderer therefore reads the per-line
 * structural signal on {@link IndentedSource} — the true indent <em>level</em> the formatter emitted at each newline,
 * which is tab-width-independent and which also flags text-block interior lines (whose leading whitespace is literal
 * program data, left untouched here save for the uniform-dot fallback).
 *
 * <p>On top of that structural level signal it applies a <strong>heuristic</strong> for block-vs-continuation, because
 * the level alone does not name the construct: a line is treated as a <em>continuation</em> when its indent level rises
 * two or more levels above the current block baseline (the double-indent shape every wrap/continuation construct in the
 * formatter uses), and as a <em>block</em> otherwise. The one refinement the level jump cannot make on its own is a real
 * block opened <em>inside</em> a continuation (a block-lambda body wrapped under a broken chain): its statements are
 * blocks, not continuations, even though they sit far to the right. That case is caught by tracking an open brace on the
 * previous line — the classic brace-based block signal — which resets the block baseline. The heuristic is approximate
 * in two documented ways: a continuation that happens to indent exactly one level (a single-level wrapped argument list)
 * reads as a block, and a brace that is not a block opener is not distinguished. Both match the trade-off the feature
 * accepts; a fully exact classifier would require the layout printers to label their continuation indents, which this
 * presentation layer intentionally does not reach into.
 *
 * <p>Decisions left to the caller: <em>when</em> to apply this (which run modes expose the flag) and how the result is
 * emitted. Callers should only feed it text destined for a human-readable stream (stdout print / {@code --stdin}
 * output), never file writes, because dotted indentation is not valid Java.
 */
final class IndentationRenderer {

    /** The glyph substituted for each added-block-indent whitespace column. U+00B7 MIDDLE DOT. */
    static final char INDENTATION_DOT = '·';

    /** The glyph that marks the start of a continuation indent's offset. U+22EE VERTICAL ELLIPSIS. */
    static final char CONTINUATION_MARKER = '⋮';

    private IndentationRenderer() {}

    /**
     * Returns {@code source.text()} with each line's leading whitespace replaced by the block/continuation
     * visualization, using {@code source.lines()} for the structural indent level of each line.
     *
     * <p>The substitution is one glyph per leading-whitespace character so positions are preserved exactly. A structural
     * block line renders its shared prefix blank and its added characters as dots; a structural continuation line renders
     * the enclosing statement's characters blank, then {@code ⋮}, then dots for the rest of the offset. A dedent or
     * same-depth block line adds nothing and so renders as plain spaces. Block-vs-continuation is decided from the indent
     * <em>level</em>, but the glyphs are laid over the actual leading-whitespace characters, so a line whose leading
     * whitespace differs from {@code level} indent units (a block comment's {@code *}-alignment space) still emits one
     * glyph per character and never shifts a column. Non-structural lines (text-block interiors) and any line for which
     * no structural signal is available fall back to the uniform-dot rendering (one dot per leading whitespace
     * character), which leaves literal indentation legible without misclassifying it.
     */
    static String render(IndentedSource source) {
        String text = source.text();
        List<IndentedSource.Line> lines = source.lines();
        StringBuilder rendered = new StringBuilder(text.length());
        int cursor = 0;
        int length = text.length();
        int lineIndex = 0;
        // Block-vs-continuation is decided from the indent LEVEL (the reliable structural signal), but the glyphs are
        // laid over the ACTUAL leading-whitespace characters so the substitution never adds or drops a character — one
        // glyph per leading whitespace char. previousStructuralIndentChars is the
        // block-delta baseline (dots measure the delta vs the previous line as drawn); blockBaseline{Level,Chars} is the
        // enclosing statement a continuation aligns to; previousStructuralOpenedBlock is whether the line above ended
        // with an open brace, which opens a real block and resets the baseline even deep inside a continuation.
        int previousStructuralIndentChars = 0;
        int blockBaselineLevel = 0;
        int blockBaselineChars = 0;
        boolean previousStructuralOpenedBlock = false;
        boolean seenStructuralLine = false;
        while (cursor < length) {
            int lineEnd = text.indexOf('\n', cursor);
            int contentEnd = lineEnd >= 0 ? lineEnd : length;
            int indentEnd = cursor;
            while (indentEnd < contentEnd && isLeadingWhitespace(text.charAt(indentEnd))) {
                indentEnd++;
            }
            int indentChars = indentEnd - cursor;
            boolean blankLine = indentEnd == contentEnd;
            IndentedSource.Line line = lineIndex < lines.size() ? lines.get(lineIndex) : null;

            if (line == null || !line.structural() || blankLine) {
                // Text-block interior, a line with no structural signal, or a whitespace-only line: keep the uniform
                // one-dot-per-character rendering. These never update the block baseline.
                rendered.append(String.valueOf(INDENTATION_DOT).repeat(indentChars));
            } else {
                // The first structural line opens the document's structure — there is nothing above it to continue, so
                // it is always a block. Otherwise a line is a continuation when it rises two or more indent levels above
                // the current block baseline, unless the line above it just opened a real block (trailing "{"), which
                // resets the baseline even deep inside a continuation (a block-lambda body under a broken chain).
                boolean continuation = seenStructuralLine
                    && !previousStructuralOpenedBlock
                    && line.level() >= blockBaselineLevel + 2;
                if (continuation) {
                    appendContinuation(rendered, blockBaselineChars, indentChars);
                } else {
                    appendBlockDelta(rendered, previousStructuralIndentChars, indentChars);
                    blockBaselineLevel = line.level();
                    blockBaselineChars = indentChars;
                }
                previousStructuralIndentChars = indentChars;
                previousStructuralOpenedBlock = text.charAt(contentEnd - 1) == '{';
                seenStructuralLine = true;
            }

            rendered.append(text, indentEnd, contentEnd);
            if (lineEnd >= 0) {
                rendered.append('\n');
            }
            cursor = lineEnd >= 0 ? lineEnd + 1 : length;
            lineIndex++;
        }
        return rendered.toString();
    }

    /**
     * Renders a block indent over {@code indentChars} leading-whitespace characters: the characters shared with the
     * previous line stay blank and only the ones this line adds over it become dots. A dedent or same-depth line adds
     * nothing, so it renders as plain spaces. The total emitted always equals {@code indentChars}, so no character is
     * ever added or dropped.
     */
    private static void appendBlockDelta(StringBuilder rendered, int previousChars, int indentChars) {
        int shared = Math.min(indentChars, previousChars);
        rendered.append(" ".repeat(shared));
        rendered.append(String.valueOf(INDENTATION_DOT).repeat(indentChars - shared));
    }

    /**
     * Renders a continuation indent over {@code indentChars} leading-whitespace characters: the enclosing statement's
     * characters (the block baseline) stay blank, then a single {@code ⋮} marks the start of the continuation, then dots
     * fill the rest of the offset. The marker plus dots span the whole offset beyond the enclosing statement, so the
     * total emitted equals {@code indentChars} and every line of a continuation run is marked the same way regardless of
     * the line above it. The baseline is clamped below {@code indentChars} so there is always room for the marker.
     */
    private static void appendContinuation(StringBuilder rendered, int baselineChars, int indentChars) {
        int blank = Math.min(baselineChars, indentChars - 1);
        rendered.append(" ".repeat(blank));
        rendered.append(CONTINUATION_MARKER);
        rendered.append(String.valueOf(INDENTATION_DOT).repeat(indentChars - blank - 1));
    }

    private static boolean isLeadingWhitespace(char character) {
        return character == ' ' || character == '\t';
    }
}
