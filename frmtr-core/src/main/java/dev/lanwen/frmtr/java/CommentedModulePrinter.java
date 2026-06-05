package dev.lanwen.frmtr.java;

import java.util.ArrayList;
import java.util.List;

/**
 * Formats the raw-source escape hatch for {@code module-info.java} declarations with comments inside directive syntax.
 *
 * <p>This helper owns module headers and the small set of module directives whose comments are awkward to reconstruct
 * from JavaParser's module directive AST. It deliberately does not decide when raw source should be used, how leading
 * declaration comments are attached, or how normal structured module blocks and directives are printed.
 */
final class CommentedModulePrinter {
    /**
     * Rebuilds a raw commented module declaration after the caller has chosen this escape hatch.
     *
     * <p>Blank runs inside the raw module body are compacted to a single blank line so comment-only directive sections
     * keep separation without preserving accidental vertical whitespace.
     */
    String formatCommentedModule(String rawModule) {
        String[] lines = rawModule.strip().split("\\R");
        if (lines.length == 0) {
            return rawModule.strip();
        }
        List<String> formatted = new ArrayList<>();
        formatted.add(formatCommentedModuleHeader(lines[0]));
        boolean previousBlank = false;
        for (int i = 1; i < lines.length - 1; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) {
                if (!previousBlank && !formatted.isEmpty()) {
                    formatted.add("");
                }
                previousBlank = true;
                continue;
            }
            formatted.add("  " + formatCommentedModuleDirective(line));
            previousBlank = false;
        }
        formatted.add("}");
        return String.join("\n", formatted);
    }

    /**
     * Rebuilds the commented module header, preserving comments before {@code open}, {@code module}, the module name,
     * and the opening brace.
     */
    private String formatCommentedModuleHeader(String line) {
        List<String> tokens = CommentedTokenText.tokens(line);
        int cursor = 0;
        List<String> leadingComments = new ArrayList<>();
        while (cursor < tokens.size() && CommentedTokenText.isComment(tokens.get(cursor))) {
            leadingComments.add(tokens.get(cursor++));
        }
        boolean open = cursor < tokens.size() && tokens.get(cursor).equals("open");
        if (open) {
            cursor++;
        }
        List<String> beforeNameComments = new ArrayList<>();
        // Comments between "open" and "module", plus comments immediately after "module", stay before the name.
        while (cursor < tokens.size() && !tokens.get(cursor).equals("module")) {
            if (CommentedTokenText.isComment(tokens.get(cursor))) {
                beforeNameComments.add(tokens.get(cursor));
            }
            cursor++;
        }
        if (cursor < tokens.size() && tokens.get(cursor).equals("module")) {
            cursor++;
        }
        while (cursor < tokens.size() && CommentedTokenText.isComment(tokens.get(cursor))) {
            beforeNameComments.add(tokens.get(cursor++));
        }
        int nameEnd = tokens.indexOf("{");
        if (nameEnd < 0) {
            nameEnd = tokens.size();
        }
        StringBuilder out = new StringBuilder();
        CommentedTokenText.appendSpaceSeparated(out, leadingComments);
        if (!out.isEmpty()) {
            out.append(' ');
        }
        if (open) {
            out.append("open ");
        }
        out.append("module");
        if (!beforeNameComments.isEmpty()) {
            out.append(' ');
            CommentedTokenText.appendSpaceSeparated(out, beforeNameComments);
        }
        out.append(' ');
        out.append(CommentedTokenText.qualifiedName(tokens.subList(cursor, nameEnd), true));
        out.append(" {");
        return out.toString();
    }

    /**
     * Rebuilds one commented module directive line while preserving leading and post-semicolon comments.
     */
    private String formatCommentedModuleDirective(String line) {
        if (line.startsWith("//")) {
            return line;
        }
        List<String> tokens = CommentedTokenText.tokens(line);
        int semicolon = tokens.indexOf(";");
        if (semicolon < 0) {
            semicolon = tokens.size();
        }
        List<String> afterSemicolonComments = new ArrayList<>();
        for (int i = semicolon + 1; i < tokens.size(); i++) {
            if (CommentedTokenText.isComment(tokens.get(i))) {
                afterSemicolonComments.add(tokens.get(i));
            }
        }
        int cursor = 0;
        List<String> leadingComments = new ArrayList<>();
        while (cursor < semicolon && CommentedTokenText.isComment(tokens.get(cursor))) {
            leadingComments.add(tokens.get(cursor++));
        }
        if (cursor >= semicolon) {
            return line;
        }
        String keyword = tokens.get(cursor++);
        String body = switch (keyword) {
            case "requires" -> formatCommentedRequires(tokens, cursor, semicolon);
            case "exports", "opens" -> formatCommentedModuleAccess(tokens, cursor, semicolon);
            case "uses" -> formatCommentedUses(tokens, cursor, semicolon);
            default -> line;
        };
        if (body.equals(line)) {
            return line;
        }
        StringBuilder out = new StringBuilder();
        CommentedTokenText.appendSpaceSeparated(out, leadingComments);
        if (!out.isEmpty()) {
            out.append(' ');
        }
        out.append(keyword);
        if (!body.isEmpty()) {
            out.append(' ').append(body);
        }
        out.append(';');
        if (!afterSemicolonComments.isEmpty()) {
            out.append(' ');
            CommentedTokenText.appendSpaceSeparated(out, afterSemicolonComments);
        }
        return out.toString();
    }

    /**
     * Rebuilds a {@code requires} body with comments before {@code transitive}, before the module name, or both.
     */
    private String formatCommentedRequires(List<String> tokens, int cursor, int semicolon) {
        List<String> beforeTransitiveOrName = new ArrayList<>();
        while (cursor < semicolon && CommentedTokenText.isComment(tokens.get(cursor))) {
            beforeTransitiveOrName.add(tokens.get(cursor++));
        }
        StringBuilder out = new StringBuilder();
        if (cursor < semicolon && tokens.get(cursor).equals("transitive")) {
            CommentedTokenText.appendSpaceSeparated(out, beforeTransitiveOrName);
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append("transitive");
            cursor++;
            List<String> beforeName = new ArrayList<>();
            while (cursor < semicolon && CommentedTokenText.isComment(tokens.get(cursor))) {
                beforeName.add(tokens.get(cursor++));
            }
            if (!beforeName.isEmpty()) {
                out.append(' ');
                CommentedTokenText.appendSpaceSeparated(out, beforeName);
            }
            out.append(' ');
        } else {
            CommentedTokenText.appendSpaceSeparated(out, beforeTransitiveOrName);
            if (!out.isEmpty()) {
                out.append(' ');
            }
        }
        out.append(CommentedTokenText.qualifiedName(tokens.subList(cursor, semicolon), false));
        return out.toString();
    }

    /**
     * Rebuilds {@code exports} and {@code opens} bodies, breaking {@code to} target lists onto a continuation line.
     */
    private String formatCommentedModuleAccess(List<String> tokens, int cursor, int semicolon) {
        List<String> beforeName = new ArrayList<>();
        while (cursor < semicolon && CommentedTokenText.isComment(tokens.get(cursor))) {
            beforeName.add(tokens.get(cursor++));
        }
        int targetKeyword = tokens.subList(cursor, semicolon).indexOf("to");
        if (targetKeyword < 0) {
            StringBuilder out = new StringBuilder();
            CommentedTokenText.appendSpaceSeparated(out, beforeName);
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(CommentedTokenText.qualifiedName(tokens.subList(cursor, semicolon), false));
            return out.toString();
        }
        int toIndex = cursor + targetKeyword;
        int nameEnd = toIndex;
        // Comments immediately before "to" stay with the package name line, before the target-list break.
        while (nameEnd > cursor && CommentedTokenText.isComment(tokens.get(nameEnd - 1))) {
            nameEnd--;
        }
        List<String> beforeTo = tokens.subList(nameEnd, toIndex);
        StringBuilder out = new StringBuilder();
        CommentedTokenText.appendSpaceSeparated(out, beforeName);
        if (!out.isEmpty()) {
            out.append(' ');
        }
        out.append(CommentedTokenText.qualifiedName(tokens.subList(cursor, nameEnd), false));
        if (!beforeTo.isEmpty()) {
            out.append(' ');
            CommentedTokenText.appendSpaceSeparated(out, beforeTo);
        }
        out.append('\n');
        out.append("    to ");
        out.append(formatCommentedModuleTargets(tokens.subList(toIndex + 1, semicolon)));
        return out.toString();
    }

    /**
     * Rebuilds a {@code uses} body with comments before the service name or immediately before the semicolon.
     */
    private String formatCommentedUses(List<String> tokens, int cursor, int semicolon) {
        List<String> beforeName = new ArrayList<>();
        while (cursor < semicolon && CommentedTokenText.isComment(tokens.get(cursor))) {
            beforeName.add(tokens.get(cursor++));
        }
        List<String> nameTokens = new ArrayList<>(tokens.subList(cursor, semicolon));
        List<String> beforeSemicolon = new ArrayList<>();
        while (!nameTokens.isEmpty() && CommentedTokenText.isComment(nameTokens.getLast())) {
            beforeSemicolon.add(0, nameTokens.removeLast());
        }
        StringBuilder out = new StringBuilder();
        CommentedTokenText.appendSpaceSeparated(out, beforeName);
        if (!out.isEmpty()) {
            out.append(' ');
        }
        out.append(CommentedTokenText.qualifiedName(nameTokens, false));
        if (!beforeSemicolon.isEmpty()) {
            out.append(' ');
            CommentedTokenText.appendSpaceSeparated(out, beforeSemicolon);
        }
        return out.toString();
    }

    /**
     * Reconstructs a comma-separated module target list after each target's qualified-name comments are normalized.
     */
    private String formatCommentedModuleTargets(List<String> tokens) {
        List<String> parts = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (String token : tokens) {
            if (token.equals(",")) {
                parts.add(CommentedTokenText.qualifiedName(current, false));
                current = new ArrayList<>();
            } else {
                current.add(token);
            }
        }
        if (!current.isEmpty()) {
            parts.add(CommentedTokenText.qualifiedName(current, false));
        }
        return String.join(", ", parts);
    }
}
