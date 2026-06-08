package dev.lanwen.frmtr.doc;

/**
 * Renders a stable structural view of formatter documents for internal diagnostics and focused tests.
 *
 * <p>This helper owns only the tree-shaped, human-readable representation of {@link Doc} values. The boundary exists so
 * formatter maintainers can inspect document IR shape, break opportunities, indentation scopes, groups, and flat versus
 * broken alternatives without invoking the width-fitting renderer. It intentionally leaves column fitting, concrete line
 * endings, indentation text, trailing-newline policy, and Java syntax policy to {@link DocRenderer} and the language
 * printers.
 */
public final class DocDebugRenderer {
    private static final String INDENT = "  ";

    private DocDebugRenderer() {}

    /**
     * Returns a concise debug tree for the supplied document using {@code \n} separators regardless of platform line
     * endings.
     */
    public static String render(Doc doc) {
        StringBuilder out = new StringBuilder();
        render(doc, out, 0);
        if (!out.isEmpty()) {
            out.setLength(out.length() - 1);
        }
        return out.toString();
    }

    private static void render(Doc doc, StringBuilder out, int depth) {
        switch (doc) {
            case Doc.Text text -> appendLine(out, depth, "Text(\"" + escaped(text.value()) + "\")");
            case Doc.Concat concat -> {
                appendLine(out, depth, "Concat");
                concat.docs().forEach(child -> render(child, out, depth + 1));
            }
            case Doc.Line _ -> appendLine(out, depth, "Line");
            case Doc.SoftLine _ -> appendLine(out, depth, "SoftLine");
            case Doc.HardLine _ -> appendLine(out, depth, "HardLine");
            case Doc.Indent indented -> {
                appendLine(out, depth, "Indent");
                render(indented.doc(), out, depth + 1);
            }
            case Doc.Group group -> {
                appendLine(out, depth, "Group");
                render(group.doc(), out, depth + 1);
            }
            case Doc.IfBreak conditional -> {
                appendLine(out, depth, "IfBreak");
                appendLine(out, depth + 1, "break:");
                render(conditional.breakDoc(), out, depth + 2);
                appendLine(out, depth + 1, "flat:");
                render(conditional.flatDoc(), out, depth + 2);
            }
            case Doc.Label label -> {
                appendLine(out, depth, "Label(\"" + escaped(label.label()) + "\")");
                render(label.doc(), out, depth + 1);
            }
        }
    }

    private static void appendLine(StringBuilder out, int depth, String line) {
        out.append(INDENT.repeat(depth)).append(line).append('\n');
    }

    /**
     * Escapes source-sensitive characters so debug output remains one logical line per document node.
     */
    private static String escaped(String value) {
        StringBuilder escaped = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                default -> {
                    if (Character.isISOControl(current)) {
                        escaped.append("\\u");
                        String hex = Integer.toHexString(current);
                        escaped.append("0".repeat(4 - hex.length())).append(hex);
                    } else {
                        escaped.append(current);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
