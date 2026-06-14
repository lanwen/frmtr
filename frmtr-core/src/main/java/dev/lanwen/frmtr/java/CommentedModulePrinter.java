package dev.lanwen.frmtr.java;

import java.util.ArrayList;
import java.util.List;

/**
 * Formats the raw-source escape hatch for {@code module-info.java} declarations with comments inside directive syntax.
 *
 * <p>This helper owns module headers and the small set of module directives whose comments are awkward to reconstruct
 * from JavaParser's module directive AST. It deliberately does not decide when raw source should be used, how leading
 * declaration comments are attached, or how normal structured module blocks and directives are printed.
 *
 * <p>The fixture pair {@code format/comment-preservation-module-declaration/input.java} and {@code
 * format/comment-preservation-module-declaration/frmtr-default.output.java} shows the expected output for this raw commented
 * module path.
 */
final class CommentedModulePrinter {
    /**
     * Rebuilds a raw commented module declaration after the caller has chosen this escape hatch.
     *
     * <p>The module body is split into directive units by their terminating {@code ;} rather than by physical source
     * lines, so the reconstruction is independent of how the source distributed whitespace. A purely line-based split
     * silently dropped every directive when whitespace was collapsed (the whole module on one line) and duplicated the
     * {@code module} keyword when whitespace was expanded (each token on its own line). Standalone line comments between
     * directives keep their own line; block comments stay attached to the directive unit that owns them. Blank runs are
     * compacted to a single blank line so comment-only sections keep separation without preserving accidental vertical
     * whitespace.
     */
    String formatCommentedModule(String rawModule) {
        String stripped = rawModule.strip();
        int headerEnd = stripped.indexOf('{');
        int bodyEnd = stripped.lastIndexOf('}');
        if (headerEnd < 0 || bodyEnd < headerEnd) {
            return stripped;
        }
        List<String> formatted = new ArrayList<>();
        formatted.add(formatCommentedModuleHeader(stripped.substring(0, headerEnd + 1)));
        for (ModuleBodyUnit unit : moduleBodyUnits(stripped.substring(headerEnd + 1, bodyEnd))) {
            if (unit.blank()) {
                if (!formatted.isEmpty() && !formatted.getLast().isEmpty()) {
                    formatted.add("");
                }
            } else {
                formatted.add("  " + formatCommentedModuleDirective(unit.text()));
            }
        }
        while (formatted.size() > 1 && formatted.getLast().isEmpty()) {
            formatted.removeLast();
        }
        formatted.add("}");
        return String.join("\n", formatted);
    }

    /**
     * One reconstructed module-body item: either a directive/comment unit ({@link #text}) or a blank-line marker.
     */
    private record ModuleBodyUnit(String text, boolean blank) {
        static ModuleBodyUnit directive(String text) {
            return new ModuleBodyUnit(text, false);
        }

        static ModuleBodyUnit blankLine() {
            return new ModuleBodyUnit("", true);
        }
    }

    /**
     * Splits a raw module body into directive and standalone-comment units independent of source whitespace.
     *
     * <p>Directives are delimited by their terminating {@code ;} (with any same-segment trailing comments kept on the
     * directive). A standalone line comment (one not trailing a directive's {@code ;}) becomes its own unit so it keeps a
     * dedicated line. A blank source line that separates units is recorded as a single blank marker. Splitting on {@code ;}
     * rather than on newlines is what makes the reconstruction robust to collapsed (everything on one line) and expanded
     * (one token per line) whitespace alike.
     */
    private List<ModuleBodyUnit> moduleBodyUnits(String body) {
        List<ModuleBodyUnit> units = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean sawBlankRun = false;
        int index = 0;
        int length = body.length();
        while (index < length) {
            char ch = body.charAt(index);
            if (ch == '\n' || ch == '\r') {
                // Track blank source lines only while no directive text is pending, so they separate units rather than
                // splitting a directive that the source happened to wrap across lines.
                if (current.toString().isBlank()) {
                    current.setLength(0);
                    if (peekBlankLine(body, index)) {
                        sawBlankRun = true;
                    }
                }
                index++;
                continue;
            }
            if (body.startsWith("/*", index)) {
                int end = body.indexOf("*/", index + 2);
                end = end < 0 ? length : end + 2;
                current.append(body, index, end);
                index = end;
                continue;
            }
            if (body.startsWith("//", index)) {
                int end = lineEnd(body, index);
                String comment = body.substring(index, end).stripTrailing();
                if (current.toString().isBlank()) {
                    // A standalone line comment owns its own unit and line.
                    flushBlank(units, sawBlankRun);
                    sawBlankRun = false;
                    units.add(ModuleBodyUnit.directive(comment));
                    current.setLength(0);
                } else {
                    current.append(' ').append(comment);
                }
                index = end;
                continue;
            }
            if (ch == ';') {
                current.append(';');
                index++;
                // Attach trailing block/line comments that share the directive's segment before the next line break.
                index = appendTrailingComments(body, index, current);
                flushBlank(units, sawBlankRun);
                sawBlankRun = false;
                units.add(ModuleBodyUnit.directive(current.toString().strip()));
                current.setLength(0);
                continue;
            }
            if (current.toString().isBlank() && Character.isWhitespace(ch)) {
                index++;
                continue;
            }
            current.append(ch);
            index++;
        }
        if (!current.toString().isBlank()) {
            flushBlank(units, sawBlankRun);
            units.add(ModuleBodyUnit.directive(current.toString().strip()));
        }
        return units;
    }

    private void flushBlank(List<ModuleBodyUnit> units, boolean sawBlankRun) {
        if (sawBlankRun && !units.isEmpty()) {
            units.add(ModuleBodyUnit.blankLine());
        }
    }

    /**
     * Appends comments that trail a directive's {@code ;} into {@code current}, but only when they truly belong to this
     * directive rather than leading the next one.
     *
     * <p>A {@code //} line comment after the {@code ;} always trails this directive (it runs to the end of the line). A
     * {@code /} block comment after the {@code ;} trails this directive only when nothing but whitespace, a line break,
     * or the closing brace follows it: if a directive keyword or name follows, the block comment leads that next
     * directive (e.g. {@code requires x; /* note *}{@code / uses y;} — the {@code /* note *}{@code /} leads {@code uses}).
     * Consuming such a comment here would otherwise move it onto the wrong directive when the source was collapsed onto
     * a single line.
     */
    private int appendTrailingComments(String body, int index, StringBuilder current) {
        int length = body.length();
        StringBuilder pendingBlockComments = new StringBuilder();
        int cursor = index;
        while (cursor < length) {
            char ch = body.charAt(cursor);
            if (ch == ' ' || ch == '\t') {
                cursor++;
                continue;
            }
            if (body.startsWith("/*", cursor)) {
                int end = body.indexOf("*/", cursor + 2);
                end = end < 0 ? length : end + 2;
                pendingBlockComments.append(' ').append(body, cursor, end);
                cursor = end;
                continue;
            }
            if (body.startsWith("//", cursor)) {
                // A line comment terminates the line, so block comments seen before it also trail this directive.
                int end = lineEnd(body, cursor);
                current.append(pendingBlockComments).append(' ').append(body, cursor, end);
                return end;
            }
            if (ch == '\n' || ch == '\r' || ch == '}') {
                // End of line (or body): pending block comments genuinely trail this directive.
                current.append(pendingBlockComments);
                return cursor;
            }
            // Directive content follows: the pending block comments lead the next directive, so leave them unconsumed.
            return index;
        }
        current.append(pendingBlockComments);
        return cursor;
    }

    private static boolean peekBlankLine(String body, int newlineIndex) {
        for (int i = newlineIndex + 1; i < body.length(); i++) {
            char ch = body.charAt(i);
            if (ch == '\n') {
                return true;
            }
            if (!Character.isWhitespace(ch) || ch == '\r') {
                if (ch == '\r') {
                    continue;
                }
                return false;
            }
        }
        return false;
    }

    private static int lineEnd(String text, int from) {
        for (int i = from; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\n' || ch == '\r') {
                return i;
            }
        }
        return text.length();
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
