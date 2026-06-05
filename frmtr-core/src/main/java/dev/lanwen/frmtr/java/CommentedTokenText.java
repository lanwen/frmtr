package dev.lanwen.frmtr.java;

import java.util.ArrayList;
import java.util.List;

final class CommentedTokenText {
    private static final String COMMENTED_TOKEN_PUNCTUATION = "{};,().";

    private CommentedTokenText() {}

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
            if (COMMENTED_TOKEN_PUNCTUATION.indexOf(current) >= 0) {
                tokens.add(String.valueOf(current));
                cursor++;
                continue;
            }
            int end = cursor + 1;
            while (end < line.length()
                    && !Character.isWhitespace(line.charAt(end))
                    && COMMENTED_TOKEN_PUNCTUATION.indexOf(line.charAt(end)) < 0
                    && !line.startsWith("/*", end)
                    && !line.startsWith("//", end)) {
                end++;
            }
            tokens.add(line.substring(cursor, end));
            cursor = end;
        }
        return tokens;
    }

    static boolean isComment(String token) {
        return token.startsWith("/*") || token.startsWith("//");
    }

    static void appendSpaceSeparated(StringBuilder out, List<String> values) {
        for (String value : values) {
            if (!out.isEmpty() && out.charAt(out.length() - 1) != ' ') {
                out.append(' ');
            }
            out.append(value);
        }
    }

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
                if (moveCommentsAfterDotBeforeDot && cursor + 1 < tokens.size() && tokens.get(cursor + 1).equals(".")) {
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
            if (!out.isEmpty() && out.charAt(out.length() - 1) != ' ' && out.charAt(out.length() - 1) != '.') {
                out.append(' ');
            }
            out.append(token);
        }
        return out.toString().strip();
    }

    private static void stripTrailingSpace(StringBuilder out) {
        while (!out.isEmpty() && out.charAt(out.length() - 1) == ' ') {
            out.deleteCharAt(out.length() - 1);
        }
    }
}
