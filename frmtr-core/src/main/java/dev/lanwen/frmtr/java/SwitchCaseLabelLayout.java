package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.RecordPatternExpr;
import com.github.javaparser.ast.expr.TypePatternExpr;
import com.github.javaparser.ast.nodeTypes.NodeWithModifiers;
import com.github.javaparser.ast.stmt.SwitchEntry;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Owns how a {@code switch} entry's {@code case} / {@code default} label renders for {@link SwitchPrinter} — the label's
 * flat-versus-wrapped width shape and its inline-comment-preserving raw fallback.
 *
 * <p>This helper hosts the label family behind the single {@link #switchEntryLabel} entry point (plus the
 * {@link #switchLabelText} spelling the guard renderer reuses). It classifies the label layout
 * ({@link SwitchLabelLayout}: keep the whole {@code case ...} list flat, wrap one over-wide record-pattern label after
 * {@code case}, or put each comma-separated label on its own line), renders a single label or a record pattern wrapped one
 * component per line, spells the {@code default} label and each label's text from raw or compacted source, and rebuilds a
 * spread label list from its commented token text so inline block comments ({@code case REMOTE /* remote *}{@code /,
 * HYBRID}) survive ({@link #commentPreservingCaseLabel}). The boundary exists so the switch printer's per-entry pipeline
 * can consult one place for the label region instead of carrying the record-pattern wrapping, the label-list width test,
 * and the raw comment-preservation scan inline.
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

    private final FormatterOptions options;

    private final Function<NodeWithModifiers<?>, String> modifiers;

    private final ToIntFunction<String> currentIndentedWidth;

    private final Function<SwitchEntry, List<Node>> arrowLeadingCommentBuckets;

    SwitchCaseLabelLayout(
            RawSource rawSource,
            CompactSourceText compactSource,
            CommentTracker comments,
            JavaCommentPlacementPolicy commentPlacementPolicy,
            SourceText sourceText,
            FormatterOptions options,
            Function<NodeWithModifiers<?>, String> modifiers,
            ToIntFunction<String> currentIndentedWidth,
            Function<SwitchEntry, List<Node>> arrowLeadingCommentBuckets
    ) {
        this.rawSource = rawSource;
        this.compactSource = compactSource;
        this.comments = comments;
        this.commentPlacementPolicy = commentPlacementPolicy;
        this.sourceText = sourceText;
        this.options = options;
        this.modifiers = modifiers;
        this.currentIndentedWidth = currentIndentedWidth;
        this.arrowLeadingCommentBuckets = arrowLeadingCommentBuckets;
    }

    /**
     * Names the switch-label layouts after the caller has already selected structured switch-entry printing.
     *
     * <p>The enum owns only the local label shape. It does not decide whether an entry is raw-preserved, whether guards
     * wrap, or how the entry body renders; those choices stay with the surrounding switch-entry pipeline.
     */
    private enum SwitchLabelLayout {
        /** Keep the complete {@code case ...} label on one line because it fits or a single label does not need wrapping. */
        FLAT,

        /** Wrap one record-pattern label after {@code case} because the pattern itself exceeds the available width. */
        SINGLE_WRAPPED_LABEL,

        /** Put each label on its own line because a comma-separated label list exceeds the available width. */
        WRAPPED_LABEL_LIST,
    }

    /**
     * Prints the {@code case} or {@code default} label before a switch entry's guard and arrow/colon.
     *
     * <p>Default labels may include source-only text such as comments before {@code default}; case labels can stay flat,
     * wrap as one record pattern, or wrap as one label per line when the comma-separated label list is too wide.
     */
    Doc switchEntryLabel(SwitchEntry entry) {
        if (entry.isDefault()) {
            return Doc.text(defaultSwitchEntryLabel(entry));
        }
        Optional<Doc> commented = commentPreservingCaseLabel(entry);
        if (commented.isPresent()) {
            return commented.orElseThrow();
        }
        String flatLabels = entry.getLabels()
                .stream()
                .map(this::switchLabelText)
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
        String flat = "case " + flatLabels;
        return switch (switchLabelLayout(entry, flat)) {
            case FLAT -> Doc.text(flat);
            case SINGLE_WRAPPED_LABEL -> Doc.concat(Doc.text("case "), switchLabel(entry.getLabels().get(0)));
            case WRAPPED_LABEL_LIST -> Doc.concat(
                Doc.text("case"),
                Doc.indent(
                    Doc.concat(
                        Doc.HARD_LINE,
                        Doc.join(
                            Doc.concat(Doc.text(","), Doc.HARD_LINE),
                            entry.getLabels().stream().map(label -> Doc.text(switchLabelText(label))).toList()
                        )
                    )
                )
            );
        };
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

    private SwitchLabelLayout switchLabelLayout(SwitchEntry entry, String flat) {
        if (entry.getLabels().size() == 1 && !switchLabelBreaks(entry.getLabels().get(0))) {
            return SwitchLabelLayout.FLAT;
        }
        if (currentIndentedWidth.applyAsInt(flat + " -> {}") <= options.lineWidth()) {
            return SwitchLabelLayout.FLAT;
        }
        if (entry.getLabels().size() == 1) {
            return SwitchLabelLayout.SINGLE_WRAPPED_LABEL;
        }
        return SwitchLabelLayout.WRAPPED_LABEL_LIST;
    }

    /**
     * Reports whether a record-pattern label needs its own wrapped pattern rendering.
     */
    private boolean switchLabelBreaks(Expression label) {
        return label instanceof RecordPatternExpr
            && currentIndentedWidth.applyAsInt("case " + switchLabelText(label) + " -> {}") > options.lineWidth();
    }

    /**
     * Prints a single label, wrapping record patterns whose type and component list cannot fit flat.
     */
    private Doc switchLabel(Expression label) {
        if (label instanceof RecordPatternExpr recordPattern && switchLabelBreaks(label)) {
            return recordPattern(recordPattern);
        }
        return Doc.text(switchLabelText(label));
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

    private Doc recordPatternComponent(Expression pattern) {
        if (pattern instanceof RecordPatternExpr recordPattern && switchLabelBreaks(pattern)) {
            return recordPattern(recordPattern);
        }
        return Doc.text(switchLabelText(pattern));
    }
}
