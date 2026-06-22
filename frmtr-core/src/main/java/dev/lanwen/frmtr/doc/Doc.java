package dev.lanwen.frmtr.doc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public sealed interface Doc
    permits
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
     * never reaches, so the separator would be silently dropped (the same data-loss footgun fixed in {@code 0332c16c}).
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
     * Defers {@code content} to the end of the current line: it renders nothing at this position and flushes just before
     * the next line break (or at end of document). Used for trailing comments so the code preceding them is laid out and
     * width-measured as if the comment were absent — the comment can never push that code over the line width or change
     * which separator prints first.
     *
     * <p>Content is single-line only in this version; a {@link HardLine} inside it is rejected at render time.
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
