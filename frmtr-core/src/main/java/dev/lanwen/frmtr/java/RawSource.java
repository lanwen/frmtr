package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import dev.lanwen.frmtr.FormatterOptions;

/**
 * Recovers and normalizes original source text for AST nodes when the formatter must preserve source-only syntax.
 *
 * <p>This helper owns raw token-range extraction and whitespace normalization for fallback paths such as pragma
 * raw-passes and compact source snippets. It intentionally does not decide when raw source should be used, how comments
 * are attached to docs, or which Java syntax constructs are printable by structured formatter logic.
 */
final class RawSource {

    private final FormatterOptions options;

    RawSource(FormatterOptions options) {
        this.options = options;
    }

    /**
     * Returns the node's original source text after removing the node's own attached comment.
     *
     * <p>Callers use this when they have already printed leading comments through the comment tracker and need the raw
     * node body without duplicating that same comment.
     */
    String rawWithoutOwnComment(Node node) {
        Node clone = node.clone();
        clone.removeComment();
        String raw = clone.getTokenRange()
                .map(Object::toString)
                .orElseGet(clone::toString)
                .strip();
        return options.preserveRawTrailingWhitespace() ? raw : stripTrailingHorizontalWhitespace(raw);
    }

    /**
     * Returns the node's original source text, honoring the formatter option that controls trailing horizontal
     * whitespace in raw-preservation paths.
     */
    String raw(Node node) {
        String raw = node.getTokenRange()
                .map(Object::toString)
                .orElseGet(node::toString)
                .strip();
        return options.preserveRawTrailingWhitespace() ? raw : stripTrailingHorizontalWhitespace(raw);
    }

    /**
     * Strips trailing horizontal whitespace from each line in a single pass, matching a {@code String.lines()} split
     * (terminators dropped, no trailing empty line for a final terminator) and rejoining with the platform separator.
     * Normalizing CR/CRLF/LF to the platform separator makes it deliberately not interchangeable with
     * {@link SourceText}'s separator-preserving strip.
     */
    String stripTrailingHorizontalWhitespace(String text) {
        int length = text.length();
        StringBuilder result = new StringBuilder(length);
        int index = 0;
        boolean firstLine = true;
        while (index < length) {
            int lineEnd = index;
            while (lineEnd < length && text.charAt(lineEnd) != '\n' && text.charAt(lineEnd) != '\r') {
                lineEnd++;
            }
            int contentEnd = lineEnd;
            while (contentEnd > index && Character.isWhitespace(text.charAt(contentEnd - 1))) {
                contentEnd--;
            }
            if (!firstLine) {
                result.append(System.lineSeparator());
            }
            result.append(text, index, contentEnd);
            firstLine = false;
            if (lineEnd == length) {
                break;
            }
            boolean crlf = text.charAt(lineEnd) == '\r' && lineEnd + 1 < length && text.charAt(lineEnd + 1) == '\n';
            index = crlf ? lineEnd + 2 : lineEnd + 1;
            // A terminator at end of text yields no trailing empty line, mirroring String.lines().
            if (index == length) {
                break;
            }
        }
        return result.toString();
    }

    /**
     * Collapses source text into a compact single-line form for syntax decisions that need source-equivalent text rather
     * than fully formatted docs.
     *
     * <p>Whitespace collapsing and the assignment-{@code =} spacing rule are applied only <em>between</em> string,
     * character, and text-block literals; each literal span is copied verbatim. Rewriting an {@code =} inside
     * {@code "useSSL="} or collapsing whitespace inside {@code "            "}/{@code "\t"} would silently change the
     * literal's value — data corruption. Literal boundaries are tracked by a hand scanner, not a regex, because a text
     * block ({@code """..."""}) cannot be matched reliably alongside plain strings.
     *
     * <p>The {@code =} spacing skips the trailing {@code =} of a compound-assignment operator ({@code ^=}, {@code +=},
     * {@code <<=}, …) and the equality/relational operators ({@code ==}, {@code !=}, {@code <=}, {@code >=}): splitting a
     * compound operator into {@code "^ ="} would produce source {@code javac} rejects, so a lone {@code =} is spaced only
     * when the next character is not {@code =} and the character it abuts is not itself an operator.
     */
    String normalizeWhitespace(String text) {
        String stripped = text.strip();
        if (stripped.isEmpty()) {
            return "";
        }
        return normalizeOutsideLiterals(stripped);
    }

    /**
     * Drops a single collapsed space that abuts a member/chain dot, so a compact snippet reconstructed from a
     * source-broken chain ({@code x\n.foo()} collapsed to {@code x .foo()}) emits with canonical dot spacing. Only spaces
     * that sit <em>between two non-whitespace characters and a following {@code .}</em> are removed; a line-leading space
     * (the continuation indent after a {@code //} line comment) is left intact. String, character, text-block, and comment
     * spans are copied verbatim, so a literal {@code " .html"} or a dot inside a comment is never rewritten.
     *
     * <p>This is an emit-only cleanup: callers keep measuring on {@link #normalizeWhitespace(String)} so a width gate's
     * verdict never shifts, and pass it the chosen flat text only when that verdict already committed to the flat shape.
     */
    String dropSpaceBeforeChainDot(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int index = 0;
        while (index < text.length()) {
            int blockCommentEnd = blockCommentSpanEnd(text, index);
            if (blockCommentEnd >= 0) {
                out.append(text, index, blockCommentEnd);
                index = blockCommentEnd;
                continue;
            }
            int lineCommentEnd = lineCommentSpanEnd(text, index);
            if (lineCommentEnd >= 0) {
                out.append(text, index, lineCommentEnd);
                index = lineCommentEnd;
                continue;
            }
            int literalEnd = literalSpanEnd(text, index);
            if (literalEnd >= 0) {
                out.append(text, index, literalEnd);
                index = literalEnd;
                continue;
            }
            char current = text.charAt(index);
            if (
                current == '.'
                && out.length() >= 2
                && out.charAt(out.length() - 1) == ' '
                && !isCollapsibleWhitespace(out.charAt(out.length() - 2))
            ) {
                out.setLength(out.length() - 1);
            }
            out.append(current);
            index++;
        }
        return out.toString();
    }

    /**
     * Applies whitespace collapsing and the assignment-{@code =} spacing normalization to non-literal regions only,
     * emitting string, character, and text-block literal spans verbatim so whitespace or an {@code =} inside a literal is
     * never rewritten.
     *
     * <p>A {@code //} line comment is copied verbatim and always followed by a newline: since a line comment runs to
     * end-of-line, collapsing that newline would pull the next token onto the comment line where {@code //} swallows it,
     * producing non-compiling text. Keeping the newline means width-gating callers see a multi-line (over-width) result
     * and fall back to a structured layout, and any direct emitter still produces source where the trailing token
     * survives.
     */
    private String normalizeOutsideLiterals(String text) {
        StringBuilder result = new StringBuilder(text.length());
        StringBuilder outside = new StringBuilder();
        int index = 0;
        while (index < text.length()) {
            int blockCommentEnd = blockCommentSpanEnd(text, index);
            if (blockCommentEnd >= 0) {
                // A block comment does not run to end-of-line, so it is collapsed with the surrounding text like any
                // other non-literal span. It is consumed as one span here only so a {@code //} sequence inside it (such
                // as a {@code http://} URL) is not mistaken for a line comment by the line-comment check below.
                outside.append(text, index, blockCommentEnd);
                index = blockCommentEnd;
                continue;
            }
            int lineCommentEnd = lineCommentSpanEnd(text, index);
            if (lineCommentEnd >= 0) {
                flushOutside(result, outside);
                result.append(text, index, lineCommentEnd);
                result.append('\n');
                index = lineCommentEnd;
                // Skip the newline run that already terminated the line comment in source; the explicit '\n' above
                // stands in for it so a following token cannot be collapsed onto the comment line.
                while (index < text.length() && (text.charAt(index) == '\n' || text.charAt(index) == '\r')) {
                    index++;
                }
                continue;
            }
            int literalEnd = literalSpanEnd(text, index);
            if (literalEnd < 0) {
                outside.append(text.charAt(index));
                index++;
                continue;
            }
            flushOutside(result, outside);
            result.append(text, index, literalEnd);
            index = literalEnd;
        }
        flushOutside(result, outside);
        return result.toString();
    }

    /**
     * Returns the exclusive end index of a {@code //} line comment that starts at {@code start} (the index of the first
     * character past the comment content, i.e. the newline or end of text), or {@code -1} when no line comment begins
     * there. The {@code //} prefix only opens a comment outside a string/character/text-block literal and outside a
     * block comment, which the caller guarantees by checking {@link #literalSpanEnd(String, int)} and
     * {@link #blockCommentSpanEnd(String, int)} spans before reaching this position.
     */
    private int lineCommentSpanEnd(String text, int start) {
        if (!text.startsWith("//", start)) {
            return -1;
        }
        int index = start + 2;
        while (index < text.length() && text.charAt(index) != '\n' && text.charAt(index) != '\r') {
            index++;
        }
        return index;
    }

    /**
     * Returns the exclusive end index of a {@code /}{@code * ... *}{@code /} block comment that starts at {@code start},
     * or {@code -1} when no block comment begins there. An unterminated block comment consumes the rest of the text. This
     * span exists only to keep a {@code //} sequence inside a block comment from opening a spurious line comment; the
     * caller still collapses the block comment's own whitespace like any other non-literal text.
     */
    private int blockCommentSpanEnd(String text, int start) {
        if (!text.startsWith("/*", start)) {
            return -1;
        }
        int closing = text.indexOf("*/", start + 2);
        return closing < 0 ? text.length() : closing + 2;
    }

    /** Flushes one accumulated non-literal region into {@code result}, normalized for the compact form. */
    private void flushOutside(StringBuilder result, StringBuilder outside) {
        if (outside.length() == 0) {
            return;
        }
        appendNormalizedRegion(result, outside);
        outside.setLength(0);
    }

    /** Single-region entry point mirroring {@link #flushOutside}, exposed so tests can pin it against the region rule. */
    String normalizeOutsideRegion(CharSequence region) {
        StringBuilder out = new StringBuilder(region.length());
        appendNormalizedRegion(out, region);
        return out.toString();
    }

    /**
     * Appends {@code region} to {@code out} in one left-to-right scan: whitespace runs collapse to a single space, a
     * standalone assignment {@code =} is spaced (a compound/relational operator's {@code =} is left alone), and the space
     * just inside an opener ({@code (} {@code [}) or closer ({@code )} {@code ]}) is dropped. The opener/closer trim keeps
     * a call's compact width from depending on whether a prior pass broke its arguments, so a near-boundary gate cannot
     * oscillate between {@code (arg)} and {@code ( arg )}. Operates on the region alone, so the seam with already-emitted
     * output is never trimmed.
     */
    private void appendNormalizedRegion(StringBuilder out, CharSequence region) {
        int length = region.length();
        boolean pendingSpace = false;
        char lastEmitted = 0;
        char lastSignificant = 0;
        for (int index = 0; index < length; index++) {
            char current = region.charAt(index);
            if (isCollapsibleWhitespace(current)) {
                pendingSpace = true;
                continue;
            }
            // A lone '=' is spaced unless the next char is '=' (==) or the char it abuts is an operator (+=, <=, …).
            boolean assignmentEquals = current == '='
                && !(index + 1 < length && region.charAt(index + 1) == '=')
                && (pendingSpace || lastSignificant == 0 || !isAssignmentBoundaryOperator(lastSignificant));
            if (assignmentEquals) {
                pendingSpace = true;
            }
            if (pendingSpace) {
                boolean justInsideOpener = lastEmitted == '(' || lastEmitted == '[';
                boolean justBeforeCloser = current == ')' || current == ']';
                if (!justInsideOpener && !justBeforeCloser) {
                    out.append(' ');
                }
                pendingSpace = false;
            }
            out.append(current);
            lastEmitted = current;
            lastSignificant = current;
            if (assignmentEquals) {
                pendingSpace = true;
            }
        }
        // A trailing collapsed space survives unless it sits just inside an opener.
        if (pendingSpace && lastEmitted != '(' && lastEmitted != '[') {
            out.append(' ');
        }
    }

    /** Matches Java regex {@code \s} — the six ASCII whitespace characters the collapse recognizes. */
    private static boolean isCollapsibleWhitespace(char c) {
        return switch (c) {
            case ' ', '\t', '\n', '\u000B', '\f', '\r' -> true;
            default -> false;
        };
    }

    /** Operator characters that, abutting a lone {@code =}, make it part of a compound/relational operator. */
    private static boolean isAssignmentBoundaryOperator(char c) {
        return switch (c) {
            case '-', '+', '*', '/', '%', '^', '&', '|', '=', '!', '<', '>' -> true;
            default -> false;
        };
    }

    /**
     * Returns the exclusive end index of a string, character, or text-block literal that starts at {@code start}, or
     * {@code -1} when no literal begins there. Text blocks are checked before plain strings so an opening {@code """} is
     * not mistaken for an empty string followed by another quote. Unterminated literals consume the rest of the text,
     * which keeps the scanner from misreading a stray {@code =} after a broken literal as an assignment.
     */
    private int literalSpanEnd(String text, int start) {
        char delimiter = text.charAt(start);
        if (delimiter == '"' && text.startsWith("\"\"\"", start)) {
            return delimitedSpanEnd(text, start + 3, "\"\"\"");
        }
        if (delimiter == '"') {
            return delimitedSpanEnd(text, start + 1, "\"");
        }
        if (delimiter == '\'') {
            return delimitedSpanEnd(text, start + 1, "'");
        }
        return -1;
    }

    private int delimitedSpanEnd(String text, int contentStart, String closing) {
        int index = contentStart;
        while (index < text.length()) {
            char current = text.charAt(index);
            if (current == '\\') {
                index += 2;
                continue;
            }
            if (text.startsWith(closing, index)) {
                return index + closing.length();
            }
            index++;
        }
        return text.length();
    }
}
