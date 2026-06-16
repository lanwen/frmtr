package dev.lanwen.frmtr.doc;

import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.DocExplanation.Decision;
import dev.lanwen.frmtr.doc.DocExplanation.ForcedBreak;
import dev.lanwen.frmtr.doc.DocExplanation.GroupDecision;
import dev.lanwen.frmtr.doc.DocExplanation.Node;
import java.util.ArrayList;
import java.util.List;
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
        return new DocExplanation(
            lineWidth,
            List.copyOf(trace.decisions),
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

        private final List<ForcedBreak> forcedBreaks = new ArrayList<>();

        private final DocWidths.Measurement widths = DocWidths.measurement();

        private int column;

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
                case Doc.Line _ -> {
                    if (mode == Mode.FLAT) {
                        advance(" ");
                    } else {
                        newline(indent);
                    }
                    return Builder.leaf();
                }
                case Doc.SoftLine _ -> {
                    if (mode == Mode.BREAK) {
                        newline(indent);
                    }
                    return Builder.leaf();
                }
                case Doc.HardLine _ -> {
                    newline(indent);
                    if (enclosingLabel != null) {
                        enclosingLabel.forcedLineBreaks++;
                    }
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
                    return render(
                        mode == Mode.BREAK ? conditional.breakDoc() : conditional.flatDoc(),
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
            column = options.indentUnit().length() * indent;
        }
    }

    private enum Mode {
        FLAT,
        BREAK,
    }

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
