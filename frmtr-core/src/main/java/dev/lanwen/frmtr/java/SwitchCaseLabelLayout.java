package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.RecordPatternExpr;
import com.github.javaparser.ast.expr.TypePatternExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.github.javaparser.ast.stmt.SwitchEntry;
import dev.lanwen.frmtr.doc.Doc;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Owns how a {@code switch} entry's {@code case} / {@code default} label renders for {@link SwitchPrinter} — the label's
 * flat-versus-wrapped width shape and its inline-comment-preserving raw fallback.
 *
 * <p>This helper hosts the label family behind the single {@link #switchEntryLabel} entry point. It returns a
 * {@link CaseLabel}: a fixed layout for a
 * {@code default}, a comment-carrying list, or a single non-pattern label, or the flat-and-wrapped alternatives — a
 * comma-separated label list or a single record pattern — for the renderer to rank at the entry's true column. It renders
 * a record pattern wrapped one component per line, spells the {@code default} label
 * and each label's text from raw or compacted source, and rebuilds a spread label list from its commented token text so
 * inline block comments ({@code case REMOTE /* remote *}{@code /, HYBRID}) survive ({@link #commentPreservingCaseLabel}).
 * The boundary exists so the switch printer's per-entry pipeline can consult one place for the label region instead of
 * carrying the record-pattern wrapping, the label-list alternatives, and the raw comment-preservation scan inline.
 *
 * <p>The helper claims only the label region: it does not decide whether an entry is raw-preserved end to end, whether the
 * guard wraps, or how the entry body renders — those stay with {@link SwitchPrinter}. It reads the same arrow-leading
 * comment buckets the switch printer computes (threaded in as a handle) and accounts the label comments it renders as
 * raw-rendered through the shared {@link CommentTracker}, so a comment this helper claims is one the printer's other
 * comment slots will not re-emit.
 */
final class SwitchCaseLabelLayout {

    private final RawSource rawSource;

    private final CompactSourceText compactSource;

    private final CommentTracker comments;

    private final JavaCommentPlacementPolicy commentPlacementPolicy;

    private final SourceText sourceText;

    private final Function<NodeWithModifiers<?>, String> modifiers;

    private final Function<SwitchEntry, List<Node>> arrowLeadingCommentBuckets;

    SwitchCaseLabelLayout(
            RawSource rawSource,
            CompactSourceText compactSource,
            CommentTracker comments,
            JavaCommentPlacementPolicy commentPlacementPolicy,
            SourceText sourceText,
            Function<NodeWithModifiers<?>, String> modifiers,
            Function<SwitchEntry, List<Node>> arrowLeadingCommentBuckets
    ) {
        this.rawSource = rawSource;
        this.compactSource = compactSource;
        this.comments = comments;
        this.commentPlacementPolicy = commentPlacementPolicy;
        this.sourceText = sourceText;
        this.modifiers = modifiers;
        this.arrowLeadingCommentBuckets = arrowLeadingCommentBuckets;
    }

    /**
     * A switch entry's rendered {@code case}/{@code default} label: a single fixed layout, or — for a comma-separated
     * {@code case} label list — the flat and wrapped alternatives the renderer ranks at the entry's true
     * column.
     *
     * <p>Splitting the list case into two alternatives lets {@link SwitchPrinter} attach the guard and arrow/colon opener
     * to each and defer the flat-versus-wrapped choice to the renderer, which alone sees the label list's real nesting
     * column; a build-time width probe cannot, because the switch entry-list indent is a renderer-applied {@code indent}.
     */
    sealed interface CaseLabel {

        /** A label with one settled layout: {@code default}, a comment-carrying list, or a single non-pattern label. */
        record Fixed(Doc doc) implements CaseLabel {}

        /**
         * A flat one-liner and its wrapped shape for the renderer to rank at the entry's true column: a comma-separated
         * list's wrapped shape, or a single record pattern's one-component-per-line shape.
         */
        record Ranked(Doc flat, Doc wrapped) implements CaseLabel {}
    }

    /**
     * Builds the {@code case} or {@code default} label before a switch entry's guard and arrow/colon.
     *
     * <p>Default labels may include source-only text such as comments before {@code default}. A single record pattern and
     * a comma-separated list are both handed back as {@link CaseLabel.Ranked} so the renderer keeps them flat when they
     * fit at the real column and wraps (one component / one label per line) only on overflow; other single labels stay
     * flat as {@link CaseLabel.Fixed}.
     */
    CaseLabel switchEntryLabel(SwitchEntry entry) {
        if (entry.isDefault()) {
            return new CaseLabel.Fixed(Doc.text(defaultSwitchEntryLabel(entry)));
        }
        Optional<Doc> commented = commentPreservingCaseLabel(entry);
        if (commented.isPresent()) {
            return new CaseLabel.Fixed(commented.orElseThrow());
        }
        List<Expression> labels = entry.getLabels();
        String flat = "case " + labels.stream()
                .map(this::switchLabelText)
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
        if (labels.size() == 1) {
            Expression only = labels.get(0);
            if (only instanceof RecordPatternExpr recordPattern) {
                return new CaseLabel.Ranked(
                    Doc.text(flat), Doc.concat(Doc.text("case "), recordPattern(recordPattern))
                );
            }
            return new CaseLabel.Fixed(Doc.text(flat));
        }
        return new CaseLabel.Ranked(Doc.text(flat), wrappedLabelList(labels));
    }

    /**
     * The wrapped shape for a comma-separated label list: labels pack greedily via {@link Doc#fill} at {@code +2}
     * continuation indent. Emitted as the break arm of {@link CaseLabel.Ranked}; the renderer chooses it only when the
     * flat one-liner overflows.
     */
    private Doc wrappedLabelList(List<Expression> labels) {
        Doc separator = Doc.concat(Doc.text(","), Doc.LINE);
        List<Doc> fillParts = Stream.concat(
            Stream.of(Doc.text("case " + switchLabelText(labels.get(0)))),
            labels.stream()
                .skip(1)
                .flatMap(label -> Stream.of(separator, Doc.text(switchLabelText(label))))
        ).toList();
        return Doc.indent(Doc.indent(Doc.fill(fillParts)));
    }

    /**
     * Renders a {@code case} label list that carries inline block comments ({@code case REMOTE /* remote *}{@code /,
     * HYBRID}) from its raw commented token text, preserving the comments structured rendering would otherwise strip.
     *
     * <p>At {@code @default} a single-line entry with such a comment is preserved verbatim by
     * {@link SwitchPrinter#rawSingleLineSwitchEntry}; once a perturbation spreads the entry across lines that path stops
     * firing and the label list is rebuilt token by token, dropping the comments ({@link #switchLabelText} renders only
     * the label expression). This rebuilds the label region with {@link CommentedTokenText#tokenLine} (reproducing inline
     * label comment spacing) and accounts them as raw-rendered so the print-once guardrails still see them. Labels
     * without block comments never enter this path.
     */
    private Optional<Doc> commentPreservingCaseLabel(SwitchEntry entry) {
        Node boundary = entry.getStatements().isEmpty() ? entry : entry.getStatements().get(0);
        List<JavaCommentTrivia> labelComments =
            commentPlacementPolicy.blockCommentsBefore(arrowLeadingCommentBuckets.apply(entry), boundary)
                    .stream()
                    .filter(comment -> !comment.startsBefore(entry))
                    .toList();
        if (labelComments.isEmpty()) {
            return Optional.empty();
        }
        String raw = rawSource.raw(entry);
        int boundaryIndex = defaultLabelBoundary(raw);
        if (boundaryIndex < 0) {
            return Optional.empty();
        }
        String labelText = CommentedTokenText.tokenLine(CommentedTokenText.tokens(raw.substring(0, boundaryIndex)));
        if (labelText.isEmpty()) {
            return Optional.empty();
        }
        List<JavaCommentTrivia> renderedLabelComments = labelComments.stream()
                .filter(comment -> beginsWithinLabelRegion(entry, comment, boundaryIndex))
                .toList();
        if (renderedLabelComments.isEmpty()) {
            return Optional.empty();
        }
        comments.accountRaw(renderedLabelComments.stream().map(JavaCommentTrivia::comment).toList());
        return Optional.of(Doc.text(labelText));
    }

    /**
     * Reports whether a label block comment begins inside the rebuilt label region (the raw token text before the
     * arrow/colon), so accounting it matches what {@code labelText} actually renders.
     *
     * <p>{@link JavaCommentPlacementPolicy#blockCommentsBefore} bounds by the body node, so it can include a comment in
     * the arrow-to-body gap ({@code case X -> /* mid *}{@code / body}) that {@code labelText} never reproduces. Mapping
     * the comment's offset into the same coordinate space as {@code boundaryIndex} (relative to the entry's stripped
     * token range) and keeping only comments before the boundary makes the accounted set equal the rendered set.
     */
    private boolean beginsWithinLabelRegion(SwitchEntry entry, JavaCommentTrivia comment, int boundaryIndex) {
        return entry.getRange()
                .map(range -> sourceText.region(range).beginOffset())
                .flatMap(entryRawBeginOffset -> commentBeginOffset(comment)
                    .map(commentBeginOffset -> commentBeginOffset - entryRawBeginOffset < boundaryIndex))
                .orElse(false);
    }

    private Optional<Integer> commentBeginOffset(JavaCommentTrivia comment) {
        return comment.comment()
                .getRange()
                .map(range -> sourceText.region(range).beginOffset());
    }

    String switchLabelText(Expression label) {
        if (label instanceof TypePatternExpr) {
            return rawSource.normalizeWhitespace(label.toString());
        }
        if (label instanceof RecordPatternExpr) {
            return rawSource.normalizeWhitespace(label.toString());
        }
        return compactSource.compact(label);
    }

    private String defaultSwitchEntryLabel(SwitchEntry entry) {
        String raw = rawSource.raw(entry);
        int boundary = defaultLabelBoundary(raw);
        if (boundary < 0) {
            return "default";
        }
        String label = CommentedTokenText.tokenLine(CommentedTokenText.tokens(raw.substring(0, boundary)));
        return label.isEmpty() ? "default" : label;
    }

    private int defaultLabelBoundary(String raw) {
        int colon = raw.indexOf(':');
        int arrow = raw.indexOf("->");
        if (colon < 0) {
            return arrow;
        }
        if (arrow < 0) {
            return colon;
        }
        return Math.min(colon, arrow);
    }

    /**
     * Prints a record-pattern label with one component per line when the flat label is wider than the line budget.
     *
     * <p>Pattern labels use normalized raw text for compact cases because JavaParser's ordinary expression printer does
     * not preserve all source-only pattern spelling. Once the label wraps, this method keeps the type and modifiers in
     * the normal type pipeline and recursively wraps nested record patterns that are also too wide.
     */
    private Doc recordPattern(RecordPatternExpr pattern) {
        return Doc.concat(
            Doc.text(modifiers.apply(pattern) + compactSource.compactTypeLike(pattern.getType()) + "("),
            Doc.indent(
                Doc.concat(
                    Doc.HARD_LINE,
                    Doc.join(
                        Doc.concat(Doc.text(","), Doc.HARD_LINE),
                        pattern.getPatternList()
                                .stream()
                                .map(this::recordPatternComponent)
                                .toList()
                    )
                )
            ),
            Doc.HARD_LINE,
            Doc.text(")")
        );
    }

    /**
     * A nested record-pattern component's flat spelling and its one-component-per-line shape, ranked by the renderer at
     * the component's true column; non-pattern components stay flat. Keeping both arms out of the IR lets the renderer
     * wrap a nested component only when it overflows at real depth.
     */
    private Doc recordPatternComponent(Expression pattern) {
        if (pattern instanceof RecordPatternExpr recordPattern) {
            return Doc.bestFittingFirstLine(List.of(
                Doc.text(switchLabelText(recordPattern)),
                recordPattern(recordPattern)
            ));
        }
        return Doc.text(switchLabelText(pattern));
    }
}
