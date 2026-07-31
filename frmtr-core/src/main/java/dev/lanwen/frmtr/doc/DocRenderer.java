package dev.lanwen.frmtr.doc;

import dev.lanwen.frmtr.FormatterOptions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class DocRenderer {

    private final FormatterOptions options;

    private final StringBuilder out = new StringBuilder();

    /**
     * Trailing content parked by {@link Doc.LineSuffix} that has not yet been flushed. Each entry remembers the
     * indent/mode in scope where the suffix was reached, so it renders at the same layout it would have had inline,
     * and the list preserves document order so multiple suffixes on a line flush in the order they were buffered.
     */
    private final List<BufferedSuffix> lineSuffixes = new ArrayList<>();

    /**
     * GroupMode chosen by each identified {@link Doc.Group}, keyed by its {@code groupId}. A dependent {@link Doc.IfBreak}
     * with a matching id reads this map instead of the ambient mode, so a closing delimiter can follow the break/flat
     * decision of an opener group it does not enclose. Populated as each identified group renders and reset per render,
     * which requires the identified group to render before the {@code IfBreak} that targets it.
     */
    private final Map<String, GroupMode> groupModes = new HashMap<>();

    /**
     * Structural indentation fact for each output line, in order, recorded only when a caller renders through
     * {@link #renderIndented(Doc)}; empty on the byte-identical {@link #render(Doc)} path. Each entry says whether the
     * line's leading whitespace was emitted by {@link #newline(int, DocWidths.Measurement)} (a structural indent) and at
     * which indent <em>level</em>. Text-block interior lines (whose embedded newlines flow through {@link #append(String)})
     * are non-structural, so a presentation layer leaves their literal indentation as emitted. Levels are the
     * authoritative, tab-width-independent signal the finished text cannot recover.
     */
    private final List<LineIndent> lineIndents = new ArrayList<>();

    /** Whether the current render is accumulating {@link #lineIndents}; keeps {@link #render(Doc)} allocation-free. */
    private boolean trackLineIndents;

    private int column;

    /**
     * Columns of caller content that will follow the enclosing {@link Doc.Reserve} on the same line. A ranked decision
     * takes and clears it for its one choice among alternatives; a plain {@link Doc.Group} only peeks, since it is not
     * a terminal choice and its content or siblings may still need the same budget.
     */
    private int reservedColumns;

    public DocRenderer(FormatterOptions options) {
        this.options = options;
    }

    public String render(Doc doc) {
        return renderInternal(doc, false).text();
    }

    /**
     * Renders {@code doc} exactly as {@link #render(Doc)} does and additionally reports, for each output line, whether
     * its leading indentation is structural (formatter-emitted) and at which indent level.
     *
     * <p>The rendered text is byte-for-byte identical to {@link #render(Doc)}; the only difference is that this overload
     * also accumulates the per-line {@link LineIndent} facts. The facts carry the block-vs-continuation signal that the
     * finished text cannot: a block level and a continuation offset both look like leading spaces, and tabs make column
     * counting ambiguous, but the renderer knows the true indent <em>level</em> at each newline. Non-structural lines
     * (text-block interiors) are marked so callers can leave their literal indentation untouched. Classifying a
     * structural indent as block or continuation is deliberately left to the caller — that policy is a
     * presentation concern, and this renderer owns only the raw structural facts.
     */
    public RenderedSource renderIndented(Doc doc) {
        return renderInternal(doc, true);
    }

    private RenderedSource renderInternal(Doc doc, boolean tracking) {
        out.setLength(0);
        column = 0;
        lineSuffixes.clear();
        groupModes.clear();
        lineIndents.clear();
        reservedColumns = 0;
        trackLineIndents = tracking;
        // The first output line starts before any newline fires; its indentation (the document's root column) is
        // structural at level 0, matching how render() begins at indent 0 / column 0.
        recordLineStart(true, 0);
        DocWidths.Measurement widths = DocWidths.measurement();
        widths.indentWidth(options.indentUnit().length());
        render(doc, 0, GroupMode.BREAK, widths);
        flushLineSuffixes(widths);
        String rendered = out.toString();
        if (options.trailingNewline() && !rendered.endsWith(options.lineEnding().value())) {
            rendered += options.lineEnding().value();
        }
        return new RenderedSource(rendered, tracking ? List.copyOf(lineIndents) : List.of());
    }

    private void render(Doc doc, int indent, GroupMode mode, DocWidths.Measurement widths) {
        switch (doc) {
            case Doc.Text text -> append(text.value());
            case Doc.Concat concat -> concat.docs().forEach(child -> render(child, indent, mode, widths));
            case Doc.Line ignored -> {
                if (mode == GroupMode.FLAT) {
                    append(" ");
                } else {
                    newline(indent, widths);
                }
            }
            case Doc.SoftLine ignored -> {
                if (mode == GroupMode.BREAK) {
                    newline(indent, widths);
                }
            }
            case Doc.HardLine ignored -> newline(indent, widths);
            case Doc.BreakParent ignored -> {
                // Emits nothing: the break it forces already happened when the enclosing group measured this marker
                // and chose BREAK mode (see DocWidths, where BreakParent yields NO_FIT exactly like HardLine).
            }
            case Doc.Indent indented -> render(indented.doc(), indent + 1, mode, widths);
            case Doc.Group group -> {
                // Peek, don't take: an enclosing Reserve's budget is a field, not a per-call token, so charging it here
                // without clearing it leaves it live for whatever follows this group on the same caller-owned last line.
                GroupMode next = widths.fits(group.doc(), options.lineWidth() - column - reservedColumns)
                    ? GroupMode.FLAT
                    : GroupMode.BREAK;
                if (group.groupId() != null) {
                    groupModes.put(group.groupId(), next);
                }
                render(group.doc(), indent, next, widths);
            }
            case Doc.Fill fill -> renderFill(fill.parts(), indent, widths);
            case Doc.ConditionalGroup conditionalGroup -> renderConditionalGroup(conditionalGroup.alternatives(), indent, widths);
            case Doc.BestFitting bestFitting -> renderBestFitting(bestFitting, indent, widths);
            case Doc.IfBreak conditional -> {
                // An identified IfBreak follows the recorded mode of its target group (which must have rendered first);
                // an anonymous IfBreak follows the ambient mode. A target that has not rendered yet is treated as flat.
                GroupMode effective = conditional.groupId() == null
                    ? mode
                    : groupModes.getOrDefault(conditional.groupId(), GroupMode.FLAT);
                render(
                    effective == GroupMode.BREAK ? conditional.breakDoc() : conditional.flatDoc(),
                    indent,
                    mode,
                    widths
                );
            }
            case Doc.Label label -> render(label.doc(), indent, mode, widths);
            case Doc.Reserve reserve -> {
                int enclosing = reservedColumns;
                reservedColumns = reserve.columns();
                render(reserve.doc(), indent, mode, widths);
                reservedColumns = enclosing;
            }
            case Doc.LineSuffix lineSuffix -> {
                requireSingleLineSuffix(lineSuffix.content());
                lineSuffixes.add(new BufferedSuffix(lineSuffix.content(), indent, mode));
            }
        }
    }

    /**
     * Renders a {@link Doc.Fill}'s alternating {@code [content, separator, …]} parts with greedy per-separator packing:
     * every content piece renders flat, and each separator stays flat when the separator plus the next content still
     * fits on the current line, broken otherwise. Each lookahead measures only one separator and one following content
     * through the shared {@link DocWidths} authority (never the whole tail), keeping the walk linear, not quadratic.
     */
    private void renderFill(List<Doc> parts, int indent, DocWidths.Measurement widths) {
        if (parts.isEmpty()) {
            return;
        }
        render(parts.getFirst(), indent, GroupMode.FLAT, widths);
        for (int i = 1; i + 1 < parts.size(); i += 2) {
            Doc separator = parts.get(i);
            Doc nextContent = parts.get(i + 1);
            // Decide this separator from the column reached after the preceding content via the shared fit helper, so
            // the renderer and the --explain trace make the identical per-separator flat/break choice.
            GroupMode separatorMode = widths.separatorFitsFlat(separator, nextContent, options.lineWidth() - column)
                ? GroupMode.FLAT
                : GroupMode.BREAK;
            render(separator, indent, separatorMode, widths);
            render(nextContent, indent, GroupMode.FLAT, widths);
        }
    }

    /**
     * Renders a {@link Doc.ConditionalGroup} by choosing the first alternative whose flat layout fits the space left on
     * the current line and rendering it flat; if none fits, the last renders in break mode as the unconditional
     * fallback. Each candidate is probed with the shared {@link DocWidths} authority, the same fit question a
     * {@link Doc.Group} asks. Since non-last alternatives are chosen purely by flat fit, one containing a forced break
     * never fits and is skipped, so only the last is reachable as a broken layout (see {@link Doc#conditionalGroup}); the
     * factory rejects an empty list, so there is always a fallback.
     */
    private void renderConditionalGroup(List<Doc> alternatives, int indent, DocWidths.Measurement widths) {
        int reserved = takeReservedColumns();
        for (Doc alternative : alternatives) {
            if (widths.fits(alternative, options.lineWidth() - column - reserved)) {
                render(alternative, indent, GroupMode.FLAT, widths);
                return;
            }
        }
        // No alternative fits flat, so render the last one in break mode as the unconditional fallback.
        render(alternatives.getLast(), indent, GroupMode.BREAK, widths);
    }

    /**
     * Reads and clears the pending reservation: the enclosing {@link Doc.Reserve} funds exactly one decision, and the
     * chosen candidate's own inner decisions must be judged at the plain width.
     */
    private int takeReservedColumns() {
        int reserved = reservedColumns;
        reservedColumns = 0;
        return reserved;
    }

    /**
     * Renders a {@link Doc.BestFitting} by keeping the alternative that minimizes rendered line count at the live output
     * column (rule B8 + D16). Selection is delegated to {@link DocWidths.Measurement#chooseBestFitting} — the same
     * decision the line-count simulation uses, so the alternative rendered here cannot drift from the one the ranking
     * measured. The probes are side-effect-free (they never touch {@code out}, {@code column}, or {@code lineSuffixes}),
     * so the winner is rendered <em>once</em>, in break mode, letting its own inner groups decide flat-vs-broken from the
     * column they reach. A nested best-fitting node inside the winner is ranked at its own column through the same
     * shared, memoized decision.
     *
     * <p>The verdicts already published are handed to the ranking so conditional content inside an arm measures the way
     * it will render, and an identified node publishes its own verdict — FLAT for the flattest arm — before the winner
     * renders, so dependent content that follows can read it.
     */
    private void renderBestFitting(Doc.BestFitting bestFitting, int indent, DocWidths.Measurement widths) {
        int reserved = takeReservedColumns();
        int chosen = widths.chooseBestFitting(
            bestFitting,
            indent,
            column,
            options.lineWidth(),
            reserved,
            groupModes
        );
        if (bestFitting.groupId() != null) {
            groupModes.put(bestFitting.groupId(), DocWidths.Measurement.verdictOf(chosen));
        }
        // The winner's own fit decisions may sit on the same caller-owned last line the reservation was funding, so
        // re-supply it as the live field for its render, exactly as the ranking walk already did, then restore after.
        int enclosing = reservedColumns;
        reservedColumns = reserved;
        render(bestFitting.alternatives().get(chosen), indent, GroupMode.BREAK, widths);
        reservedColumns = enclosing;
    }

    private void append(String value) {
        out.append(value);
        int lastLineBreak = value.lastIndexOf('\n');
        if (lastLineBreak >= 0) {
            column = value.length() - lastLineBreak - 1;
        } else {
            column += value.length();
        }
        // A newline that arrives inside appended text (only text-block literals carry embedded newlines) starts a line
        // whose leading whitespace is literal source data, not a structural indent the formatter chose — mark it
        // non-structural so the presentation layer leaves it exactly as emitted.
        if (trackLineIndents && lastLineBreak >= 0) {
            for (int at = value.indexOf('\n'); at >= 0; at = value.indexOf('\n', at + 1)) {
                recordLineStart(false, 0);
            }
        }
    }

    private void newline(int indent, DocWidths.Measurement widths) {
        flushLineSuffixes(widths);
        trimTrailingHorizontalWhitespace();
        out.append(options.lineEnding().value())
                .repeat(options.indentUnit(), indent);
        column = options.indentUnit().length() * indent;
        // The line just opened begins with a structural indent of exactly this many levels — the tab-width-independent
        // fact the finished text cannot recover.
        recordLineStart(true, indent);
    }

    private void recordLineStart(boolean structural, int level) {
        if (trackLineIndents) {
            lineIndents.add(new LineIndent(structural, level));
        }
    }

    /**
     * Renders every buffered {@link Doc.LineSuffix} at its captured indent/mode, in document order, then empties the
     * buffer. A suffix that itself buffers another suffix would re-enter this method, so the buffer is drained until
     * empty rather than iterated once; restricting suffix content to single lines keeps that drain finite.
     */
    private void flushLineSuffixes(DocWidths.Measurement widths) {
        while (!lineSuffixes.isEmpty()) {
            List<BufferedSuffix> pending = List.copyOf(lineSuffixes);
            lineSuffixes.clear();
            for (BufferedSuffix suffix : pending) {
                render(suffix.content(), suffix.indent(), suffix.mode(), widths);
            }
        }
    }

    /**
     * Guards the restriction that line-suffix content is single-line: a {@link Doc.HardLine} buried in a
     * suffix would, once flushed at a line break, emit a second break and could retroactively change a layout already
     * decided around the (zero-width) suffix. All trailing-comment call sites produce single-line content.
     */
    private static void requireSingleLineSuffix(Doc content) {
        if (containsHardLine(content)) {
            throw new IllegalArgumentException("LineSuffix content must be single-line, but contained a hard line break");
        }
    }

    /**
     * Reports whether {@code doc} contains a forced newline ({@link Doc.HardLine}) anywhere in its tree — i.e. whether it
     * is a genuinely multi-line layout that can never render flat.
     *
     * <p>A pure structural query (touches no {@link DocWidths}/renderer state) so a printer can enforce the
     * {@link Doc#conditionalGroup(java.util.List) conditionalGroup} contract locally: only the LAST alternative may be a
     * broken layout. A printer whose broken fallback comes from a source-shape-dependent helper (which may hand back a
     * FLAT one-liner) uses this to detect that degenerate result and substitute a real broken layout.
     */
    public static boolean containsHardLine(Doc doc) {
        return switch (doc) {
            case Doc.HardLine ignored -> true;
            case Doc.Concat concat -> concat.docs().stream().anyMatch(DocRenderer::containsHardLine);
            case Doc.Fill fill -> fill.parts().stream().anyMatch(DocRenderer::containsHardLine);
            case Doc.ConditionalGroup conditionalGroup ->
                conditionalGroup.alternatives().stream().anyMatch(DocRenderer::containsHardLine);
            case Doc.BestFitting bestFitting ->
                bestFitting.alternatives().stream().anyMatch(DocRenderer::containsHardLine);
            case Doc.Indent indented -> containsHardLine(indented.doc());
            case Doc.Group group -> containsHardLine(group.doc());
            case Doc.Label label -> containsHardLine(label.doc());
            case Doc.Reserve reserve -> containsHardLine(reserve.doc());
            case Doc.IfBreak conditional ->
                containsHardLine(conditional.breakDoc()) || containsHardLine(conditional.flatDoc());
            case Doc.LineSuffix lineSuffix -> containsHardLine(lineSuffix.content());
            case Doc.Text ignored -> false;
            case Doc.Line ignored -> false;
            case Doc.SoftLine ignored -> false;
            // BreakParent forces a group break but emits no newline, so it does not violate the single-line restriction.
            case Doc.BreakParent ignored -> false;
        };
    }

    private void trimTrailingHorizontalWhitespace() {
        while (!out.isEmpty()) {
            char last = out.charAt(out.length() - 1);
            if (last != ' ' && last != '\t') {
                break;
            }
            out.setLength(out.length() - 1);
        }
    }

    private record BufferedSuffix(Doc content, int indent, GroupMode mode) {}

    /**
     * The rendered source paired with a per-line structural indentation signal, produced by {@link #renderIndented(Doc)}.
     * {@link #text()} is byte-for-byte what {@link #render(Doc)} returns; {@link #lines()} holds one {@link LineIndent}
     * per output line (splitting {@code text} on line feeds), in order. On the plain {@link #render(Doc)} path
     * {@code lines()} is empty because the facts are not accumulated.
     *
     * @param text the formatted source, identical to {@link #render(Doc)} for the same document
     * @param lines the structural indentation fact for each output line, in order (empty when not rendered with indents)
     */
    public record RenderedSource(String text, List<LineIndent> lines) {}

    /**
     * Structural indentation fact for one output line. {@link #structural()} is true when the line's leading whitespace
     * was emitted by the renderer as a chosen indent (so {@link #level()} is meaningful) and false for text-block
     * interior lines, whose leading whitespace is literal source data and whose {@code level} is not meaningful. The
     * level is an indent-unit count, independent of tab width — the signal a presentation layer needs to tell a block
     * indent from a continuation indent, which the finished text alone cannot provide.
     *
     * @param structural whether this line's leading whitespace is a formatter-chosen indent (vs literal text-block content)
     * @param level the indent-unit level of that structural indent; not meaningful when {@code structural} is false
     */
    public record LineIndent(boolean structural, int level) {}
}
