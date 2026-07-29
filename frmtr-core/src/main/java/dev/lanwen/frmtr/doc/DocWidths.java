package dev.lanwen.frmtr.doc;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Single width authority for document layout: measures the flat-mode width of a {@link Doc} and answers whether it fits
 * in a given number of columns.
 *
 * <p>This helper owns only width arithmetic, intentionally separated from the rendering walk so that the renderer and
 * any observer of its decisions (such as {@link DocExplainRenderer}) compute fit identically. Centralizing it here means
 * a fit decision and the width number reported for it can never diverge. Each render/explain pass gets a fresh
 * {@link Measurement} so memoized widths are reused only inside that pass and cannot leak across renders. The boundary
 * deliberately excludes column tracking, indentation text, and line endings: those are render concerns owned by
 * {@link DocRenderer}, because indentation does not affect flat width.
 */
final class DocWidths {

    /** Sentinel flat width signalling that a document contains a forced break and cannot fit on one line. */
    static final int NO_FIT = Integer.MIN_VALUE;

    /**
     * Upper bound on how many alternatives of a {@link Doc.BestFitting} the renderer line-count-ranks (rule D16): only
     * the first {@code min(size, MAX_BEST_FITTING_ALTERNATIVES)} are measured, so ranking stays linear and native-image
     * friendly no matter how many alternatives a printer emits. The winner is always one of the measured prefix.
     */
    static final int MAX_BEST_FITTING_ALTERNATIVES = 8;

    /** Internal sentinel for bounded measurements that stopped after overflow before finding a complete width. */
    private static final int OVERFLOW = Integer.MIN_VALUE + 1;

    private static final int UNBOUNDED = Integer.MAX_VALUE;

    private DocWidths() {}

    static Measurement measurement() {
        return new Measurement();
    }

    /**
     * Per-render width measurement state.
     *
     * <p>The cache stores only complete flat widths, never the result of a bounded fit that stopped after overflow.
     * {@link Doc.IfBreak} is measured by its flat branch only — group ids play no part in flat width, because a flat
     * measurement asks "can this render on one line?", where every enclosing decision is flat and the flat arm is the
     * consistent answer.
     */
    static final class Measurement {

        private final IdentityHashMap<Doc, Integer> flatWidths = new IdentityHashMap<>();

        /**
         * Memoizes each {@link Doc.BestFitting} ranking by node identity and the (indent, start column) it is ranked at,
         * so the same node in the same context is ranked once even when many enclosing alternatives reach it. Identity
         * keying avoids a deep value-equality traversal of the node's Doc subtree and is exact because the ranking is a
         * pure function of node identity plus measurement context within one render; created fresh per {@link
         * Measurement} (one per render) so choices never leak across renders.
         */
        private final IdentityHashMap<Doc.BestFitting, Map<RankContext, Integer>> bestFittingChoices =
            new IdentityHashMap<>();

        /**
         * The group ids a subtree reads through an identified {@link Doc.IfBreak}, memoized by node identity. Only these
         * ids can change a ranking, so only their seeded verdicts enter the memo key — a subtree that reads none keeps
         * the plain (indent, column, reserved) key it always had.
         */
        private final IdentityHashMap<Doc, List<String>> readGroupIds = new IdentityHashMap<>();

        /**
         * Width of one indentation unit in columns, used only by the {@link #measureLineCount} simulation to reset the
         * column after a newline exactly as {@link DocRenderer} does ({@code indentUnit().length() * indent}). It is
         * width arithmetic, not indentation text, so it stays within this authority's boundary. Defaults to 0 so callers
         * that only ask {@link #fits}/{@link #flatWidth} (which never see a newline) are unaffected; {@link DocRenderer}
         * and {@link DocExplainRenderer} set it to the configured indent-unit width before ranking any best-fitting node.
         */
        private int indentWidth;

        private Measurement() {}

        void indentWidth(int indentWidth) {
            this.indentWidth = indentWidth;
        }

        boolean fits(Doc doc, int remaining) {
            if (remaining < 0) {
                return false;
            }
            Integer cached = flatWidths.get(doc);
            if (cached != null) {
                return fitsWidth(cached, remaining);
            }
            int measured = measure(doc, remaining);
            return fitsWidth(measured, remaining);
        }

        /**
         * The per-separator FLAT-vs-BREAK fit decision a {@link Doc.Fill} makes for each separator, expressed as
         * "should this separator stay flat?". A fill keeps a separator flat when the separator together with the next
         * content still fits in the columns left on the current line, and breaks only that separator otherwise.
         *
         * <p>Centralized so the rendering walk ({@link DocRenderer#render(Doc)}) and the {@code --explain} trace
         * ({@link DocExplainRenderer}) ask the fit question through one place and cannot drift. It owns only the probe
         * (the same {@link Doc#concat} of separator and next content against the shared width authority); the caller
         * supplies its own remaining width, maps the boolean onto its flat/break mode, and takes the per-step action
         * (emit text vs record a trace node).
         */
        boolean separatorFitsFlat(Doc separator, Doc nextContent, int remaining) {
            return fits(Doc.concat(separator, nextContent), remaining);
        }

        int flatWidth(Doc doc) {
            return measure(doc, UNBOUNDED);
        }

        /**
         * Ranks a {@link Doc.BestFitting}'s alternatives by rendered line count at the live output column and returns the
         * winning index (rule B8 + D16). The single decision {@link DocRenderer} and the {@link #measureLineCount}
         * simulation both consult, so the alternative rendered for real is always the one the ranking measured — one
         * function, not two copies of the tie-break, so they cannot drift.
         *
         * <p>Only the first {@code min(size, MAX_BEST_FITTING_ALTERNATIVES)} alternatives are measured. Nested
         * best-fitting nodes are ranked exactly, at their own column, however deep they nest; the ranking of a given node
         * at a given (indent, start column) is memoized in {@link #bestFittingChoices} so this stays affordable — the
         * same context is ranked once, not re-explored once per enclosing alternative. The layered D16 tie-break: fit (no
         * line over width) beats any overflow regardless of line count; among fitting candidates a strictly higher
         * {@code priority} wins; then strictly fewer lines, then strictly less overflow. Equal on all keys keeps the
         * earlier (flatter) alternative, which makes the choice deterministic and the reformat a fixpoint. See
         * {@link #betterThan(LineCount, int, LineCount, int, boolean)}.
         *
         * <p>The ambient render mode is not part of the key: a best-fitting node is always ranked and rendered in break
         * mode (each candidate is measured, and the winner rendered, in break mode), and {@code lineWidth} is constant
         * across a render, so identity plus (indent, start column) fully determines the choice.
         */
        int chooseBestFitting(Doc.BestFitting bestFitting, int indent, int startColumn, int lineWidth) {
            return chooseBestFitting(bestFitting, indent, startColumn, lineWidth, 0, Map.of());
        }

        /**
         * As {@link #chooseBestFitting(Doc.BestFitting, int, int, int)}, with {@code reserved} columns of the caller's
         * same-line content charged against each candidate's last line (see {@link Doc#reserving(Doc, int)}).
         */
        int chooseBestFitting(Doc.BestFitting bestFitting, int indent, int startColumn, int lineWidth, int reserved) {
            return chooseBestFitting(bestFitting, indent, startColumn, lineWidth, reserved, Map.of());
        }

        /**
         * As {@link #chooseBestFitting(Doc.BestFitting, int, int, int, int)}, with the group verdicts {@code outerModes}
         * the deciding walk has already published. Each candidate is measured under those verdicts, so conditional
         * content inside an arm resolves the same way it will when the winner renders.
         *
         * <p>When this node carries a group id, each arm is additionally measured under its own provisional verdict
         * (FLAT for the first arm, BREAK for the rest), so an arm that reads the very decision it belongs to stays
         * self-consistent. An arm re-deciding a group that lives inside it is likewise per-arm and correct.
         */
        int chooseBestFitting(
                Doc.BestFitting bestFitting,
                int indent,
                int startColumn,
                int lineWidth,
                int reserved,
                Map<String, GroupMode> outerModes
        ) {
            List<Doc> alternatives = bestFitting.alternatives();
            if (alternatives.size() == 1) {
                return 0;
            }
            Map<RankContext, Integer> byContext =
                bestFittingChoices.computeIfAbsent(bestFitting, ignored -> new HashMap<>());
            RankContext contextKey =
                new RankContext(indent, startColumn, reserved, observedModes(bestFitting, outerModes));
            Integer cached = byContext.get(contextKey);
            if (cached != null) {
                return cached;
            }
            int[] priorities = bestFitting.priorities();
            boolean rankFirstLineFirst = bestFitting.rankFirstLineFirst();
            int limit = Math.min(alternatives.size(), MAX_BEST_FITTING_ALTERNATIVES);
            int bestIndex = 0;
            LineCount best = null;
            for (int i = 0; i < limit; i++) {
                // Rank each candidate as it would render at this column, resolving any nested best-fitting nodes at their
                // own column so the metric matches what the winner will actually emit.
                LineCount candidate = measureLineCount(
                    alternatives.get(i),
                    indent,
                    startColumn,
                    lineWidth,
                    reserved,
                    armModes(bestFitting, outerModes, i)
                );
                if (best == null || betterThan(candidate, priorities[i], best, priorities[bestIndex], rankFirstLineFirst)) {
                    best = candidate;
                    bestIndex = i;
                }
            }
            byContext.put(contextKey, bestIndex);
            return bestIndex;
        }

        /** The verdict FLAT/BREAK a ranked decision publishes: FLAT exactly when the flattest alternative wins. */
        static GroupMode verdictOf(int chosenIndex) {
            return chosenIndex == 0 ? GroupMode.FLAT : GroupMode.BREAK;
        }

        /** The seed an arm is measured under: the outer verdicts plus this node's own provisional per-arm verdict. */
        private Map<String, GroupMode> armModes(
                Doc.BestFitting bestFitting,
                Map<String, GroupMode> outerModes,
                int index
        ) {
            if (bestFitting.groupId() == null) {
                return outerModes;
            }
            Map<String, GroupMode> seeded = new HashMap<>(outerModes);
            seeded.put(bestFitting.groupId(), verdictOf(index));
            return seeded;
        }

        /**
         * The part of {@code outerModes} this node's subtree can actually observe, rendered as a compact key. Empty for
         * a subtree that reads no group id, which keeps the memo as sharable as it was before verdicts were threaded.
         */
        private String observedModes(Doc.BestFitting bestFitting, Map<String, GroupMode> outerModes) {
            if (outerModes.isEmpty()) {
                return "";
            }
            StringBuilder key = new StringBuilder();
            for (String id : groupIdsRead(bestFitting)) {
                GroupMode mode = outerModes.get(id);
                if (mode != null) {
                    key.append(id).append('=').append(mode == GroupMode.FLAT ? 'F' : 'B').append(';');
                }
            }
            return key.toString();
        }

        /** The identified {@link Doc.IfBreak} ids anywhere under {@code doc}, in a stable order, memoized per node. */
        private List<String> groupIdsRead(Doc doc) {
            List<String> cached = readGroupIds.get(doc);
            if (cached != null) {
                return cached;
            }
            java.util.TreeSet<String> ids = new java.util.TreeSet<>();
            collectGroupIdsRead(doc, ids);
            List<String> collected = List.copyOf(ids);
            readGroupIds.put(doc, collected);
            return collected;
        }

        private void collectGroupIdsRead(Doc doc, java.util.Set<String> ids) {
            switch (doc) {
                case Doc.IfBreak conditional -> {
                    if (conditional.groupId() != null) {
                        ids.add(conditional.groupId());
                    }
                    collectGroupIdsRead(conditional.breakDoc(), ids);
                    collectGroupIdsRead(conditional.flatDoc(), ids);
                }
                case Doc.Concat concat -> concat.docs().forEach(child -> collectGroupIdsRead(child, ids));
                case Doc.Fill fill -> fill.parts().forEach(child -> collectGroupIdsRead(child, ids));
                case Doc.ConditionalGroup conditionalGroup ->
                    conditionalGroup.alternatives().forEach(child -> collectGroupIdsRead(child, ids));
                case Doc.BestFitting nested -> nested.alternatives().forEach(child -> collectGroupIdsRead(child, ids));
                case Doc.Group group -> collectGroupIdsRead(group.doc(), ids);
                case Doc.Indent indented -> collectGroupIdsRead(indented.doc(), ids);
                case Doc.Label label -> collectGroupIdsRead(label.doc(), ids);
                case Doc.Reserve reserve -> collectGroupIdsRead(reserve.doc(), ids);
                case Doc.LineSuffix lineSuffix -> collectGroupIdsRead(lineSuffix.content(), ids);
                default -> {
                    // Leaves read no group id.
                }
            }
        }

        /**
         * Whether candidate {@code (aCount, aPriority)} is strictly preferable to {@code (bCount, bPriority)} under the
         * D16 tie-break extended with a per-alternative priority key. The order is,
         * top to bottom: <strong>fit</strong> (a fitting layout always beats an overflowing one — {@link LineCount#fits});
         * then, <strong>among two fitting candidates only</strong>, a strictly higher {@code priority}; then fewer lines,
         * then less overflow (the {@link LineCount#betterThan} order).
         *
         * <p>Placement is load-bearing: priority sits <em>after</em> the fit gate (so it can never rescue an overflowing
         * alternative) and <em>before</em> line count (so a higher-priority fitting shape wins over a fewer-lines one).
         * With equal priorities — the default all-zero case, and the neither-fits case — this reduces exactly to
         * {@link LineCount#betterThan}, the byte-identity guarantee for callers that set no priority.
         *
         * <p>When {@code rankFirstLineFirst} is set on the node the order changes to <strong>first-line fit → fit →
         * priority → less overflow → fewer lines</strong>. The first-line gate leads: an arm whose first line fits beats
         * one whose first line overruns, so a hug whose opener spills loses to a broken shape whose header fits. When the
         * first lines tie — the root broke internally, so both arms open with the same short line and the collision lands
         * on a later seam line — overflow moves ahead of line count: the arms share one over-width body, so the arm that
         * overflows less (the broken shape that splits the seam) must win over the hug that saves a line by colliding the
         * root's continuation with the selector opener. Default off keeps the fit → priority → line-count order.
         */
        private boolean betterThan(LineCount aCount, int aPriority, LineCount bCount, int bPriority, boolean rankFirstLineFirst) {
            if (rankFirstLineFirst) {
                // First-line gate leads: a hug whose first line overruns can never win over a broken arm whose header fits.
                if (aCount.firstLineFits() != bCount.firstLineFits()) {
                    return aCount.firstLineFits();
                }
                if (aCount.fits() != bCount.fits()) {
                    return aCount.fits();
                }
                if (aCount.fits() && aPriority != bPriority) {
                    return aPriority > bPriority;
                }
                // Overflow before line count: the arms share one over-width body, so the shape that overflows less on its
                // seam lines wins over the one that merely saves a line by hugging the selector onto the broken root.
                if (aCount.overflow() != bCount.overflow()) {
                    return aCount.overflow() < bCount.overflow();
                }
                return aCount.lines() < bCount.lines();
            }
            // Fit gate: fitting dominates both priority and line count, so priority is only a tie-break among
            // candidates that already fit and can never make an overflowing alternative win.
            if (aCount.fits() != bCount.fits()) {
                return aCount.fits();
            }
            if (aCount.fits() && aPriority != bPriority) {
                return aPriority > bPriority;
            }
            return aCount.betterThan(bCount);
        }

        /**
         * Counts the lines and overflow a document would render into at a given start column, without emitting anything
         * or mutating this measurement's width cache. The ranking metric for {@link Doc.BestFitting} and a
         * <em>side-effect-free mirror of {@link DocRenderer}'s rendering walk</em>, reproducing the same newline, mode,
         * {@link Doc.Fill} packing, {@link Doc.IfBreak}/{@code groupId}, {@link Doc.LineSuffix} flushing, and
         * best-fitting selection. Its line count equalling the newlines the renderer emits is the load-bearing invariant,
         * pinned by a congruence test so the mirror cannot silently drift from the renderer even though the two are
         * separate walks (one counts, one emits text — unifying them would route the byte-identical hot path through a
         * sink abstraction this foundation leaves intact).
         *
         * <p>The walk keeps its own scratch column, group-mode map, and line-suffix buffer — never the renderer's — so a
         * ranking probe cannot perturb an in-progress render. Its group-mode map starts as a copy of the deciding walk's
         * verdicts, so a verdict published before the probe crosses into it while the probe's own verdicts stay local. Inner {@link Doc.Group} fit and nested best-fitting nodes
         * are resolved through the same shared {@link #fits} cache and {@link #chooseBestFitting} the renderer uses, under
         * the same depth bound.
         *
         * @return the number of newlines emitted and {@code Σ max(0, column - lineWidth)} accumulated at each newline and
         *     at end of the document
         */
        LineCount measureLineCount(Doc doc, int indent, int startColumn, int lineWidth) {
            return measureLineCount(doc, indent, startColumn, lineWidth, 0, Map.of());
        }

        /**
         * As {@link #measureLineCount(Doc, int, int, int)}, with {@code reserved} columns of the caller's content charged
         * against the document's last line — the {@code ;} or {@code {} that follows a ranked candidate on the same line.
         */
        LineCount measureLineCount(Doc doc, int indent, int startColumn, int lineWidth, int reserved) {
            return measureLineCount(doc, indent, startColumn, lineWidth, reserved, Map.of());
        }

        /**
         * As {@link #measureLineCount(Doc, int, int, int, int)}, seeded with the group verdicts the deciding walk has
         * already published so an identified {@link Doc.IfBreak} inside {@code doc} resolves as it will when rendered.
         */
        LineCount measureLineCount(
                Doc doc,
                int indent,
                int startColumn,
                int lineWidth,
                int reserved,
                Map<String, GroupMode> outerModes
        ) {
            LineCountWalk walk = new LineCountWalk(lineWidth, outerModes);
            walk.column = startColumn;
            walk.reserved = reserved;
            walk.trailingReserved = reserved;
            walk.walk(doc, indent, GroupMode.BREAK);
            walk.flushLineSuffixes();
            walk.accountOverflowAtEnd();
            return new LineCount(walk.lines, walk.overflow, walk.firstLineOverflow);
        }

        private int measure(Doc doc, int remaining) {
            if (remaining < 0) {
                return OVERFLOW;
            }
            Integer cached = flatWidths.get(doc);
            if (cached != null) {
                return cached;
            }
            int measured = switch (doc) {
                case Doc.Text text -> text.value().length();
                case Doc.Concat concat -> measureSequence(concat.docs(), remaining);
                case Doc.Fill fill -> measureSequence(fill.parts(), remaining);
                // An enclosing group measures a conditional group by its first (most-flat) alternative, the layout the
                // renderer prefers when it fits.
                case Doc.ConditionalGroup conditionalGroup ->
                    measure(conditionalGroup.alternatives().getFirst(), remaining);
                // A best-fitting node is sized by its first (flattest) alternative — the representative flat width, the
                // same convention as a conditional group. The renderer ranks the alternatives by rendered line count, but
                // an enclosing group deciding its own flat/break mode only needs the flattest candidate's width.
                case Doc.BestFitting bestFitting ->
                    measure(bestFitting.alternatives().getFirst(), remaining);
                // A reservation is a context fact about what follows, not content, so it adds nothing to a flat width.
                case Doc.Reserve reserve -> measure(reserve.doc(), remaining);
                case Doc.Line ignored -> 1;
                case Doc.SoftLine ignored -> 0;
                case Doc.HardLine ignored -> NO_FIT;
                case Doc.BreakParent ignored -> NO_FIT;
                case Doc.Indent indented -> measure(indented.doc(), remaining);
                case Doc.Group group -> measure(group.doc(), remaining);
                case Doc.IfBreak conditional -> measure(conditional.flatDoc(), remaining);
                case Doc.Label label -> measure(label.doc(), remaining);
                case Doc.LineSuffix ignored -> 0;
            };
            if (measured != OVERFLOW) {
                flatWidths.put(doc, measured);
            }
            return measured;
        }

        /**
         * Measures a child sequence as the sum of its parts' flat widths, bounded by {@code remaining}. Used for both
         * {@link Doc.Concat} and {@link Doc.Fill}: a fill's flat width is the concatenation of all its parts (each
         * separator counted at its flat width), which over-estimates the fill but is the safe width to report to an
         * enclosing group deciding its own mode — if the whole fill fits flat, so does any greedily packed layout of it.
         */
        private int measureSequence(List<Doc> parts, int remaining) {
            int total = 0;
            for (int i = 0; i < parts.size(); i++) {
                Doc child = parts.get(i);
                int measured = measure(child, remainingAfter(remaining, total));
                if (measured == NO_FIT) {
                    return NO_FIT;
                }
                if (measured == OVERFLOW) {
                    return OVERFLOW;
                }
                total += measured;
                if (exceeds(remaining, total) && i < parts.size() - 1) {
                    return OVERFLOW;
                }
            }
            return total;
        }

        private int remainingAfter(int remaining, int used) {
            return remaining == UNBOUNDED ? UNBOUNDED : remaining - used;
        }

        private boolean exceeds(int remaining, int width) {
            return remaining != UNBOUNDED && width > remaining;
        }

        private boolean fitsWidth(int measured, int remaining) {
            return measured >= 0 && measured <= remaining;
        }

        /**
         * A side-effect-free replay of {@link DocRenderer}'s rendering walk that accumulates only a newline count and
         * overflow instead of emitting text. Each walk carries its own column cursor, group-mode map, and line-suffix
         * buffer so it never touches the enclosing render's state, and it consults the outer {@link Measurement}'s
         * memoized {@link #fits}/{@link #chooseBestFitting} for the identical fit and best-fitting decisions. Kept as a
         * per-walk instance because the cursor and buffers are mutable per replay, exactly like {@link DocRenderer}.
         */
        private final class LineCountWalk {

            private final int lineWidth;

            private final Map<String, GroupMode> groupModes;

            private final List<BufferedSuffix> lineSuffixes = new java.util.ArrayList<>();

            private int column;

            private int lines;

            private int overflow;

            /** Overflow contributed by line 0 only, captured the first time a line closes; the whole-doc overflow when it never breaks. */
            private int firstLineOverflow;

            /** Mirrors {@link DocRenderer}'s reserved-columns cursor: consumed by one decision, then cleared. */
            private int reserved;

            /** The reservation the whole walk closes on, charged to its last line by {@link #accountOverflowAtEnd}. */
            private int trailingReserved;

            private LineCountWalk(int lineWidth, Map<String, GroupMode> outerModes) {
                this.lineWidth = lineWidth;
                this.groupModes = new HashMap<>(outerModes);
            }

            private void walk(Doc doc, int indent, GroupMode mode) {
                switch (doc) {
                    case Doc.Text text -> advance(text.value());
                    case Doc.Concat concat -> concat.docs().forEach(child -> walk(child, indent, mode));
                    case Doc.Line ignored -> {
                        if (mode == GroupMode.FLAT) {
                            advance(" ");
                        } else {
                            newline(indent);
                        }
                    }
                    case Doc.SoftLine ignored -> {
                        if (mode == GroupMode.BREAK) {
                            newline(indent);
                        }
                    }
                    case Doc.HardLine ignored -> newline(indent);
                    case Doc.BreakParent ignored -> {
                        // Emits nothing and advances no column, exactly like DocRenderer.
                    }
                    case Doc.Indent indented -> walk(indented.doc(), indent + 1, mode);
                    case Doc.Group group -> {
                        GroupMode next = fits(group.doc(), lineWidth - column) ? GroupMode.FLAT : GroupMode.BREAK;
                        if (group.groupId() != null) {
                            groupModes.put(group.groupId(), next);
                        }
                        walk(group.doc(), indent, next);
                    }
                    case Doc.Fill fill -> walkFill(fill.parts(), indent);
                    case Doc.ConditionalGroup conditionalGroup -> walkConditionalGroup(conditionalGroup.alternatives(), indent);
                    case Doc.BestFitting bestFitting -> {
                        int chosen =
                            chooseBestFitting(bestFitting, indent, column, lineWidth, takeReserved(), groupModes);
                        if (bestFitting.groupId() != null) {
                            groupModes.put(bestFitting.groupId(), verdictOf(chosen));
                        }
                        walk(bestFitting.alternatives().get(chosen), indent, GroupMode.BREAK);
                    }
                    case Doc.Reserve reserve -> {
                        int enclosing = reserved;
                        reserved = reserve.columns();
                        walk(reserve.doc(), indent, mode);
                        reserved = enclosing;
                    }
                    case Doc.IfBreak conditional -> {
                        GroupMode effective = conditional.groupId() == null
                            ? mode
                            : groupModes.getOrDefault(conditional.groupId(), GroupMode.FLAT);
                        walk(effective == GroupMode.BREAK ? conditional.breakDoc() : conditional.flatDoc(), indent, mode);
                    }
                    case Doc.Label label -> walk(label.doc(), indent, mode);
                    case Doc.LineSuffix lineSuffix ->
                        lineSuffixes.add(new BufferedSuffix(lineSuffix.content(), indent, mode));
                }
            }

            /** Mirrors {@link DocRenderer#renderFill}: greedy per-separator packing via the shared fit authority. */
            private void walkFill(List<Doc> parts, int indent) {
                if (parts.isEmpty()) {
                    return;
                }
                walk(parts.getFirst(), indent, GroupMode.FLAT);
                for (int i = 1; i + 1 < parts.size(); i += 2) {
                    Doc separator = parts.get(i);
                    Doc nextContent = parts.get(i + 1);
                    GroupMode separatorMode = separatorFitsFlat(separator, nextContent, lineWidth - column)
                        ? GroupMode.FLAT
                        : GroupMode.BREAK;
                    walk(separator, indent, separatorMode);
                    walk(nextContent, indent, GroupMode.FLAT);
                }
            }

            /** Mirrors {@link DocRenderer#renderConditionalGroup}: first flat fit wins, else the last in break mode. */
            private void walkConditionalGroup(List<Doc> alternatives, int indent) {
                int available = lineWidth - column - takeReserved();
                for (Doc alternative : alternatives) {
                    if (fits(alternative, available)) {
                        walk(alternative, indent, GroupMode.FLAT);
                        return;
                    }
                }
                walk(alternatives.getLast(), indent, GroupMode.BREAK);
            }

            /** Mirrors {@link DocRenderer}'s take-once reservation: one decision spends it, inner ones see none. */
            private int takeReserved() {
                int spent = reserved;
                reserved = 0;
                return spent;
            }

            private void advance(String value) {
                int lastLineBreak = value.lastIndexOf('\n');
                if (lastLineBreak >= 0) {
                    // A newline embedded in literal text closes a line just like an emitted break would.
                    accountOverflow();
                    lines++;
                    column = value.length() - lastLineBreak - 1;
                } else {
                    column += value.length();
                }
            }

            /**
             * Mirrors {@link DocRenderer#newline}: flush buffered line suffixes onto the closing line, account the
             * closing line's overflow, count the break, then reset the column to the indent. Trailing-whitespace
             * trimming is a text concern with no effect on the line count, so it is intentionally not replayed here.
             */
            private void newline(int indent) {
                flushLineSuffixes();
                accountOverflow();
                lines++;
                column = indentWidth * indent;
            }

            /**
             * Walks buffered suffixes (so nested structure is replayed identically to the renderer) but restores
             * {@code column} to its pre-flush value afterward: a {@link Doc.Group}'s fit check measures
             * {@link Doc.LineSuffix} as zero width, so this ranking walk must match — a trailing comment can never
             * inflate the measured line, only the code that precedes it.
             */
            private void flushLineSuffixes() {
                int widthBlindColumn = column;
                while (!lineSuffixes.isEmpty()) {
                    List<BufferedSuffix> pending = List.copyOf(lineSuffixes);
                    lineSuffixes.clear();
                    for (BufferedSuffix suffix : pending) {
                        walk(suffix.content(), suffix.indent(), suffix.mode());
                    }
                }
                column = widthBlindColumn;
            }

            private void accountOverflow() {
                int lineOverflow = Math.max(0, column - lineWidth);
                overflow += lineOverflow;
                // Every line close routes through here before its lines++, so lines == 0 is exactly the first line's
                // close (or end-of-doc when the doc never broke); capture its overflow once for first-line-fit ranking.
                if (lines == 0) {
                    firstLineOverflow = lineOverflow;
                }
            }

            /**
             * Closes the last line, charging the reserved columns of caller content that lands on it. This is the only
             * line the reservation touches: interior lines close before the tail exists.
             */
            private void accountOverflowAtEnd() {
                column += trailingReserved;
                accountOverflow();
            }
        }

        private record BufferedSuffix(Doc content, int indent, GroupMode mode) {}

        /**
         * The measurement context a {@link Doc.BestFitting} ranking is memoized under: its indent, the column it starts
         * at, the columns reserved for caller content on its last line, and the seeded verdicts its subtree reads. All
         * four change the ranking, so all four key the cache; {@code observedModes} is empty for the common node that
         * reads no group id, so those rankings stay as widely shared as before.
         */
        private record RankContext(int indent, int startColumn, int reserved, String observedModes) {}
    }

    /**
     * The result of a {@link Measurement#measureLineCount} probe: how many newlines a document renders into and the
     * total overflow past the line width. Used to rank {@link Doc.BestFitting} alternatives (rule D16): a layout that
     * fits (zero overflow) always beats one that overflows; among layouts of equal fit status the winner is the one with
     * strictly fewer lines, then strictly less overflow, then the earliest (flattest) on a tie. This is the
     * <em>measured-width</em> half of the ranking only; the per-alternative priority key sits between the fit gate
     * and line count and is applied by {@link Measurement#betterThan(LineCount, int, LineCount, int, boolean)}, which keeps
     * {@code LineCount} a pure width fact.
     */
    record LineCount(int lines, int overflow, int firstLineOverflow) {

        /**
         * Whether the document fits — no rendered line exceeded the width, i.e. the summed per-line overflow is zero.
         * This is the primary ranking key (rule D16): fitting must dominate line count, because a layout whose opener or
         * any later line spills past the width is a defect the reader sees, whereas one extra line is not.
         */
        boolean fits() {
            return overflow == 0;
        }

        /**
         * Whether the <em>first</em> rendered line stays within the width. The first-line-fit ranking key used when a
         * best-fitting node opts into {@code rankFirstLineFirst}: it separates a hug whose opener overruns from a broken
         * shape whose header fits even when both carry the same over-width hard-break body, so neither {@code fits}.
         */
        boolean firstLineFits() {
            return firstLineOverflow == 0;
        }

        /**
         * Whether this count is strictly preferable to {@code other} under the D16 tie-break.
         *
         * <p>The <em>overflow gate</em> is the primary key (rule D16): a fitting layout (zero total overflow) beats any
         * overflowing one no matter how many fewer lines the latter uses. Without it the line-count-first metric could
         * keep an alternative whose first line overruns the width just because it uses fewer lines, outranking one that
         * fully fits but wraps — the fan-out-versus-argument-break decision a printer routes through {@code bestFitting}.
         *
         * <p>Within one fit class: fewer lines wins, then less overflow (when both fit, overflow is zero so it reduces to
         * fewer lines; when neither fits the gate is a no-op). Equal lines and overflow is <em>not</em> strictly better,
         * so the earlier alternative keeps its place — that strictness makes the ranking deterministic and the reformat a
         * fixpoint.
         */
        boolean betterThan(LineCount other) {
            // Overflow gate first: fitting dominates line count, so a layout that fits can never be outranked by one that
            // overflows regardless of its line count, and an overflowing one can never outrank a fitting one.
            if (fits() != other.fits()) {
                return fits();
            }
            if (lines != other.lines) {
                return lines < other.lines;
            }
            return overflow < other.overflow;
        }
    }
}
