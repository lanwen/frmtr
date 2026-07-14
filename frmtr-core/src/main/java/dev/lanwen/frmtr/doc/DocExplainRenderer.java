package dev.lanwen.frmtr.doc;

import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.DocExplanation.BestFittingDecision;
import dev.lanwen.frmtr.doc.DocExplanation.ConditionalGroupDecision;
import dev.lanwen.frmtr.doc.DocExplanation.Decision;
import dev.lanwen.frmtr.doc.DocExplanation.FillDecision;
import dev.lanwen.frmtr.doc.DocExplanation.ForcedBreak;
import dev.lanwen.frmtr.doc.DocExplanation.GroupDecision;
import dev.lanwen.frmtr.doc.DocExplanation.Node;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Traces the layout decisions the renderer makes and records the width arithmetic and forced breaks behind them.
 *
 * <p>This helper exists so developer-facing tooling can answer "why did this line wrap?" without re-deriving renderer
 * internals or guessing. It deliberately re-walks the document with the <em>same</em> fit logic as {@link DocRenderer}
 * (the shared {@link DocWidths} authority and the same column accounting), so a recorded decision always matches what
 * {@link DocRenderer#render(Doc)} actually emits. It captures both width-driven group breaks and the forced hard line
 * breaks a Java printer emitted as policy, attributing each forced break to the nearest enclosing rule label. It is an
 * observer: it never produces formatted output and never feeds back into rendering policy. Presentation of the recorded
 * decisions (text, color, depth limits) is left to callers via {@link DocExplanation}.
 */
public final class DocExplainRenderer {

    private final FormatterOptions options;

    private final int lineWidth;

    public DocExplainRenderer(FormatterOptions options) {
        this.options = options;
        this.lineWidth = options.lineWidth();
    }

    /**
     * Walks {@code doc} once and returns the labeled decision tree, the ordered group decisions, the forced breaks, and
     * the printer-recorded width decisions.
     *
     * <p>Decisions are reported in the order the renderer reaches each construct, so they read top-down like the
     * formatted source. Nested groups appear after their enclosing group because the renderer descends into the chosen
     * layout before reaching inner break opportunities. The {@code printerWraps} are passed through untouched: they
     * already carry the printers' own width arithmetic for constructs the renderer width-fits as forced breaks, and the
     * caller merges them with this trace.
     */
    public DocExplanation explain(Doc doc, List<dev.lanwen.frmtr.doc.PrinterWrap> printerWraps) {
        Trace trace = new Trace();
        Builder root = trace.render(doc, 0, Mode.BREAK, null);
        trace.flushLineSuffixes();
        return new DocExplanation(
            lineWidth,
            List.copyOf(trace.decisions),
            List.copyOf(trace.fillDecisions),
            List.copyOf(trace.conditionalGroupDecisions),
            List.copyOf(trace.bestFittingDecisions),
            List.copyOf(trace.forcedBreaks),
            List.copyOf(printerWraps),
            root.freeze()
        );
    }

    /**
     * Mirrors {@link DocRenderer}'s rendering walk while tracking the current column and nearest enclosing label, and
     * building a pruned tree of the labels and group decisions it passes through.
     *
     * <p>This is a per-render instance rather than static methods because the column cursor and the current label node
     * are mutable per walk: the cursor must advance exactly as {@link DocRenderer} would (otherwise "columns available"
     * drifts) and hard line breaks must accrue to whichever label node is currently in scope.
     */
    private final class Trace {

        private final List<GroupDecision> decisions = new ArrayList<>();

        private final List<FillDecision> fillDecisions = new ArrayList<>();

        private final List<ConditionalGroupDecision> conditionalGroupDecisions = new ArrayList<>();

        private final List<BestFittingDecision> bestFittingDecisions = new ArrayList<>();

        private final List<ForcedBreak> forcedBreaks = new ArrayList<>();

        private final DocWidths.Measurement widths = createWidths();

        /**
         * Current {@link Doc.BestFitting} nesting depth, mirroring {@link DocRenderer}'s so the trace ranks a nested
         * best-fitting node at the same depth the renderer does — and therefore records, and descends into, the same
         * winner. Incremented while walking a chosen alternative.
         */
        private int bestFittingDepth;

        private final List<BufferedSuffix> lineSuffixes = new ArrayList<>();

        /**
         * Mirrors {@link DocRenderer}'s per-render group-mode map so a named {@link Doc.IfBreak} resolves the same arm
         * here as in the real render; otherwise the replayed column cursor would drift from what the renderer emits.
         */
        private final Map<String, Mode> groupModes = new HashMap<>();

        private int column;

        /**
         * Builds this trace's width authority with the same indent-unit width the renderer uses, so ranking a
         * best-fitting node resets the simulated column after a newline exactly as {@link DocRenderer} does and the
         * recorded per-alternative line counts match the emitted layout.
         */
        private DocWidths.Measurement createWidths() {
            DocWidths.Measurement measurement = DocWidths.measurement();
            measurement.indentWidth(options.indentUnit().length());
            return measurement;
        }

        private Builder render(Doc doc, int indent, Mode mode, Builder enclosingLabel) {
            switch (doc) {
                case Doc.Text text -> {
                    advance(text.value());
                    return Builder.leaf();
                }
                case Doc.Concat concat -> {
                    Builder structural = Builder.structural();
                    for (Doc child : concat.docs()) {
                        structural.add(render(child, indent, mode, enclosingLabel));
                    }
                    return structural;
                }
                case Doc.Fill fill -> {
                    Builder structural = Builder.structural();
                    List<Doc> parts = fill.parts();
                    List<FillDecision.Separator> separatorDecisions = new ArrayList<>();
                    if (!parts.isEmpty()) {
                        structural.add(render(parts.getFirst(), indent, Mode.FLAT, enclosingLabel));
                        for (int i = 1; i + 1 < parts.size(); i += 2) {
                            Doc separator = parts.get(i);
                            Doc nextContent = parts.get(i + 1);
                            // Decide each separator through the shared DocWidths.separatorFitsFlat helper DocRenderer
                            // also calls, so the cursor advances identically. Record the arithmetic (column reached, flat
                            // width of concat(separator, nextContent), columns left) before the separator advances the
                            // cursor, so a recorded BREAK reflects the budget the renderer actually weighed.
                            int available = lineWidth - column;
                            int separatorFlatWidth = widths.flatWidth(Doc.concat(separator, nextContent));
                            boolean fitsFlat = widths.separatorFitsFlat(separator, nextContent, available);
                            Mode separatorMode = fitsFlat ? Mode.FLAT : Mode.BREAK;
                            separatorDecisions.add(new FillDecision.Separator(
                                i,
                                fitsFlat ? Decision.FLAT : Decision.BREAK,
                                separatorFlatWidth,
                                available,
                                column
                            ));
                            structural.add(render(separator, indent, separatorMode, enclosingLabel));
                            structural.add(render(nextContent, indent, Mode.FLAT, enclosingLabel));
                        }
                    }
                    if (!separatorDecisions.isEmpty()) {
                        Optional<String> label = enclosingLabel == null ? Optional.empty() : enclosingLabel.label;
                        fillDecisions.add(new FillDecision(label, separatorDecisions));
                    }
                    return structural;
                }
                case Doc.ConditionalGroup conditionalGroup -> {
                    // Mirror DocRenderer.renderConditionalGroup so the column cursor advances identically: probe each
                    // alternative with the shared fit authority and the same remaining width, render only the first that
                    // fits (flat) or the last as the break-mode fallback. Only the chosen alternative is walked, exactly
                    // as the renderer emits it, so the replayed cursor cannot drift.
                    Builder structural = Builder.structural();
                    List<Doc> alternatives = conditionalGroup.alternatives();
                    if (!alternatives.isEmpty()) {
                        int available = lineWidth - column;
                        int startColumn = column;
                        int chosen = alternatives.size() - 1;
                        Mode chosenMode = Mode.BREAK;
                        // Probe alternatives in order, recording each one's flat width and whether it fit, so the trace
                        // can show why earlier (narrower) alternatives were skipped. Probing stops at the first fit, so
                        // alternatives after the chosen one are never measured and are intentionally absent.
                        List<ConditionalGroupDecision.Alternative> probed = new ArrayList<>();
                        for (int i = 0; i < alternatives.size(); i++) {
                            boolean fits = widths.fits(alternatives.get(i), available);
                            probed.add(new ConditionalGroupDecision.Alternative(
                                i,
                                widths.flatWidth(alternatives.get(i)),
                                fits
                            ));
                            if (fits) {
                                chosen = i;
                                chosenMode = Mode.FLAT;
                                break;
                            }
                        }
                        conditionalGroupDecisions.add(new ConditionalGroupDecision(
                            enclosingLabel == null ? Optional.empty() : enclosingLabel.label,
                            chosen,
                            chosenMode == Mode.BREAK,
                            available,
                            startColumn,
                            probed
                        ));
                        structural.add(render(alternatives.get(chosen), indent, chosenMode, enclosingLabel));
                    }
                    return structural;
                }
                case Doc.BestFitting bestFitting -> {
                    // Mirror DocRenderer.renderBestFitting so the replayed cursor advances identically: rank via the same
                    // shared chooseBestFitting at the same depth, then walk only the winner in break mode. Record the
                    // decision with each measured alternative's line count and overflow — the exact numbers the ranking
                    // weighed — so --explain can show why the flatter alternatives lost.
                    Builder structural = Builder.structural();
                    List<Doc> alternatives = bestFitting.alternatives();
                    int[] priorities = bestFitting.priorities();
                    int available = lineWidth - column;
                    int startColumn = column;
                    int depth = bestFittingDepth;
                    int chosen = widths.chooseBestFitting(alternatives, priorities, indent, startColumn, lineWidth, depth);
                    // Only the alternatives the ranking measured (the first MAX_BEST_FITTING_ALTERNATIVES, and none at
                    // all past the depth bound where the node collapses to its first) are recorded, matching the winner
                    // selection exactly.
                    int measured = depth >= DocWidths.MAX_BEST_FITTING_DEPTH
                        ? 1
                        : Math.min(alternatives.size(), DocWidths.MAX_BEST_FITTING_ALTERNATIVES);
                    List<BestFittingDecision.Alternative> ranked = new ArrayList<>();
                    for (int i = 0; i < measured; i++) {
                        DocWidths.LineCount count =
                            widths.measureLineCount(alternatives.get(i), indent, startColumn, lineWidth, depth + 1);
                        // Record the priority alongside the measured line count so --explain can show why a higher-line
                        // alternative won: a fitting higher-priority arm outranks a fitting fewer-lines one.
                        ranked.add(new BestFittingDecision.Alternative(
                            i,
                            count.lines(),
                            count.overflow(),
                            priorities[i],
                            i == chosen
                        ));
                    }
                    bestFittingDecisions.add(new BestFittingDecision(
                        enclosingLabel == null ? Optional.empty() : enclosingLabel.label,
                        chosen,
                        available,
                        startColumn,
                        ranked
                    ));
                    bestFittingDepth = depth + 1;
                    structural.add(render(alternatives.get(chosen), indent, Mode.BREAK, enclosingLabel));
                    bestFittingDepth = depth;
                    return structural;
                }
                case Doc.Line ignored -> {
                    if (mode == Mode.FLAT) {
                        advance(" ");
                    } else {
                        newline(indent);
                    }
                    return Builder.leaf();
                }
                case Doc.SoftLine ignored -> {
                    if (mode == Mode.BREAK) {
                        newline(indent);
                    }
                    return Builder.leaf();
                }
                case Doc.HardLine ignored -> {
                    newline(indent);
                    if (enclosingLabel != null) {
                        enclosingLabel.forcedLineBreaks++;
                    }
                    return Builder.leaf();
                }
                case Doc.BreakParent ignored -> {
                    // Emits no newline and advances no column, so it leaves the cursor untouched. The break it forces
                    // is already reported on the enclosing Group decision: BreakParent measures as NO_FIT in DocWidths,
                    // so that group's flatWidth == NO_FIT and its decision is flagged as a forced break.
                    return Builder.leaf();
                }
                case Doc.Indent indented -> {
                    return render(indented.doc(), indent + 1, mode, enclosingLabel);
                }
                case Doc.Group group -> {
                    int available = lineWidth - column;
                    boolean fits = widths.fits(group.doc(), available);
                    int flatWidth = widths.flatWidth(group.doc());
                    Mode next = fits ? Mode.FLAT : Mode.BREAK;
                    if (group.groupId() != null) {
                        groupModes.put(group.groupId(), next);
                    }
                    Optional<String> label = enclosingLabel == null ? Optional.empty() : enclosingLabel.label;
                    GroupDecision decision = new GroupDecision(
                        label,
                        fits ? Decision.FLAT : Decision.BREAK,
                        flatWidth,
                        available,
                        column,
                        flatWidth == DocWidths.NO_FIT
                    );
                    decisions.add(decision);
                    Builder node = Builder.group(decision);
                    node.add(render(group.doc(), indent, next, enclosingLabel));
                    return node;
                }
                case Doc.IfBreak conditional -> {
                    // Resolve the arm exactly as DocRenderer does: by the named group's recorded mode when identified,
                    // otherwise by the ambient mode, so the replayed column advances identically.
                    Mode effective = conditional.groupId() == null
                        ? mode
                        : groupModes.getOrDefault(conditional.groupId(), Mode.FLAT);
                    return render(
                        effective == Mode.BREAK ? conditional.breakDoc() : conditional.flatDoc(),
                        indent,
                        mode,
                        enclosingLabel
                    );
                }
                case Doc.Label labeled -> {
                    Builder node = Builder.label(labeled.label());
                    node.add(render(labeled.doc(), indent, mode, node));
                    if (node.forcedLineBreaks > 0) {
                        forcedBreaks.add(new ForcedBreak(node.label, node.forcedLineBreaks));
                    }
                    return node;
                }
                case Doc.LineSuffix lineSuffix -> {
                    lineSuffixes.add(new BufferedSuffix(lineSuffix.content(), indent, mode, enclosingLabel));
                    return Builder.leaf();
                }
            }
        }

        /**
         * Replays buffered {@link Doc.LineSuffix} content at its captured indent/mode/label so the column cursor and
         * forced-break attribution stay identical to {@link DocRenderer}'s flush at the line break. Drains the buffer
         * until empty because a flushed suffix may itself buffer another.
         */
        private void flushLineSuffixes() {
            while (!lineSuffixes.isEmpty()) {
                List<BufferedSuffix> pending = List.copyOf(lineSuffixes);
                lineSuffixes.clear();
                for (BufferedSuffix suffix : pending) {
                    render(suffix.content(), suffix.indent(), suffix.mode(), suffix.enclosingLabel());
                }
            }
        }

        private void advance(String value) {
            int lastLineBreak = value.lastIndexOf('\n');
            if (lastLineBreak >= 0) {
                column = value.length() - lastLineBreak - 1;
            } else {
                column += value.length();
            }
        }

        private void newline(int indent) {
            // Flush buffered line suffixes before resetting the column, mirroring DocRenderer.newline so every break path
            // (Line, SoftLine, HardLine) replays suffix content on the line being closed rather than the next one.
            flushLineSuffixes();
            column = options.indentUnit().length() * indent;
        }
    }

    private enum Mode {
        FLAT,
        BREAK,
    }

    private record BufferedSuffix(Doc content, int indent, Mode mode, Builder enclosingLabel) {}

    /**
     * Mutable scratch node used while walking, so a label can accrue forced line breaks emitted under it before the
     * tree is frozen into the immutable {@link Node} the public model exposes. Pure structural wrappers are collapsed at
     * freeze time so the model keeps only labels and group decisions.
     */
    private static final class Builder {

        private final Optional<String> label;

        private final Optional<GroupDecision> decision;

        private final List<Builder> children = new ArrayList<>();

        private int forcedLineBreaks;

        private Builder(Optional<String> label, Optional<GroupDecision> decision) {
            this.label = label;
            this.decision = decision;
        }

        private static Builder leaf() {
            return new Builder(Optional.empty(), Optional.empty());
        }

        private static Builder structural() {
            return new Builder(Optional.empty(), Optional.empty());
        }

        private static Builder label(String label) {
            return new Builder(Optional.of(label), Optional.empty());
        }

        private static Builder group(GroupDecision decision) {
            return new Builder(Optional.empty(), Optional.of(decision));
        }

        private boolean meaningful() {
            return label.isPresent() || decision.isPresent();
        }

        private void add(Builder child) {
            children.add(child);
        }

        private Node freeze() {
            return new Node(label, decision, forcedLineBreaks, prunedChildren());
        }

        /**
         * Collapses pure structural wrappers by lifting their meaningful descendants, so the frozen tree retains only
         * labels and group decisions and stays focused on rule provenance and break opportunities.
         */
        private List<Node> prunedChildren() {
            List<Node> kept = new ArrayList<>();
            for (Builder child : children) {
                if (child.meaningful()) {
                    kept.add(child.freeze());
                } else {
                    kept.addAll(child.prunedChildren());
                }
            }
            return kept;
        }
    }
}
