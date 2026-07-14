package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import dev.lanwen.frmtr.FormatterOptions;
import java.util.regex.Pattern;

/**
 * Recovers and normalizes original source text for AST nodes when the formatter must preserve source-only syntax.
 *
 * <p>This helper owns raw token-range extraction and whitespace normalization for fallback paths such as pragma
 * raw-passes and compact source snippets. It intentionally does not decide when raw source should be used, how comments
 * are attached to docs, or which Java syntax constructs are printable by structured formatter logic.
 */
final class RawSource {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private static final Pattern ASSIGN_EQUALS = Pattern.compile("(?<![-+*/%^&|=!<>])\\s*=\\s*(?![=])");

    // Drops the space whitespace-collapsing leaves just inside a delimiter pair when the author broke the content across
    // lines: {@code foo(\n    arg\n)} collapses to {@code foo( arg )}, but the canonical compact form is {@code foo(arg)}.
    // Without this, a call's compact width depended on whether a prior pass had broken its args, so a near-boundary gate
    // could flip between {@code (arg)} and {@code ( arg )} and never converge. Anchoring on the opener/closer keeps it a
    // pure interior-spacing fix that never touches operator spacing ({@code 42/42} preserved) and never runs on literal
    // content (string/character/text-block spans are emitted verbatim and never reach this non-literal buffer).
    private static final Pattern SPACE_AFTER_OPENER = Pattern.compile("([(\\[]) ");

    private static final Pattern SPACE_BEFORE_CLOSER = Pattern.compile(" ([)\\]])");

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
        String raw = clone.getTokenRange().map(Object::toString).orElseGet(clone::toString).strip();
        return options.preserveRawTrailingWhitespace() ? raw : stripTrailingHorizontalWhitespace(raw);
    }

    /**
     * Returns the node's original source text, honoring the formatter option that controls trailing horizontal
     * whitespace in raw-preservation paths.
     */
    String raw(Node node) {
        String raw = node.getTokenRange().map(Object::toString).orElseGet(node::toString).strip();
        return options.preserveRawTrailingWhitespace() ? raw : stripTrailingHorizontalWhitespace(raw);
    }

    /**
     * Removes trailing horizontal whitespace line-by-line without otherwise changing line structure.
     */
    String stripTrailingHorizontalWhitespace(String text) {
        return text.lines()
                .map(line -> line.stripTrailing())
                .reduce((left, right) -> left + System.lineSeparator() + right)
                .orElse("");
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
     * <p>The {@code =} spacing regex also excludes the trailing {@code =} of a compound-assignment operator ({@code ^=},
     * {@code +=}, {@code <<=}, …) and the equality/relational operators ({@code ==}, {@code !=}, {@code <=}, {@code >=}):
     * splitting a compound operator into {@code "^ ="} produces source {@code javac} rejects, so the negative lookbehind
     * excludes every operator character that can precede a single {@code =}.
     */
    String normalizeWhitespace(String text) {
        String stripped = text.strip();
        if (stripped.isEmpty()) {
            return "";
        }
        return normalizeOutsideLiterals(stripped);
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
                // A block comment does not run to end-of-line, so it keeps the previous behavior of being collapsed with
                // the surrounding text. It is consumed as one span here only so a {@code //} sequence inside it (such as
                // a {@code http://} URL) is not mistaken for a line comment by the line-comment check below.
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

    /**
     * Normalizes one non-literal region: collapses whitespace runs to single spaces, applies the {@code =} spacing rule,
     * then re-collapses because inserting {@code " = "} can produce a space adjacent to an existing one.
     */
    private void flushOutside(StringBuilder result, StringBuilder outside) {
        if (outside.length() == 0) {
            return;
        }
        String collapsed = WHITESPACE.matcher(outside).replaceAll(" ");
        String normalized = ASSIGN_EQUALS.matcher(collapsed).replaceAll(" = ");
        String reCollapsed = WHITESPACE.matcher(normalized).replaceAll(" ");
        String afterOpener = SPACE_AFTER_OPENER.matcher(reCollapsed).replaceAll("$1");
        result.append(SPACE_BEFORE_CLOSER.matcher(afterOpener).replaceAll("$1"));
        outside.setLength(0);
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
