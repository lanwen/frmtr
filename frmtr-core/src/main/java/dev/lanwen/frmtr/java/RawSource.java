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
     * <p>The assignment-{@code =} spacing rule is applied only to the text <em>between</em> string, character, and
     * text-block literals; each literal span is copied through verbatim. A raw regex over the whole text cannot tell an
     * assignment {@code =} from an {@code =} byte that happens to live inside {@code "useSSL="} or {@code '='}, and
     * rewriting the latter to {@code " = "} would silently change the literal's value. Literal boundaries are tracked by
     * a small hand scanner rather than a regex because a text block ({@code """..."""}) cannot be matched reliably with
     * the same alternation that recognizes plain strings.
     *
     * <p>The {@code =} spacing regex also guards against the trailing {@code =} of a compound-assignment operator
     * ({@code ^=}, {@code |=}, {@code &=}, {@code +=}, {@code -=}, {@code *=}, {@code /=}, {@code %=}, {@code <<=},
     * {@code >>=}, {@code >>>=}) as well as the equality/relational operators ({@code ==}, {@code !=}, {@code <=},
     * {@code >=}). Splitting a compound operator into {@code "^ ="} produces source that JavaParser tolerates but
     * {@code javac} rejects, so the negative lookbehind excludes every operator character that can precede a single
     * {@code =}.
     */
    String normalizeWhitespace(String text) {
        String stripped = text.strip();
        if (stripped.isEmpty()) {
            return "";
        }
        String collapsed = WHITESPACE.matcher(stripped).replaceAll(" ");
        String normalized = normalizeEqualsOutsideLiterals(collapsed);
        return WHITESPACE.matcher(normalized).replaceAll(" ");
    }

    /**
     * Applies the assignment-{@code =} spacing normalization to non-literal regions only, emitting string, character,
     * and text-block literal spans verbatim so an {@code =} inside a literal is never reinterpreted as an assignment.
     */
    private String normalizeEqualsOutsideLiterals(String text) {
        StringBuilder result = new StringBuilder(text.length());
        StringBuilder outside = new StringBuilder();
        int index = 0;
        while (index < text.length()) {
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

    private void flushOutside(StringBuilder result, StringBuilder outside) {
        if (outside.length() == 0) {
            return;
        }
        result.append(ASSIGN_EQUALS.matcher(outside).replaceAll(" = "));
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
