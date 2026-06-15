package dev.lanwen.frmtr.doc;

import java.util.List;
import java.util.Optional;

/**
 * The result of tracing a document render: the line width decisions were measured against, the group fit decisions, the
 * forced line breaks, and a pruned tree of the formatter rule labels and break decisions the render passed through.
 *
 * <p>This is the public, presentation-free model that {@link DocExplainRenderer} produces and developer tooling (such as
 * the CLI {@code --explain} mode) renders. It owns the data, not its formatting: it intentionally leaves color, depth
 * limits, and layout to the caller so the same explanation can be printed plainly into a bug report or colorized for a
 * terminal.
 *
 * <p>It captures the ways this formatter wraps a line. Width-driven wraps the renderer decided are {@link GroupDecision}s:
 * the renderer measured a {@link Doc.Group} and broke it because its flat width exceeded the columns left. Many
 * constructs developers debug (method chains, argument lists, ternaries, control conditions) are instead width-measured
 * by their Java printer and emitted as hard line breaks, so the renderer only sees a {@link ForcedBreak} and cannot
 * report the arithmetic. For those, the printers record their own decision as a {@link PrinterWrap}, which carries the
 * real flat width, budget, and a friendly construct name. Reporting all three is what lets the explanation answer "why
 * did this line wrap?" with true width arithmetic for the constructs that actually wrap on real Java.
 */
public record DocExplanation(
    int lineWidth,
    List<GroupDecision> decisions,
    List<ForcedBreak> forcedBreaks,
    List<PrinterWrap> printerWraps,
    Node tree
) {
    public DocExplanation {
        decisions = List.copyOf(decisions);
        forcedBreaks = List.copyOf(forcedBreaks);
        printerWraps = List.copyOf(printerWraps);
    }

    /**
     * Returns the set of {@code java.*:} rule labels a printer attributed a width break to, so the renderer-trace
     * forced breaks for the same labels can be suppressed and each wrap reported once with its real arithmetic.
     */
    public java.util.Set<String> printerWrapLabels() {
        return printerWraps.stream().map(PrinterWrap::label).collect(java.util.stream.Collectors.toSet());
    }

    /**
     * Returns only the groups that broke across lines, in render order. These are the decisions a developer debugging
     * an unexpected wrap usually cares about first.
     */
    public List<GroupDecision> brokenGroups() {
        return decisions.stream().filter(decision -> decision.decision() == Decision.BREAK).toList();
    }

    /**
     * Whether a group rendered flat (on one line) or broke across lines.
     */
    public enum Decision {
        FLAT,
        BREAK,
    }

    /**
     * Strips the {@code java.*:} provenance prefix from a label, leaving the local rule name. Returns {@code group} for
     * an unlabeled construct.
     */
    static String ruleName(Optional<String> label) {
        return label
                .map(name -> {
                    int colon = name.indexOf(':');
                    return colon >= 0 ? name.substring(colon + 1) : name;
                })
                .orElse("group");
    }

    /**
     * One renderer group decision and the arithmetic that produced it.
     *
     * <p>The label is the nearest enclosing {@link Doc.Label}, which carries formatter rule provenance such as
     * {@code java.expression:MethodCallExpr}; it is absent for unlabeled structural groups. {@code flatWidth} is the
     * single-line width the group would occupy, {@code available} is the columns left on the current line when the
     * renderer reached the group, and {@code startColumn} is where that group began. The overall line width lives on
     * {@link DocExplanation#lineWidth()}, not here, since it is the same for every decision. {@code forcedBreak} marks
     * groups that contain a hard line break and therefore can never fit flat regardless of width.
     */
    public record GroupDecision(
        Optional<String> label,
        Decision decision,
        int flatWidth,
        int available,
        int startColumn,
        boolean forcedBreak
    ) {
        /**
         * Returns the label's local rule name without the {@code java.*:} provenance prefix, or {@code group} for an
         * unlabeled structural group.
         */
        public String ruleName() {
            return DocExplanation.ruleName(label);
        }
    }

    /**
     * A run of hard line breaks a formatter rule emitted directly, independent of width fitting.
     *
     * <p>These come from a Java printer that pre-decided a construct must span lines (for example a method chain it
     * measured as too wide to keep on one line) and laid it out with {@link Doc.HardLine}s rather than a width-fitted
     * group. {@code label} is the rule that owns those breaks and {@code count} is how many hard breaks it emitted in
     * the rendered layout. They are policy decisions, so there is no per-break width arithmetic to report.
     */
    public record ForcedBreak(Optional<String> label, int count) {
        public String ruleName() {
            return DocExplanation.ruleName(label);
        }
    }

    /**
     * A node in the pruned explanation tree: either a formatter rule label, a group carrying a decision, or a structural
     * root holding children. Pure structural wrappers (concat, text, lines) are collapsed so callers see only formatter
     * rule labels and the break decisions they own. {@code forcedLineBreaks} counts the hard line breaks a label node
     * owns directly; it is zero for group and structural nodes.
     */
    public record Node(
        Optional<String> label,
        Optional<GroupDecision> decision,
        int forcedLineBreaks,
        List<Node> children
    ) {
        public Node {
            children = List.copyOf(children);
        }

        /**
         * Whether this node carries information worth printing (a label, a group decision, or owned forced breaks) as
         * opposed to being a collapsed structural root.
         */
        public boolean meaningful() {
            return label.isPresent() || decision.isPresent();
        }

        /**
         * Returns the label's local rule name without the {@code java.*:} provenance prefix.
         */
        public String ruleName() {
            return DocExplanation.ruleName(label);
        }
    }
}
