package dev.lanwen.frmtr.cli;

import dev.lanwen.frmtr.ExplainResult;
import dev.lanwen.frmtr.doc.DocExplanation;
import dev.lanwen.frmtr.doc.DocExplanation.Decision;
import dev.lanwen.frmtr.doc.DocExplanation.ForcedBreak;
import dev.lanwen.frmtr.doc.DocExplanation.GroupDecision;
import dev.lanwen.frmtr.doc.DocExplanation.Node;
import dev.lanwen.frmtr.doc.PrinterWrap;
import java.util.List;

/**
 * Renders an {@link ExplainResult} into the human-readable {@code --explain} report.
 *
 * <p>This view owns layout and wording of the explanation only. It does not decide colors: callers pass a {@link Styler}
 * so the CLI keeps ANSI policy (the {@code --color} flag) in one place, and the same view renders identical plain text
 * for logs, bug reports, and tests by supplying a no-op styler. It deliberately knows nothing about formatting policy;
 * it merely presents the decisions the core already made.
 */
final class ExplainView {
    /**
     * Applies a presentation role to a span of text. The CLI maps roles to ANSI styles; a plain renderer returns the
     * text unchanged.
     */
    @FunctionalInterface
    interface Styler {
        String style(Role role, String text);
    }

    /**
     * Semantic roles in the explain report, kept separate from concrete colors so presentation stays in the CLI.
     */
    enum Role {
        HEADING,
        BREAK,
        FLAT,
        LABEL,
        NUMBER,
        TREE,
        FADE
    }

    private final Styler styler;
    private final boolean verbose;

    ExplainView(Styler styler, boolean verbose) {
        this.styler = styler;
        this.verbose = verbose;
    }

    String render(ExplainResult result) {
        DocExplanation explanation = result.explanation();
        StringBuilder out = new StringBuilder();

        out.append(styler.style(Role.HEADING, "Formatted"))
                .append(' ')
                .append(styler.style(Role.FADE, "(line width " + explanation.lineWidth() + ")"))
                .append('\n');
        appendIndentedBlock(out, result.formatted());
        out.append('\n');

        appendWhyItWrapped(out, explanation);
        out.append('\n');

        appendTree(out, explanation);
        out.append('\n');

        appendLegend(out);
        return out.toString();
    }

    private void appendIndentedBlock(StringBuilder out, String text) {
        String body = text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
        for (String line : body.split("\n", -1)) {
            out.append("  ").append(line).append('\n');
        }
    }

    private void appendWhyItWrapped(StringBuilder out, DocExplanation explanation) {
        out.append(styler.style(Role.HEADING, "Why it wrapped")).append('\n');

        List<PrinterWrap> printerWraps = explanation.printerWraps();
        List<GroupDecision> rendererBreaks = explanation.brokenGroups().stream()
                .filter(decision -> !decision.forcedBreak())
                .toList();
        boolean hasMeasuredReason = !printerWraps.isEmpty() || !rendererBreaks.isEmpty();
        List<ForcedBreak> causalForced = causalForcedBreaks(explanation, hasMeasuredReason);

        if (printerWraps.isEmpty() && rendererBreaks.isEmpty() && causalForced.isEmpty()) {
            out.append("  ")
                    .append(styler.style(Role.FLAT, "Nothing wrapped"))
                    .append(" — everything fit within ")
                    .append(styler.style(Role.NUMBER, explanation.lineWidth() + ""))
                    .append(" columns.\n");
            return;
        }

        for (PrinterWrap wrap : printerWraps) {
            appendPrinterWrap(out, wrap);
        }
        for (GroupDecision decision : rendererBreaks) {
            appendRendererWidthBreak(out, decision);
        }
        for (ForcedBreak forcedBreak : causalForced) {
            appendCausalForcedBreak(out, forcedBreak);
        }
    }

    /**
     * Keeps only the forced breaks that are genuinely the cause of a wrap, dropping noise.
     *
     * <p>Body declarations and compilation units always span lines structurally, so they are never a wrap cause and are
     * always dropped. Statements never wrap on their own width in this formatter: they delegate layout to the
     * expression printer inside them, so a broken statement is propagation, not a cause. They are therefore dropped
     * whenever some measured reason (a printer wrap or a renderer width break) already explains the wrap; they survive
     * only when nothing measured does, so the "Why it wrapped" section is never empty while something visibly wrapped.
     *
     * <p>A printer wrap and the renderer-trace forced break for the same construct are the same wrap seen twice, so a
     * forced break that a printer wrap already reported is dropped. Matching is by provenance, not by label alone: a
     * printer-wrap label is only the node type (for example {@code java.expression:MethodCallExpr}), so a width-wrapping
     * chain and a separate same-typed call that broke for a non-width reason (a trailing line comment, a
     * source-multiline argument list) carry the same label. Suppressing every forced break with a matching label would
     * silently hide the second wrap and tell the developer one construct wrapped when two did. A printer wrap therefore
     * claims only the forced break whose break count equals the number of hard breaks the wrap's broken layout emits
     * (its segments laid one per line, i.e. {@code segments - 1}); a different-sized same-typed break is a distinct
     * construct and survives as the muted "laid out across lines by rule" note. Wraps that do not lay countable
     * segments one per line (a ternary, an argument list whose call header stays on the opening line) leave their
     * pass-through statement break to the statement rule above and claim no expression break.
     */
    private List<ForcedBreak> causalForcedBreaks(DocExplanation explanation, boolean hasMeasuredReason) {
        List<PrinterWrap> unclaimedWraps = new java.util.ArrayList<>(explanation.printerWraps());
        return explanation.forcedBreaks().stream()
                .filter(forced -> !claimedByPrinterWrap(forced, unclaimedWraps))
                .filter(forced -> !isStructuralBody(forced.label().orElse("")))
                .filter(forced -> !(hasMeasuredReason && isPassThroughStatement(forced.label().orElse(""))))
                .toList();
    }

    /**
     * Whether a printer wrap already reported this forced break, removing the matched wrap so each wrap claims at most
     * one break. A wrap claims a break only when their labels match and the break count equals the wrap's emitted hard
     * breaks ({@code segments - 1}); this keeps a same-typed break of a different size — a distinct construct that broke
     * for its own reason — visible instead of folding it into the recorded width wrap.
     */
    private boolean claimedByPrinterWrap(ForcedBreak forced, List<PrinterWrap> unclaimedWraps) {
        String label = forced.label().orElse(null);
        if (label == null) {
            return false;
        }
        for (java.util.Iterator<PrinterWrap> wraps = unclaimedWraps.iterator(); wraps.hasNext(); ) {
            PrinterWrap wrap = wraps.next();
            if (wrap.label().equals(label) && wrap.segments() - 1 == forced.count()) {
                wraps.remove();
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a label names a class/interface/record/enum/method/compilation body whose multiline layout is structural,
     * not a width decision. These always span lines, so surfacing them as a wrap reason is noise.
     */
    private boolean isStructuralBody(String label) {
        return label.startsWith("java.bodyDeclaration:")
                || label.equals("java.compilationUnit")
                || label.equals("java.statement:BlockStmt");
    }

    /**
     * Whether a label names a statement that merely hosts a wrapped expression. Such a statement spans lines only
     * because its expression broke, so it is propagation rather than an independent width cause.
     */
    private boolean isPassThroughStatement(String label) {
        return label.startsWith("java.statement:");
    }

    /**
     * Renders a printer-recorded width wrap: the friendly construct, a short preview, and the real arithmetic the
     * printer measured before it broke the construct.
     */
    private void appendPrinterWrap(StringBuilder out, PrinterWrap wrap) {
        out.append("  ")
                .append(styler.style(Role.LABEL, wrap.construct()))
                .append(' ')
                .append(styler.style(Role.FADE, "`" + wrap.preview() + "`"))
                .append(" broke:\n");
        out.append("    flat width ")
                .append(styler.style(Role.NUMBER, wrap.flatWidth() + ""))
                .append(" > ")
                .append(styler.style(Role.NUMBER, wrap.available() + ""))
                .append(" available\n");
        if (wrap.segments() > 0) {
            out.append("    ")
                    .append(styler.style(
                            Role.FADE, "(" + wrap.segments() + " segments, one per line)"))
                    .append('\n');
        }
        if (verbose) {
            out.append("    ").append(styler.style(Role.FADE, wrap.label())).append('\n');
        }
    }

    /**
     * Renders a width break the renderer itself decided by measuring a group against the columns left.
     */
    private void appendRendererWidthBreak(StringBuilder out, GroupDecision decision) {
        out.append("  ")
                .append(styler.style(Role.LABEL, friendlyLabel(decision.label().orElse("group"))))
                .append(" broke:\n");
        out.append("    flat width ")
                .append(styler.style(Role.NUMBER, decision.flatWidth() + ""))
                .append(" > ")
                .append(styler.style(Role.NUMBER, decision.available() + ""))
                .append(" available")
                .append(styler.style(Role.FADE, " (from column " + decision.startColumn() + ")"))
                .append('\n');
        if (verbose) {
            out.append("    ").append(styler.style(Role.FADE, decision.label().orElse("group"))).append('\n');
        }
    }

    /**
     * Renders a forced break that is genuinely the cause but carries no width arithmetic, under a muted note so it does
     * not read like a measured decision.
     */
    private void appendCausalForcedBreak(StringBuilder out, ForcedBreak forcedBreak) {
        String label = forcedBreak.label().orElse("group");
        out.append("  ")
                .append(styler.style(Role.LABEL, friendlyLabel(label)))
                .append(' ')
                .append(styler.style(
                        Role.FADE,
                        "laid out across lines by rule (no width measurement)"))
                .append('\n');
        if (verbose) {
            out.append("    ").append(styler.style(Role.FADE, label)).append('\n');
        }
    }

    /**
     * Maps an internal {@code java.*:NodeType} label to a friendly construct name developers recognize, falling back to
     * the local rule name when no mapping is known. The raw label stays available in verbose mode.
     */
    private String friendlyLabel(String label) {
        return switch (label) {
            case "java.expression:MethodCallExpr" -> "method chain";
            case "java.expression:ConditionalExpr" -> "ternary";
            case "java.expression:ObjectCreationExpr" -> "constructor call";
            case "java.expression:ArrayInitializerExpr" -> "array initializer";
            case "java.expression:LambdaExpr" -> "lambda";
            case "java.statement:IfStmt" -> "if statement";
            case "java.statement:ForStmt", "java.statement:ForEachStmt" -> "for loop";
            case "java.statement:WhileStmt" -> "while loop";
            case "java.statement:ReturnStmt" -> "return statement";
            case "java.statement:ExpressionStmt" -> "statement";
            default -> {
                int colon = label.indexOf(':');
                yield colon >= 0 ? label.substring(colon + 1) : label;
            }
        };
    }

    private void appendTree(StringBuilder out, DocExplanation explanation) {
        out.append(styler.style(Role.HEADING, "Decision tree"))
                .append('\n');
        List<Node> roots = explanation.tree().meaningful()
                ? List.of(explanation.tree())
                : explanation.tree().children();
        if (roots.isEmpty()) {
            out.append("  ").append(styler.style(Role.FADE, "(no groups)")).append('\n');
            return;
        }
        for (Node root : roots) {
            appendNode(out, root, "");
        }
    }

    private void appendNode(StringBuilder out, Node node, String indent) {
        out.append("  ").append(styler.style(Role.TREE, indent)).append(nodeLabel(node)).append('\n');
        for (Node child : node.children()) {
            if (carriesBreak(child) || (verbose && carriesDecision(child))) {
                appendNode(out, child, indent + "  ");
            }
        }
    }

    /**
     * Whether a subtree carries any group decision (flat or broken) worth showing. Verbose mode keeps every group, but
     * a label leaf that owns no group and no forced break (such as a bare {@code NameExpr}) is provenance with no layout
     * decision behind it, so it stays pruned even in verbose mode to keep the tree about decisions.
     */
    private boolean carriesDecision(Node node) {
        if (node.decision().isPresent() || node.forcedLineBreaks() > 0) {
            return true;
        }
        return node.children().stream().anyMatch(this::carriesDecision);
    }

    /**
     * Whether a subtree wrapped: it contains a broken group or a label that owns forced line breaks. In the default
     * (non-verbose) tree, branches that stayed entirely flat are pruned so the path to each wrap stands out; verbose
     * mode keeps every group.
     */
    private boolean carriesBreak(Node node) {
        if (node.decision().map(decision -> decision.decision() == Decision.BREAK).orElse(false)) {
            return true;
        }
        if (node.forcedLineBreaks() > 0) {
            return true;
        }
        return node.children().stream().anyMatch(this::carriesBreak);
    }

    private String nodeLabel(Node node) {
        if (node.decision().isPresent()) {
            GroupDecision decision = node.decision().get();
            String marker = decision.decision() == Decision.BREAK
                    ? styler.style(Role.BREAK, "BREAK")
                    : styler.style(Role.FLAT, "FLAT ");
            String math = decision.forcedBreak()
                    ? styler.style(Role.FADE, "forced")
                    : styler.style(
                            Role.FADE,
                            decision.flatWidth() + (decision.decision() == Decision.BREAK ? " > " : " <= ")
                                    + decision.available());
            return marker + " group " + math;
        }
        String label = styler.style(Role.LABEL, node.label().orElse("group"));
        if (node.forcedLineBreaks() > 0) {
            return label + "  "
                    + styler.style(Role.BREAK, "BREAK")
                    + styler.style(Role.FADE, " forced " + node.forcedLineBreaks());
        }
        return label;
    }

    private void appendLegend(StringBuilder out) {
        out.append(styler.style(Role.HEADING, "Legend")).append('\n');
        out.append("  ")
                .append("flat width N > W")
                .append("  the construct's one-line width exceeded the columns left, so it wrapped\n");
        out.append("  ")
                .append(styler.style(Role.LABEL, "method chain"))
                .append(", ")
                .append(styler.style(Role.LABEL, "ternary"))
                .append(", …  the construct that wrapped, named the way you read it\n");
        out.append("  ")
                .append(styler.style(Role.FADE, "laid out across lines by rule"))
                .append("  a formatter rule spans this construct over lines with no width measurement\n");
        out.append("  In the decision tree, ")
                .append(styler.style(Role.FLAT, "FLAT "))
                .append(" stayed on one line and ")
                .append(styler.style(Role.BREAK, "BREAK"))
                .append(" wrapped across lines.\n");
        if (!verbose) {
            out.append("  ")
                    .append(styler.style(
                            Role.FADE,
                            "Run with --verbose for raw rule labels and every group in the tree."))
                    .append('\n');
        }
    }
}
