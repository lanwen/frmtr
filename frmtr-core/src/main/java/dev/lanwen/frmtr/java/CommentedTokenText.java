package dev.lanwen.frmtr.java;

import java.util.ArrayList;
import java.util.List;

/**
 * Tokenizes Java source fragments that contain comments interleaved with punctuation-sensitive syntax.
 *
 * <p>This helper exists for formatter paths that still need to reshape partially commented declarations, such as module
 * and interface headers, without losing comment placement. It intentionally does not parse Java, choose line breaks for
 * general AST nodes, or decide whether commented source should be formatted structurally or preserved as raw text.
 */
final class CommentedTokenText {

    private static final String COMMENTED_TOKEN_PUNCTUATION = "{};,().";

    private CommentedTokenText() {}

    /**
     * Splits one source line into lightweight tokens while keeping block and line comments as indivisible tokens.
     */
    static List<String> tokens(String line) {
        List<String> tokens = new ArrayList<>();
        int cursor = 0;
        while (cursor < line.length()) {
            char current = line.charAt(cursor);
            if (Character.isWhitespace(current)) {
                cursor++;
                continue;
            }
            if (cursor + 1 < line.length() && line.startsWith("/*", cursor)) {
                int end = line.indexOf("*/", cursor + 2);
                if (end < 0) {
                    tokens.add(line.substring(cursor));
                    break;
                }
                tokens.add(line.substring(cursor, end + 2));
                cursor = end + 2;
                continue;
            }
            if (cursor + 1 < line.length() && line.startsWith("//", cursor)) {
                tokens.add(line.substring(cursor).stripTrailing());
                break;
            }
            if (line.startsWith("...", cursor)) {
                tokens.add("...");
                cursor += 3;
                continue;
            }
            if (COMMENTED_TOKEN_PUNCTUATION.indexOf(current) >= 0) {
                tokens.add(String.valueOf(current));
                cursor++;
                continue;
            }
            int end = cursor + 1;
            while (
                end < line.length()
                && !Character.isWhitespace(line.charAt(end))
                && COMMENTED_TOKEN_PUNCTUATION.indexOf(line.charAt(end)) < 0
                && !line.startsWith("/*", end)
                && !line.startsWith("//", end)
            ) {
                end++;
            }
            tokens.add(line.substring(cursor, end));
            cursor = end;
        }
        return tokens;
    }

    /**
     * Reports whether a token came from a Java line or block comment.
     */
    static boolean isComment(String token) {
        return token.startsWith("/*") || token.startsWith("//");
    }

    /**
     * Appends already-rendered token groups with one separating space when needed.
     */
    static void appendSpaceSeparated(StringBuilder out, List<String> values) {
        for (String value : values) {
            if (!out.isEmpty() && out.charAt(out.length() - 1) != ' ') {
                out.append(' ');
            }
            out.append(value);
        }
    }

    /**
     * Rebuilds a qualified name from tokens, preserving comments around dots in the source-compatible order needed by
     * module and interface formatting.
     */
    static String qualifiedName(List<String> tokens, boolean moveCommentsAfterDotBeforeDot) {
        StringBuilder out = new StringBuilder();
        int cursor = 0;
        while (cursor < tokens.size()) {
            String token = tokens.get(cursor);
            if (token.equals(".")) {
                out.append('.');
                cursor++;
                continue;
            }
            if (isComment(token)) {
                if (!out.isEmpty() && out.charAt(out.length() - 1) != ' ' && out.charAt(out.length() - 1) != '.') {
                    out.append(' ');
                }
                out.append(token);
                if (
                    moveCommentsAfterDotBeforeDot
                    && cursor + 1 < tokens.size()
                    && tokens.get(cursor + 1).equals(".")
                ) {
                    cursor += 2;
                    while (cursor < tokens.size() && isComment(tokens.get(cursor))) {
                        out.append(' ').append(tokens.get(cursor++));
                    }
                    out.append('.');
                    continue;
                }
                if (cursor + 1 < tokens.size() && tokens.get(cursor + 1).equals(".")) {
                    cursor++;
                    continue;
                }
                out.append(' ');
                cursor++;
                continue;
            }
            if (!out.isEmpty() && out.charAt(out.length() - 1) != '.' && out.charAt(out.length() - 1) != ' ') {
                out.append(' ');
            }
            out.append(token);
            cursor++;
        }
        return out.toString().strip();
    }

    /**
     * Partitions token streams at top-level commas for commented constructs that need to format each segment
     * independently.
     */
    static List<List<String>> commaSeparated(List<String> tokens) {
        List<List<String>> parts = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (String token : tokens) {
            if (token.equals(",")) {
                parts.add(current);
                current = new ArrayList<>();
            } else {
                current.add(token);
            }
        }
        parts.add(current);
        return parts;
    }

    /**
     * Rebuilds a single line from tokens with Java punctuation spacing suitable for commented source fragments.
     */
    static String tokenLine(List<String> tokens) {
        StringBuilder out = new StringBuilder();
        for (String token : tokens) {
            if (token.equals(",")) {
                stripTrailingSpace(out);
                out.append(", ");
                continue;
            }
            if (token.equals(".")) {
                stripTrailingSpace(out);
                out.append('.');
                continue;
            }
            if (token.equals("...")) {
                stripTrailingSpace(out);
                out.append("...");
                continue;
            }
            if (!out.isEmpty() && out.charAt(out.length() - 1) != ' ' && needsSpaceBeforeToken(out)) {
                out.append(' ');
            }
            out.append(token);
        }
        return out.toString().strip();
    }

    private static boolean needsSpaceBeforeToken(StringBuilder out) {
        return out.charAt(out.length() - 1) != '.' || out.toString().endsWith("...");
    }

    private static void stripTrailingSpace(StringBuilder out) {
        while (!out.isEmpty() && out.charAt(out.length() - 1) == ' ') {
            out.deleteCharAt(out.length() - 1);
        }
    }
}
