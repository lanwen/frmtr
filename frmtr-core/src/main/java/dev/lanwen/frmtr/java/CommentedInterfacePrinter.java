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
     *
     * <p>Only a comment at or after the {@code interface} keyword counts: those live inside the header syntax (name,
     * type clauses, brace) that this fallback rebuilds. A comment before the keyword is ordinary leading trivia the
     * structured printer places, so it must not divert the declaration here.
     */
    boolean hasCommentedHeader(String rawInterface) {
        List<String> tokens = CommentedTokenText.tokens(commentedInterfaceHeader(rawInterface));
        int keyword = tokens.indexOf("interface");
        return keyword >= 0
            && tokens.subList(keyword, tokens.size()).stream().anyMatch(token -> token.startsWith("/*"));
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
        HeaderSplit header = formatCommentedInterfaceHeader(lines[headerLine].strip());
        formatted.add(header.headerLines());
        // A collapsed layout slides the body's first comment onto the brace line (`... { // comment`); carry that
        // trailing-after-brace comment onto its own indented body line so it is not discarded with the header rebuild.
        for (String afterBrace : header.afterBraceComments()) {
            formatted.add("  " + afterBrace);
        }
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
     * The rebuilt header text plus any comment tokens that trailed the opening brace on the (possibly collapsed) header
     * line. The caller renders {@link #afterBraceComments()} on their own indented body lines so they survive the header
     * rebuild, which keeps only the tokens up to {@code "{"}.
     */
    private record HeaderSplit(String headerLines, List<String> afterBraceComments) {}

    /**
     * Rebuilds a commented interface header while preserving comments around the {@code extends} clause and opening
     * brace.
     *
     * <p>Comment tokens that follow the {@code "{"} on the same source line (e.g. a body's first {@code // comment} that a
     * collapsed layout slid up onto the brace line) are returned separately so the caller can carry them into the body
     * instead of letting the header rebuild — which keeps only tokens up to {@code "{"} — discard them. At the default
     * layout {@code "{"} is the last header token, so the after-brace list is empty and the rendered header is unchanged.
     */
    private HeaderSplit formatCommentedInterfaceHeader(String line) {
        List<String> tokens = new ArrayList<>(CommentedTokenText.tokens(line));
        int openBrace = tokens.indexOf("{");
        if (openBrace < 0) {
            return new HeaderSplit(line, List.of());
        }
        List<String> afterBrace = new ArrayList<>();
        // Comments after "{" on this line belong to the body (collapsed layouts slide the first body comment up here).
        for (int i = openBrace + 1; i < tokens.size() && CommentedTokenText.isComment(tokens.get(i)); i++) {
            afterBrace.add(tokens.get(i));
        }
        List<String> beforeBrace = new ArrayList<>();
        // Comments immediately before "{" belong on the closing header line, after any broken extends clause.
        for (int i = openBrace - 1; i >= 0 && CommentedTokenText.isComment(tokens.get(i)); i--) {
            beforeBrace.add(0, tokens.get(i));
        }
        tokens = new ArrayList<>(tokens.subList(0, openBrace - beforeBrace.size()));
        int extendsIndex = tokens.indexOf("extends");
        if (extendsIndex < 0) {
            return new HeaderSplit(CommentedTokenText.tokenLine(tokens) + " {", afterBrace);
        }
        List<String> beforeExtends = new ArrayList<>(tokens.subList(0, extendsIndex));
        List<String> clauseLeading = new ArrayList<>();
        // Comments just before "extends" are treated as clause-leading comments rather than interface-name comments.
        while (!beforeExtends.isEmpty() && CommentedTokenText.isComment(beforeExtends.getLast())) {
            clauseLeading.add(0, beforeExtends.removeLast());
        }
        List<String> clause = new ArrayList<>(clauseLeading);
        clause.addAll(tokens.subList(extendsIndex, tokens.size()));
        String headerLines = String.join(
            "\n",
            CommentedTokenText.tokenLine(beforeExtends),
            "  " + CommentedTokenText.tokenLine(clause),
            CommentedTokenText.tokenLine(beforeBrace) + " {"
        );
        return new HeaderSplit(headerLines, afterBrace);
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
