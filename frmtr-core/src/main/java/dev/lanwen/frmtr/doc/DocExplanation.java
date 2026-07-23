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
 * the renderer measured a {@link Doc.Group} and broke it because its flat width exceeded the columns left. The greedy
 * per-separator choices of a {@link Doc.Fill} are {@link FillDecision}s, and the alternative the renderer picked for a
 * {@link Doc.ConditionalGroup} is a {@link ConditionalGroupDecision}; both carry the same kind of width arithmetic so
 * those constructs can explain their layout too. Many
 * constructs developers debug (method chains, argument lists, ternaries, control conditions) are instead width-measured
 * by their Java printer and emitted as hard line breaks, so the renderer only sees a {@link ForcedBreak} and cannot
 * report the arithmetic. For those, the printers record their own decision as a {@link PrinterWrap}, which carries the
 * real flat width, budget, and a friendly construct name. Reporting all three is what lets the explanation answer "why
 * did this line wrap?" with true width arithmetic for the constructs that actually wrap on real Java.
 */
public record DocExplanation(
    int lineWidth,
    List<GroupDecision> decisions,
    List<FillDecision> fillDecisions,
    List<ConditionalGroupDecision> conditionalGroupDecisions,
    List<BestFittingDecision> bestFittingDecisions,
    List<ForcedBreak> forcedBreaks,
    List<PrinterWrap> printerWraps,
    Node tree
) {
    public DocExplanation {
        decisions = List.copyOf(decisions);
        fillDecisions = List.copyOf(fillDecisions);
        conditionalGroupDecisions = List.copyOf(conditionalGroupDecisions);
        bestFittingDecisions = List.copyOf(bestFittingDecisions);
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
     * Returns only the fills that broke at least one separator across lines, in render order. These are the fill layouts
     * a developer debugging an unexpected wrap usually cares about.
     */
    public List<FillDecision> brokenFills() {
        return fillDecisions.stream().filter(FillDecision::anyBroke).toList();
    }

    /**
     * Returns only the best-fitting nodes whose chosen alternative wraps across lines, in render order. A best-fitting
     * node whose flattest alternative fit on one line is not a wrap and is excluded, like a flat group.
     */
    public List<BestFittingDecision> rankedBestFittings() {
        return bestFittingDecisions.stream().filter(BestFittingDecision::chosenWraps).toList();
    }

    /**
     * Whether a group rendered flat (on one line) or broke across lines.
     */
    public enum Decision {
        FLAT,
        BREAK,
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
    }

    /**
     * A {@link Doc.Fill}'s greedy per-separator layout, with the width arithmetic behind each separator's FLAT/BREAK
     * choice.
     *
     * <p>A fill packs an alternating {@code [content, separator, …]} list greedily: it keeps each separator flat while
     * the separator together with the content that follows it still fits the columns left, and breaks only that one
     * separator otherwise. Because every separator is decided independently against the column it reaches, a fill is one
     * construct holding several sub-decisions, modelled here as a list of {@link Separator} entries rather than as N
     * unrelated decisions. {@code label} is the nearest enclosing {@link Doc.Label} rule provenance, absent for an
     * unlabeled structural fill.
     */
    public record FillDecision(Optional<String> label, List<Separator> separators) {
        public FillDecision {
            separators = List.copyOf(separators);
        }

        /**
         * Whether any separator in this fill broke across lines.
         */
        public boolean anyBroke() {
            return separators.stream().anyMatch(separator -> separator.decision() == Decision.BREAK);
        }

        /**
         * One separator in a {@link Doc.Fill} and the arithmetic that decided it.
         *
         * <p>{@code index} is the separator's position in the fill's part list (separators sit at odd indices).
         * {@code flatWidth} is the single-line width of the separator plus the content that immediately follows it — the
         * span the fill measures to decide whether to keep this separator flat — and {@code available} is the columns
         * left ({@code lineWidth - startColumn}) when the renderer reached it, having advanced past the preceding
         * content from {@code startColumn}. The separator stays {@link Decision#FLAT} when {@code flatWidth} fits
         * {@code available} and {@link Decision#BREAK} otherwise.
         */
        public record Separator(
            int index,
            Decision decision,
            int flatWidth,
            int available,
            int startColumn
        ) {}
    }

    /**
     * A {@link Doc.ConditionalGroup}'s alternative selection and the fit measurements behind it.
     *
     * <p>A conditional group holds an ordered list of layout alternatives, most-flat first, and renders the first whose
     * flat layout fits the columns left; if none fit, it falls back to the last alternative laid out in break mode.
     * {@code chosenIndex} is the selected alternative and {@code chosenInBreakMode} distinguishes the two reasons: false
     * when an earlier alternative fit flat, true when nothing fit and the last alternative was taken as the break-mode
     * fallback. Each probed {@link Alternative} carries its own flat width and whether it fit, so the explanation can
     * show why earlier (narrower) alternatives were skipped. {@code label} is the nearest enclosing rule provenance,
     * absent for an unlabeled structural conditional group.
     */
    public record ConditionalGroupDecision(
        Optional<String> label,
        int chosenIndex,
        boolean chosenInBreakMode,
        int available,
        int startColumn,
        List<Alternative> alternatives
    ) {
        public ConditionalGroupDecision {
            alternatives = List.copyOf(alternatives);
        }

        /**
         * One probed alternative in a {@link Doc.ConditionalGroup}.
         *
         * <p>{@code index} is its position in the alternative list, {@code flatWidth} its single-line width (or
         * {@link DocWidths#NO_FIT} when it contains a forced break), and {@code fits} whether that flat width fit the
         * columns left. Probing stops at the first alternative that fits, so alternatives after the chosen one are not
         * measured and do not appear here.
         */
        public record Alternative(int index, int flatWidth, boolean fits) {}
    }

    /**
     * A {@link Doc.BestFitting}'s ranked-alternative selection and the line-count arithmetic behind it.
     *
     * <p>A best-fitting node holds an ordered list of layout alternatives, flattest-first, and — unlike a
     * {@link ConditionalGroupDecision}, which picks the first flat layout that fits — keeps the alternative that
     * <em>minimizes rendered line count</em> at the current column, so it can rank multiple broken shapes against each
     * other. {@code chosenIndex} is the selected alternative; each measured {@link Alternative} carries the line count
     * and overflow the ranking computed for it, so the explanation can show why the flatter alternatives lost.
     * {@code available} is the columns left and {@code startColumn} where the node began. Only the first
     * {@link DocWidths#MAX_BEST_FITTING_ALTERNATIVES} alternatives are measured, so the measured list may be shorter
     * than the node's full alternative list. {@code label} is the nearest enclosing rule provenance, absent for an
     * unlabeled structural best-fitting node.
     */
    public record BestFittingDecision(
        Optional<String> label,
        int chosenIndex,
        int available,
        int startColumn,
        List<Alternative> alternatives
    ) {
        public BestFittingDecision {
            alternatives = List.copyOf(alternatives);
        }

        /**
         * Whether the chosen alternative wraps across lines. When the flattest layout fits on one line it is chosen and
         * this is false; when every measured alternative wraps, the least-wrapping one is chosen and this is true. The
         * CLI surfaces only wrapping best-fitting nodes as a wrap reason.
         */
        public boolean chosenWraps() {
            return alternatives.stream()
                    .filter(alternative -> alternative.index() == chosenIndex)
                    .anyMatch(alternative -> alternative.lines() > 0);
        }

        /**
         * One measured alternative in a {@link Doc.BestFitting}.
         *
         * <p>{@code index} is its position in the alternative list, {@code lines} the number of newlines it would render
         * into at the node's start column, {@code overflow} the total columns past the line width it would incur, and
         * {@code priority} the per-alternative preference weight (higher wins among fitting candidates). The ranking keeps
         * a fitting alternative (zero overflow) over any overflowing one first; then, among fitting candidates, a strictly
         * higher {@code priority}; then within equal fit-and-priority the fewest lines, then the least overflow, then the
         * earliest index. Recording {@code priority} lets {@code --explain} show why a higher-line alternative won when a
         * caller set a preference (without it the report would show a line count that disagrees with the choice).
         * {@code chosen} marks the winner.
         */
        public record Alternative(int index, int lines, int overflow, int priority, boolean chosen) {}
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
    }
}
