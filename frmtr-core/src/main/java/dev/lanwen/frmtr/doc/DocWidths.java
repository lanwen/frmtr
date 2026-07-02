package dev.lanwen.frmtr.doc;

import java.util.IdentityHashMap;
import java.util.List;

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

    /**
     * Maximum nesting depth at which a {@link Doc.BestFitting} is ranked (rule D16). A best-fitting node reached while
     * ranking or rendering an outer one is at the next depth; once depth reaches this bound the inner node stops being
     * ranked and collapses to its first (flattest) alternative, bounding total exploration to keep the walk linear.
     * The renderer and the line-count simulation apply the identical bound through the shared {@link #chooseBestFitting}
     * decision so the alternative rendered for real is always the alternative the ranking measured.
     */
    static final int MAX_BEST_FITTING_DEPTH = 4;

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
     * {@link Doc.IfBreak} is measured by its flat branch only, matching the renderer's group-fit question: "can this
     * group render on one line?"
     */
    static final class Measurement {

        private final IdentityHashMap<Doc, Integer> flatWidths = new IdentityHashMap<>();

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
         * <p>This boundary exists so the rendering walk ({@link DocRenderer#render(Doc)}) and the {@code --explain}
         * trace ({@link DocExplainRenderer}) ask the fit question through one place and cannot silently drift: both
         * must advance their column cursor by the same flat/break choice or the explained layout would diverge from the
         * emitted output. It centralizes only the correctness-relevant probe — the same {@link Doc#concat} of separator
         * and next content against the same shared width authority. The caller still owns supplying its own remaining
         * width (each renderer subtracts its own tracked column from the configured line width), mapping the returned
         * boolean onto its own flat/break mode, and the per-step action that follows (emitting text versus recording a
         * trace node).
         */
        boolean separatorFitsFlat(Doc separator, Doc nextContent, int remaining) {
            return fits(Doc.concat(separator, nextContent), remaining);
        }

        int flatWidth(Doc doc) {
            return measure(doc, UNBOUNDED);
        }

        /**
         * Ranks a {@link Doc.BestFitting}'s alternatives by rendered line count at the live output column and returns the
         * winning index (rule B8 + D16). This is the single decision {@link DocRenderer} and the {@link #measureLineCount}
         * simulation both consult, so the alternative rendered for real is always the alternative the ranking measured —
         * they cannot drift because there is one function, not two copies of the tie-break.
         *
         * <p>Only the first {@code min(size, MAX_BEST_FITTING_ALTERNATIVES)} alternatives are measured (linear-time
         * bound). Beyond {@link #MAX_BEST_FITTING_DEPTH} nested best-fitting levels the node is not ranked at all and
         * collapses to its first (flattest) alternative, so total exploration stays bounded. The tie-break (D16) is
         * strict and gated on fit first: a layout that fits (no line exceeds the width) beats any that
         * overflows, no matter its line count; within one fit class an alternative wins only if it has <em>strictly
         * fewer</em> lines, or equal lines and <em>strictly less</em> overflow. Equal-fit-equal-lines-equal-overflow
         * keeps the earlier (flatter) alternative, which makes the choice deterministic and therefore the reformat a
         * fixpoint. See {@link LineCount#betterThan}.
         *
         * @param depth the best-fitting nesting depth of this node (0 for a top-level node), used to apply the bound
         */
        int chooseBestFitting(List<Doc> alternatives, int indent, int startColumn, int lineWidth, int depth) {
            if (depth >= MAX_BEST_FITTING_DEPTH || alternatives.size() == 1) {
                return 0;
            }
            int limit = Math.min(alternatives.size(), MAX_BEST_FITTING_ALTERNATIVES);
            int bestIndex = 0;
            LineCount best = null;
            for (int i = 0; i < limit; i++) {
                // Rank each candidate as it would render at this column, resolving any nested best-fitting nodes at the
                // next depth so the metric matches what the winner will actually emit.
                LineCount candidate = measureLineCount(alternatives.get(i), indent, startColumn, lineWidth, depth + 1);
                if (best == null || candidate.betterThan(best)) {
                    best = candidate;
                    bestIndex = i;
                }
            }
            return bestIndex;
        }

        /**
         * Counts the lines and overflow a document would render into at a given start column, without emitting anything
         * or mutating this measurement's width cache. It is the ranking metric for {@link Doc.BestFitting} and a
         * <em>side-effect-free mirror of {@link DocRenderer}'s rendering walk</em>: it reproduces the same newline, mode,
         * {@link Doc.Fill} packing, {@link Doc.IfBreak}/{@code groupId}, {@link Doc.LineSuffix} flushing, and
         * best-fitting selection so its line count equals the number of newlines the renderer emits. That equality is the
         * load-bearing invariant, pinned directly by a congruence test ({@code measureLineCount(doc).lines()} ==
         * newlines {@code render(doc)} emits) so the mirror can never silently drift from the renderer even though the
         * two walks are separate code: the renderer emits text while this only accumulates counts, and unifying them
         * would force the byte-identical hot path through a sink abstraction this foundation deliberately leaves intact.
         *
         * <p>The walk keeps its own scratch column, group-mode map, and line-suffix buffer — never the renderer's — so a
         * ranking probe cannot perturb an in-progress render. Inner {@link Doc.Group} fit is answered through the shared
         * memoized {@link #fits} cache (line counting is never written into that cache), and nested best-fitting nodes
         * are resolved through the same {@link #chooseBestFitting} the renderer uses, under the same depth bound.
         *
         * @return the number of newlines emitted and {@code Σ max(0, column - lineWidth)} accumulated at each newline and
         *     at end of the document
         */
        LineCount measureLineCount(Doc doc, int indent, int startColumn, int lineWidth) {
            return measureLineCount(doc, indent, startColumn, lineWidth, 0);
        }

        /**
         * The depth-parameterized {@link #measureLineCount}, exposed to {@link DocExplainRenderer} so the {@code
         * --explain} trace can record the exact per-alternative line counts the ranking weighed at a nested
         * best-fitting node's depth — the same numbers {@link #chooseBestFitting} used, so the explanation never reports
         * a metric that differs from the one that picked the winner.
         */
        LineCount measureLineCount(Doc doc, int indent, int startColumn, int lineWidth, int bestFittingDepth) {
            LineCountWalk walk = new LineCountWalk(lineWidth, bestFittingDepth);
            walk.column = startColumn;
            walk.walk(doc, indent, LineMode.BREAK);
            walk.flushLineSuffixes();
            walk.accountOverflowAtEnd();
            return new LineCount(walk.lines, walk.overflow);
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

            private final int bestFittingDepth;

            private final java.util.Map<String, LineMode> groupModes = new java.util.HashMap<>();

            private final List<BufferedSuffix> lineSuffixes = new java.util.ArrayList<>();

            private int column;

            private int lines;

            private int overflow;

            private LineCountWalk(int lineWidth, int bestFittingDepth) {
                this.lineWidth = lineWidth;
                this.bestFittingDepth = bestFittingDepth;
            }

            private void walk(Doc doc, int indent, LineMode mode) {
                switch (doc) {
                    case Doc.Text text -> advance(text.value());
                    case Doc.Concat concat -> concat.docs().forEach(child -> walk(child, indent, mode));
                    case Doc.Line ignored -> {
                        if (mode == LineMode.FLAT) {
                            advance(" ");
                        } else {
                            newline(indent);
                        }
                    }
                    case Doc.SoftLine ignored -> {
                        if (mode == LineMode.BREAK) {
                            newline(indent);
                        }
                    }
                    case Doc.HardLine ignored -> newline(indent);
                    case Doc.BreakParent ignored -> {
                        // Emits nothing and advances no column, exactly like DocRenderer.
                    }
                    case Doc.Indent indented -> walk(indented.doc(), indent + 1, mode);
                    case Doc.Group group -> {
                        LineMode next = fits(group.doc(), lineWidth - column) ? LineMode.FLAT : LineMode.BREAK;
                        if (group.groupId() != null) {
                            groupModes.put(group.groupId(), next);
                        }
                        walk(group.doc(), indent, next);
                    }
                    case Doc.Fill fill -> walkFill(fill.parts(), indent);
                    case Doc.ConditionalGroup conditionalGroup -> walkConditionalGroup(conditionalGroup.alternatives(), indent);
                    case Doc.BestFitting bestFitting -> {
                        List<Doc> alternatives = bestFitting.alternatives();
                        int chosen = chooseBestFitting(alternatives, indent, column, lineWidth, bestFittingDepth);
                        walk(alternatives.get(chosen), indent, LineMode.BREAK);
                    }
                    case Doc.IfBreak conditional -> {
                        LineMode effective = conditional.groupId() == null
                            ? mode
                            : groupModes.getOrDefault(conditional.groupId(), LineMode.FLAT);
                        walk(effective == LineMode.BREAK ? conditional.breakDoc() : conditional.flatDoc(), indent, mode);
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
                walk(parts.getFirst(), indent, LineMode.FLAT);
                for (int i = 1; i + 1 < parts.size(); i += 2) {
                    Doc separator = parts.get(i);
                    Doc nextContent = parts.get(i + 1);
                    LineMode separatorMode = separatorFitsFlat(separator, nextContent, lineWidth - column)
                        ? LineMode.FLAT
                        : LineMode.BREAK;
                    walk(separator, indent, separatorMode);
                    walk(nextContent, indent, LineMode.FLAT);
                }
            }

            /** Mirrors {@link DocRenderer#renderConditionalGroup}: first flat fit wins, else the last in break mode. */
            private void walkConditionalGroup(List<Doc> alternatives, int indent) {
                for (Doc alternative : alternatives) {
                    if (fits(alternative, lineWidth - column)) {
                        walk(alternative, indent, LineMode.FLAT);
                        return;
                    }
                }
                walk(alternatives.getLast(), indent, LineMode.BREAK);
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
             * Mirrors {@link DocRenderer#newline}: flush buffered line suffixes onto the closing line (advancing the
             * column, never adding a newline since suffix content is single-line), account the closing line's overflow,
             * count the break, then reset the column to the indent. Trailing-whitespace trimming is a text concern with
             * no effect on the line count, so it is intentionally not replayed here.
             */
            private void newline(int indent) {
                flushLineSuffixes();
                accountOverflow();
                lines++;
                column = indentWidth * indent;
            }

            private void flushLineSuffixes() {
                while (!lineSuffixes.isEmpty()) {
                    List<BufferedSuffix> pending = List.copyOf(lineSuffixes);
                    lineSuffixes.clear();
                    for (BufferedSuffix suffix : pending) {
                        walk(suffix.content(), suffix.indent(), suffix.mode());
                    }
                }
            }

            private void accountOverflow() {
                if (column > lineWidth) {
                    overflow += column - lineWidth;
                }
            }

            private void accountOverflowAtEnd() {
                accountOverflow();
            }
        }

        private record BufferedSuffix(Doc content, int indent, LineMode mode) {}
    }

    /**
     * The result of a {@link Measurement#measureLineCount} probe: how many newlines a document renders into and the
     * total overflow past the line width. Used to rank {@link Doc.BestFitting} alternatives (rule D16): a layout that
     * fits (zero overflow) always beats one that overflows; among layouts of equal fit status the winner is the one with
     * strictly fewer lines, then strictly less overflow, then the earliest (flattest) on a tie.
     */
    record LineCount(int lines, int overflow) {

        /**
         * Whether the document fits — no rendered line exceeded the width, i.e. the summed per-line overflow is zero.
         * This is the primary ranking key (rule D16): fitting must dominate line count, because a layout whose opener or
         * any later line spills past the width is a defect the reader sees, whereas one extra line is not.
         */
        boolean fits() {
            return overflow == 0;
        }

        /**
         * Whether this count is strictly preferable to {@code other} under the D16 tie-break.
         *
         * <p>The <em>overflow gate</em> is the primary key (rule D16): a fitting layout (zero total overflow) is strictly
         * better than any overflowing one, no matter how many fewer lines the overflowing one uses. Without this gate the
         * line-count-first metric could keep an alternative whose first line already overruns the width simply because it
         * renders in fewer lines, outranking an alternative that fully fits but wraps onto more lines — exactly the
         * fan-out-versus-argument-break decision a printer needs to route through {@code bestFitting}.
         *
         * <p>Within one fit class the existing least-bad metric decides: fewer lines wins; on equal lines, less overflow
         * wins. When both fit, overflow is zero for each, so the choice reduces to fewer lines. When neither fits the gate
         * is a no-op and the fewest-lines / least-overflow order is unchanged. Equal lines and equal overflow is
         * <em>not</em> strictly better, so an earlier alternative already chosen keeps its place — that strictness is what
         * makes the ranking deterministic and the reformat a fixpoint.
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

    private enum LineMode {
        FLAT,
        BREAK,
    }
}
