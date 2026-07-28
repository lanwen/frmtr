package dev.lanwen.frmtr.doc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public sealed interface Doc
    permits
        Doc.BestFitting,
        Doc.BreakParent,
        Doc.Concat,
        Doc.ConditionalGroup,
        Doc.Fill,
        Doc.Group,
        Doc.HardLine,
        Doc.IfBreak,
        Doc.Indent,
        Doc.Label,
        Doc.Line,
        Doc.LineSuffix,
        Doc.SoftLine,
        Doc.Text {
    Doc EMPTY = new Text("");

    Doc LINE = new Line();

    Doc SOFT_LINE = new SoftLine();

    Doc HARD_LINE = new HardLine();

    /**
     * Zero-width marker that forces the nearest enclosing {@link Group} into break mode without printing anything
     * itself. It is the explicit form of today's "emit a {@link HardLine} to poison the fit measurement" trick: a
     * group that measures this marker can never stay flat, but unlike {@code HARD_LINE} the marker emits no newline.
     *
     * <p>Because this single-pass renderer decides each group's mode top-down via its flat measurement, a
     * {@code BREAK_PARENT} only affects groups whose measurement <em>encounters</em> it; it does not retroactively
     * break a sibling group whose mode was already chosen. Emit it at the point the breaking child is built.
     */
    Doc BREAK_PARENT = new BreakParent();

    static Doc text(String value) {
        return value.isEmpty() ? EMPTY : new Text(value);
    }

    static Doc concat(Doc... docs) {
        return concat(Arrays.asList(docs));
    }

    static Doc concat(List<Doc> docs) {
        List<Doc> flat = new ArrayList<>();
        flattenConcat(docs, flat);
        if (flat.isEmpty()) {
            return EMPTY;
        }
        if (flat.size() == 1) {
            return flat.getFirst();
        }
        return new Concat(List.copyOf(flat));
    }

    static Doc join(Doc separator, List<Doc> docs) {
        if (docs.isEmpty()) {
            return EMPTY;
        }
        List<Doc> joined = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            if (i > 0) {
                joined.add(separator);
            }
            joined.add(docs.get(i));
        }
        return concat(joined);
    }

    /**
     * Joins documents with the formatter's standard comma-plus-line separator.
     */
    static Doc joinComma(List<Doc> docs) {
        return join(concat(text(","), LINE), docs);
    }

    static Doc group(Doc doc) {
        return new Group(doc, null);
    }

    /**
     * Builds a group with a stable identity so a dependent {@link IfBreak} can read this group's chosen mode by name
     * instead of the ambient mode. The id has no effect on this group's own layout; it only labels the group so a later
     * {@code ifBreak(..., groupId)} renders according to whether <em>this</em> group rendered flat or broken. The
     * identified group must render before any {@code IfBreak} that targets it (the renderer reads the mode the group
     * already recorded), which printers arrange by emitting the opener group ahead of its dependent closer.
     */
    static Doc group(Doc doc, String groupId) {
        return new Group(doc, groupId);
    }

    static Doc indent(Doc doc) {
        return new Indent(doc);
    }

    static Doc ifBreak(Doc breakDoc, Doc flatDoc) {
        return new IfBreak(breakDoc, flatDoc, null);
    }

    /**
     * Selects {@code breakDoc} or {@code flatDoc} based on the mode of the named group rather than the ambient
     * surrounding group. The group identified by {@code groupId} must have rendered before this node so its mode is
     * already recorded; this lets a closing delimiter follow the break/flat decision of its matching opener group even
     * when the two sit in different ambient groups.
     */
    static Doc ifBreak(Doc breakDoc, Doc flatDoc, String groupId) {
        return new IfBreak(breakDoc, flatDoc, groupId);
    }

    /**
     * Rewrites {@code doc} into the layout it would take if every break decision inside it came out flat, so a ranked
     * candidate set can offer a <em>genuinely single-line</em> arm beside the same content's breakable form. Without it a
     * cascade cannot express "prefer this shape only while it stays on one line": the preferred arm re-breaks internally,
     * its first line shrinks to fit, and both fewest-lines and first-line-fit ranking stop discriminating.
     *
     * <p>Break <em>requests</em> are denied — {@link Group} loses its flat/break choice, {@link Line} becomes a space,
     * {@link SoftLine} and {@link #BREAK_PARENT} vanish, {@link IfBreak} takes its flat branch, and a
     * {@link ConditionalGroup}/{@link BestFitting} collapses to its first (flattest) alternative. Mandatory newlines are
     * <em>content</em> and survive: a {@link HardLine} still breaks, so a subtree carrying one is not a usable flat
     * candidate and the caller gates it out ({@link DocRenderer#containsHardLine} answers that question).
     *
     * <p>The result shares the original's text and line-suffix leaves, so a comment claimed once at build time is offered
     * to both arms of a ranked pair without being claimed twice; only one arm is ever rendered.
     */
    static Doc flat(Doc doc) {
        return switch (doc) {
            case Line ignored -> text(" ");
            case SoftLine ignored -> EMPTY;
            case BreakParent ignored -> EMPTY;
            case Concat concat -> concat(concat.docs().stream().map(Doc::flat).toList());
            case Fill fill -> concat(fill.parts().stream().map(Doc::flat).toList());
            case Group group -> flat(group.doc());
            case Indent indented -> indent(flat(indented.doc()));
            case IfBreak conditional -> flat(conditional.flatDoc());
            case ConditionalGroup conditionalGroup -> flat(conditionalGroup.alternatives().getFirst());
            case BestFitting bestFitting -> flat(bestFitting.alternatives().getFirst());
            case Label label -> label(label.label(), flat(label.doc()));
            case Text ignored -> doc;
            case HardLine ignored -> doc;
            case LineSuffix ignored -> doc;
        };
    }

    /**
     * The pinned-flat arm a ranked candidate set can offer for {@code doc}, or empty when the content carries a mandatory
     * newline and no single-line arm exists. The gated form of {@link #flat(Doc)}: a caller that would rank a flat shape
     * against a broken one asks here, so an unflattenable subtree drops out of the ranking instead of entering it as a
     * multi-line arm that then wins on priority.
     */
    static Optional<Doc> flatCandidate(Doc doc) {
        Doc flattened = flat(doc);
        return DocRenderer.containsHardLine(flattened) ? Optional.empty() : Optional.of(flattened);
    }

    /**
     * Emits {@code doc} only when the surrounding group renders in break mode.
     */
    static Doc breakOnly(Doc doc) {
        return ifBreak(doc, EMPTY);
    }

    /**
     * Emits {@code doc} only when the surrounding group renders flat.
     */
    static Doc flatOnly(Doc doc) {
        return ifBreak(EMPTY, doc);
    }

    /**
     * Builds the grouped soft-line delimiter envelope used by list-like documents.
     */
    static Doc delimited(String open, String close, Doc content) {
        return group(concat(text(open), group(indent(concat(SOFT_LINE, content))), SOFT_LINE, text(close)));
    }

    static Doc label(String label, Doc doc) {
        return new Label(label, doc);
    }

    /**
     * Packs an alternating {@code [content, separator, content, separator, …]} list, laying out each separator flat when
     * the next content still fits on the current line and breaking only at the separators where it does not. Unlike a
     * {@link Group}, which is all-or-nothing, the fit decision is made independently per separator, so a fill keeps as
     * many items on a line as fit and wraps only where needed — the layout array elements, argument lists, and similar
     * sequences want.
     *
     * <p>The list alternates content and separator starting and ending with content, so a well-formed list is empty or
     * has odd length; an empty list renders nothing and a single-element list renders just that content. Choosing the
     * separators (a comma plus {@link #LINE}, a bare {@link #LINE}, etc.) and where break-vs-flat should glue is left to
     * the caller, because a fill only decides whether each supplied separator lands flat or broken — it does not invent
     * separators of its own.
     *
     * <p>A non-empty even-length list is rejected: it ends with a trailing separator that the renderer's pairwise walk
     * never reaches, so the separator would be silently dropped.
     * The factory fails fast rather than emit output that quietly differs from the list handed in.
     *
     * @throws IllegalArgumentException if {@code parts} is non-empty with an even number of elements (a trailing
     *     separator with no following content)
     */
    static Doc fill(List<Doc> parts) {
        if (!parts.isEmpty() && parts.size() % 2 == 0) {
            throw new IllegalArgumentException(
                "fill parts must alternate [content, separator, content, …] (empty or odd length), but got an "
                    + "even-length list of size " + parts.size() + " ending with a trailing separator"
            );
        }
        return new Fill(List.copyOf(parts));
    }

    /**
     * Builds a Prettier-style conditional group: an ordered list of layout alternatives where the renderer selects the
     * <em>first alternative whose flat layout fits</em> the space left on the current line and renders it flat, falling
     * back to the <em>last</em> alternative rendered in break mode when none fit. The order encodes preference, and the
     * caller owns supplying alternatives and guaranteeing the final one is a layout that always works at any width; the
     * renderer only chooses among what it is given and never invents a new layout.
     *
     * <p>Because every non-last alternative is selected purely by flat fit, <strong>only the last alternative may be a
     * broken/multi-line layout.</strong> A non-last alternative that contains a forced break ({@link HardLine} or
     * {@link #BREAK_PARENT}) can never fit flat, so the renderer always skips it: such an alternative is dead. The first
     * alternative must additionally be the <em>narrowest</em> flat layout, because an enclosing {@link Group} sizes the
     * whole conditional group by its first alternative (see {@link DocWidths}); reporting the first alternative's width is
     * a safe over-estimate for the group only when no later alternative is narrower.
     *
     * <p>This invariant is documented rather than asserted here: detecting "contains a forced break" in this factory
     * would require calling into {@link DocWidths}/{@code DocRenderer}, inverting the {@code Doc} → renderer layering
     * into a circular dependency. The renderer naturally renders a malformed group as if the dead alternative were
     * absent, so the cost of violating the invariant is a silently-ignored alternative, not incorrect output.
     *
     * <p>A singleton list is valid and degenerate: with one alternative there is nothing to choose, so it is simply an
     * unconditional fallback rendered flat when it fits and broken otherwise (identical to wrapping that one layout in a
     * {@link #group(Doc)}). An empty list is rejected: "render nothing" is never a valid layout-choice intent, and an
     * empty conditional group is almost always a construction bug; use {@link #EMPTY} to render nothing deliberately.
     *
     * <p>This does <em>not</em> subsume the predicate-gated {@code Optional<Doc>} layout dispatch that printers such as
     * {@code MethodCallChainPrinter} hand-roll: that chain selects on source/structural predicates and ranks multiple
     * <em>broken</em> layouts, whereas a conditional group ranks N flat candidates plus one final broken fallback and
     * chooses purely by flat fit at the actual output column. For width purposes an enclosing group measures a
     * conditional group by its first (most-flat) alternative, the same representative-width convention Prettier uses.
     *
     * @throws IllegalArgumentException if {@code alternatives} is empty
     */
    static Doc conditionalGroup(List<Doc> alternatives) {
        return new ConditionalGroup(alternatives);
    }

    /**
     * Builds a ranked-broken-layout node: an ordered list of layout alternatives from which the renderer selects the one
     * that minimizes <em>rendered line count</em> (with a deterministic tie-break) at the current output column, rather
     * than the first that fits flat. It is the capability {@link #conditionalGroup(List) conditionalGroup} structurally
     * lacks — the layout-decision-model's rule B8: a conditional group offers N flat candidates plus exactly one final
     * broken fallback and chooses purely by flat fit, so it can never <em>rank two broken shapes against each other</em>;
     * a best-fitting node can. A construct with several acceptable broken shapes (a method chain's all-flat /
     * break-last-call / fan-out, say) emits them here and lets the renderer keep the one that wraps the least in this
     * context.
     *
     * <p>The alternatives are ordered <strong>flattest-first</strong>: the earliest alternative is the layout that wraps
     * least when everything fits, and later alternatives progressively concede to breaks. Unlike a conditional group,
     * <strong>a non-first alternative MAY contain a forced break</strong> ({@link HardLine} or {@link #BREAK_PARENT}) —
     * that is the whole point, since ranking broken shapes is exactly what this node is for. The <strong>last
     * alternative must be renderable at any width</strong> (the always-valid fallback), because when even it overflows it
     * is still the layout the renderer falls back to. An enclosing {@link Group} sizes a best-fitting node by its first
     * (flattest) alternative — the representative-width convention {@link ConditionalGroup} and Prettier both use — so
     * the first alternative should be the narrowest flat layout.
     *
     * <p>The "a non-first alternative may contain a forced break" allowance is <em>not</em> asserted here, mirroring the
     * reasoning on {@link #conditionalGroup(List)}: detecting a forced break would require calling into
     * {@link DocWidths}/{@code DocRenderer}, inverting the {@code Doc} → renderer layering into a circular dependency.
     * Where a conditional group leaves the inverse invariant ("no forced break before the last alternative") unasserted,
     * this node leaves the freedom unconstrained; either way the renderer decides layout, not the factory.
     *
     * <p>A singleton list is valid and degenerate — with one alternative there is nothing to rank, so it renders like
     * that lone layout. An empty list is rejected: "render nothing" is never a valid layout-choice intent; use
     * {@link #EMPTY} to render nothing deliberately.
     *
     * @throws IllegalArgumentException if {@code alternatives} is empty
     */
    static Doc bestFitting(List<Doc> alternatives) {
        return new BestFitting(alternatives, new int[0], false);
    }

    /**
     * Builds a best-fitting node whose alternatives all carry the same over-width hard-break body, so none {@code fits}
     * and fewest-lines would otherwise keep the arm whose opener spills. It ranks first-line fit first — an arm whose
     * first rendered line stays within the width beats one whose first line overruns — then, when the first lines tie
     * (the root broke internally so the collision lands on a later seam line), less total overflow before fewer lines,
     * so the shape that splits the seam wins over the hug that saves a line by colliding the root with the selector.
     *
     * <p>{@code rankFirstLineFirst} is a static per-node ranking-mode fact, the same category as {@code priorities} — not
     * a measured width; the ranking stays a pure function of the AST plus the rendered column. All other {@code bestFitting}
     * semantics are unchanged; see {@link #bestFitting(List)} for the full contract.
     *
     * @throws IllegalArgumentException if {@code alternatives} is empty
     */
    static Doc bestFittingFirstLine(List<Doc> alternatives) {
        return new BestFitting(alternatives, new int[0], true);
    }

    /**
     * Combines first-line-fit ranking with per-alternative {@code priorities}: the first line's fit leads, then overall
     * fit, then among fitting arms the higher priority. Lets a caller rank by header fit yet still pin which shape wins
     * when several fit. See {@link #bestFittingFirstLine(List)} and {@link #bestFitting(List, int[])} for each half.
     *
     * @throws IllegalArgumentException if {@code alternatives} is empty, or if {@code priorities} is non-empty and its
     *     length does not equal the number of alternatives
     */
    static Doc bestFittingFirstLine(List<Doc> alternatives, int[] priorities) {
        return new BestFitting(alternatives, priorities, true);
    }

    /**
     * Builds a ranked-broken-layout node whose alternatives carry per-alternative <em>priorities</em>, giving a caller a
     * way to say "prefer this shape over that one even though it uses more lines". The
     * priority is a secondary ranking key placed <strong>after the fit gate and before line count</strong>: among the
     * alternatives that fit, the highest-priority one wins regardless of how many lines it uses; a fitting alternative
     * still beats any overflowing one whatever its priority (priority never rescues an overflowing arm), and equal
     * priority reduces to the ordinary fewest-lines-then-least-overflow order of {@link #bestFitting(List)}. This is what
     * lets an opener-attached layout be preferred over a fewer-lines collapse when both fit, without letting a
     * high-priority arm produce over-width output.
     *
     * <p>{@code priorities} is a parallel vector, one entry per alternative in the same order; a higher integer is
     * preferred. An <em>empty</em> array is the "no preference" default and is treated as all-zero, which makes this
     * factory identical to {@link #bestFitting(List)} (equal priority ⇒ today's metric). A non-empty array must have
     * exactly one entry per alternative.
     *
     * <p>All other {@code bestFitting} semantics are unchanged: alternatives are ordered flattest-first, a non-first
     * alternative may contain a forced break, the last must be renderable at any width, and an enclosing group sizes the
     * node by its first alternative. See {@link #bestFitting(List)} for the full contract.
     *
     * @throws IllegalArgumentException if {@code alternatives} is empty, or if {@code priorities} is non-empty and its
     *     length does not equal the number of alternatives
     */
    static Doc bestFitting(List<Doc> alternatives, int[] priorities) {
        return new BestFitting(alternatives, priorities, false);
    }

    /**
     * Defers {@code content} to the end of the current line: it renders nothing at this position and flushes just before
     * the next line break (or at end of document). Used for trailing comments so the code preceding them is laid out and
     * width-measured as if the comment were absent — the comment can never push that code over the line width or change
     * which separator prints first.
     *
     * <p>Content is single-line only; a {@link HardLine} inside it is rejected at render time.
     */
    static Doc lineSuffix(Doc content) {
        return new LineSuffix(content);
    }

    private static void flattenConcat(List<Doc> docs, List<Doc> out) {
        for (Doc doc : docs) {
            if (doc == EMPTY) {
                continue;
            }
            if (doc instanceof Concat(List<Doc> concat)) {
                flattenConcat(concat, out);
            } else {
                out.add(doc);
            }
        }
    }

    record Text(String value) implements Doc {}

    record Concat(List<Doc> docs) implements Doc {}

    /** Alternating {@code [content, separator, content, …]} laid out by greedy per-separator packing; see {@link #fill}. */
    record Fill(List<Doc> parts) implements Doc {}

    /** Ordered layout alternatives; the first that fits wins, the last is the fallback. See {@link #conditionalGroup}. */
    record ConditionalGroup(List<Doc> alternatives) implements Doc {
        /**
         * Rejects an empty alternative list and defensively copies the rest, so the "at least one alternative"
         * invariant holds for every {@code ConditionalGroup} no matter how it is constructed — including a direct
         * in-package {@code new ConditionalGroup(...)} that bypasses the {@link #conditionalGroup(List)} factory.
         * "Render nothing" is never a valid layout-choice intent; use {@link #EMPTY} to render nothing deliberately.
         */
        public ConditionalGroup {
            if (alternatives.isEmpty()) {
                throw new IllegalArgumentException(
                    "conditionalGroup requires at least one alternative; 'render nothing' is not a valid layout choice"
                );
            }
            alternatives = List.copyOf(alternatives);
        }
    }

    /**
     * Ranked broken-layout alternatives; the renderer keeps the one that fits, then (among fitting candidates) has the
     * highest {@code priority}, then minimizes line count. See {@link #bestFitting(List)} / {@link #bestFitting(List, int[])}.
     *
     * <p>{@code priorities} is a parallel vector — one entry per alternative, same order, higher preferred — read as a
     * secondary ranking key between the fit gate and line count. It is a static per-alternative fact on the node, not a
     * measured width, so the ranking stays a pure function of the AST plus the rendered column.
     *
     * <p>{@code rankFirstLineFirst} switches the order to first-line fit → fit → priority → less overflow → fewer lines
     * (see {@link #bestFittingFirstLine(List)}): a candidate whose first line fits beats one whose first line overruns.
     * Like {@code priorities} it is a static per-node ranking-mode fact, not a width; default {@code false} leaves the
     * ranking exactly as it was for callers that do not opt in.
     */
    record BestFitting(List<Doc> alternatives, int[] priorities, boolean rankFirstLineFirst) implements Doc {
        /**
         * Rejects an empty alternative list, defensively copies the alternatives, and normalizes {@code priorities} so
         * the "at least one alternative" and "one priority per alternative" invariants hold for every {@code BestFitting}
         * no matter how it is constructed — including a direct in-package {@code new BestFitting(...)} that bypasses the
         * factories. An empty {@code priorities} array is the "no preference" default and is expanded to all-zero, which
         * makes the priority key a no-op (every candidate ties on priority) and reduces the ranking to today's
         * fewest-lines metric — the byte-identity guarantee for existing call sites. A non-empty array is cloned and must
         * have exactly one entry per alternative. "Render nothing" is never a valid layout-choice intent; use
         * {@link #EMPTY} to render nothing deliberately. The "a non-first alternative may contain a forced break" freedom
         * is intentionally not enforced here (see {@link #bestFitting(List)}).
         */
        public BestFitting {
            if (alternatives.isEmpty()) {
                throw new IllegalArgumentException(
                    "bestFitting requires at least one alternative; 'render nothing' is not a valid layout choice"
                );
            }
            alternatives = List.copyOf(alternatives);
            priorities = priorities.length == 0 ? new int[alternatives.size()] : priorities.clone();
            if (priorities.length != alternatives.size()) {
                throw new IllegalArgumentException(
                    "bestFitting priorities length (" + priorities.length + ") must equal the number of alternatives ("
                        + alternatives.size() + ")"
                );
            }
        }
    }

    record Line() implements Doc {}

    record SoftLine() implements Doc {}

    record HardLine() implements Doc {}

    record BreakParent() implements Doc {}

    record Indent(Doc doc) implements Doc {}

    /**
     * A flat-first layout group. {@code groupId} is an optional identity (nullable): when set, the renderer records
     * this group's chosen mode under that id so a dependent {@link IfBreak} can read it by name. A null id is the
     * common, anonymous case and has no rendering effect.
     */
    record Group(Doc doc, String groupId) implements Doc {}

    /**
     * Chooses between {@code breakDoc} and {@code flatDoc} by group mode. With a null {@code groupId} it follows the
     * ambient surrounding group (the common case); with a non-null id it follows the mode the identified {@link Group}
     * recorded when it rendered earlier.
     */
    record IfBreak(Doc breakDoc, Doc flatDoc, String groupId) implements Doc {}

    record Label(String label, Doc doc) implements Doc {}

    record LineSuffix(Doc content) implements Doc {}
}
