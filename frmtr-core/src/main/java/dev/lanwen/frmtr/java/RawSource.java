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
     */
    String normalizeWhitespace(String text) {
        String stripped = text.strip();
        if (stripped.isEmpty()) {
            return "";
        }
        String normalized = WHITESPACE.matcher(stripped).replaceAll(" ")
                .replaceAll("(?<![=!<>])\\s*=\\s*(?![=])", " = ");
        return WHITESPACE.matcher(normalized).replaceAll(" ");
    }
}
