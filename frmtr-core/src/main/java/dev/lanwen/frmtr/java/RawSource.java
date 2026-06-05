package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import dev.lanwen.frmtr.FormatterOptions;
import java.util.regex.Pattern;

final class RawSource {
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final FormatterOptions options;

    RawSource(FormatterOptions options) {
        this.options = options;
    }

    String rawWithoutOwnComment(Node node) {
        Node clone = node.clone();
        clone.removeComment();
        String raw = clone.getTokenRange().map(Object::toString).orElseGet(clone::toString).strip();
        return options.preserveRawTrailingWhitespace() ? raw : stripTrailingHorizontalWhitespace(raw);
    }

    String raw(Node node) {
        String raw = node.getTokenRange().map(Object::toString).orElseGet(node::toString).strip();
        return options.preserveRawTrailingWhitespace() ? raw : stripTrailingHorizontalWhitespace(raw);
    }

    String stripTrailingHorizontalWhitespace(String text) {
        return text.lines()
                .map(line -> line.stripTrailing())
                .reduce((left, right) -> left + System.lineSeparator() + right)
                .orElse("");
    }

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
