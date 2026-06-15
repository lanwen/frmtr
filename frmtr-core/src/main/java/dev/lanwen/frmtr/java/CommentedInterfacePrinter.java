package dev.lanwen.frmtr.java;

import java.util.ArrayList;
import java.util.List;

/**
 * Formats the raw-source escape hatch for interface declarations with comments inside declaration syntax.
 *
 * <p>This helper owns interface headers and abstract method signatures where comments appear inside the header or
 * signature text and JavaParser does not expose them well enough for the structured declaration printer. It deliberately
 * does not decide leading-comment attachment, normal class/interface declaration formatting, member sequencing, or any
 * method fallback with a body.
 */
final class CommentedInterfacePrinter {

    /**
     * Reports whether an interface header contains block comments that need the raw-source formatter.
     */
    boolean hasCommentedHeader(String rawInterface) {
        return commentedInterfaceHeader(rawInterface).contains("/*");
    }

    /**
     * Rebuilds the raw interface declaration once the caller has chosen this escape hatch.
     *
     * <p>Any source lines before the interface header are carried through with trailing whitespace stripped, while the
     * body is normalized through the small set of raw-comment cases this fallback owns.
     */
    String formatCommentedInterface(String rawInterface) {
        String[] lines = rawInterface.strip().split("\\R");
        List<String> formatted = new ArrayList<>();
        int headerLine = -1;
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains("interface")) {
                headerLine = i;
                break;
            }
            formatted.add(lines[i].stripTrailing());
        }
        if (headerLine < 0) {
            return rawInterface.strip();
        }
        formatted.add(formatCommentedInterfaceHeader(lines[headerLine].strip()));
        for (int i = headerLine + 1; i < lines.length; i++) {
            String line = lines[i].strip();
            // Blank raw lines inside this fallback are omitted to preserve the existing compact interface-member style.
            if (line.isEmpty()) {
                continue;
            }
            if (line.equals("}")) {
                formatted.add("}");
            } else if (line.endsWith(";") || line.contains(";/*")) {
                // Single-line abstract method declarations are the only members this raw interface fallback reshapes.
                formatted.add(formatCommentedAbstractMethod(line));
            } else if (line.startsWith("*") || line.equals("*/")) {
                formatted.add("   " + line);
            } else {
                formatted.add("  " + line);
            }
        }
        return String.join("\n", formatted);
    }

    /**
     * Returns only the raw declaration header so body comments do not opt an otherwise normal interface into this path.
     */
    private String commentedInterfaceHeader(String rawInterface) {
        int openBrace = rawInterface.indexOf('{');
        return openBrace < 0 ? rawInterface : rawInterface.substring(0, openBrace);
    }

    /**
     * Rebuilds a commented interface header while preserving comments around the {@code extends} clause and opening
     * brace.
     */
    private String formatCommentedInterfaceHeader(String line) {
        List<String> tokens = new ArrayList<>(CommentedTokenText.tokens(line));
        int openBrace = tokens.indexOf("{");
        if (openBrace < 0) {
            return line;
        }
        List<String> beforeBrace = new ArrayList<>();
        // Comments immediately before "{" belong on the closing header line, after any broken extends clause.
        for (int i = openBrace - 1; i >= 0 && CommentedTokenText.isComment(tokens.get(i)); i--) {
            beforeBrace.add(0, tokens.get(i));
        }
        tokens = new ArrayList<>(tokens.subList(0, openBrace - beforeBrace.size()));
        int extendsIndex = tokens.indexOf("extends");
        if (extendsIndex < 0) {
            return CommentedTokenText.tokenLine(tokens) + " {";
        }
        List<String> beforeExtends = new ArrayList<>(tokens.subList(0, extendsIndex));
        List<String> clauseLeading = new ArrayList<>();
        // Comments just before "extends" are treated as clause-leading comments rather than interface-name comments.
        while (!beforeExtends.isEmpty() && CommentedTokenText.isComment(beforeExtends.getLast())) {
            clauseLeading.add(0, beforeExtends.removeLast());
        }
        List<String> clause = new ArrayList<>(clauseLeading);
        clause.addAll(tokens.subList(extendsIndex, tokens.size()));
        return String.join(
            "\n",
            CommentedTokenText.tokenLine(beforeExtends),
            "  " + CommentedTokenText.tokenLine(clause),
            CommentedTokenText.tokenLine(beforeBrace) + " {"
        );
    }

    /**
     * Formats one commented abstract method signature from a raw interface body line.
     */
    private String formatCommentedAbstractMethod(String line) {
        List<String> tokens = CommentedTokenText.tokens(line);
        int open = tokens.indexOf("(");
        int close = tokens.lastIndexOf(")");
        int semicolon = tokens.indexOf(";");
        if (open < 0 || close < open || semicolon < close) {
            return "  " + CommentedTokenText.tokenLine(tokens);
        }
        List<String> docs = new ArrayList<>();
        docs.add("  " + CommentedTokenText.tokenLine(tokens.subList(0, open)) + "(");
        List<String> parameterTokens = tokens.subList(open + 1, close);
        if (!parameterTokens.isEmpty()) {
            // Each comma-separated parameter segment keeps its adjacent comments before trailing commas are restored.
            for (List<String> parameter : CommentedTokenText.commaSeparated(parameterTokens)) {
                docs.add("    " + CommentedTokenText.tokenLine(parameter) + ",");
            }
            String lastParameter = docs.removeLast();
            docs.add(lastParameter.substring(0, lastParameter.length() - 1));
        }
        String suffix = CommentedTokenText.tokenLine(tokens.subList(close + 1, semicolon));
        String trailing = CommentedTokenText.tokenLine(tokens.subList(semicolon + 1, tokens.size()));
        docs.add("  )" + (suffix.isEmpty() ? "" : " " + suffix) + ";" + (trailing.isEmpty() ? "" : " " + trailing));
        return String.join("\n", docs);
    }
}
