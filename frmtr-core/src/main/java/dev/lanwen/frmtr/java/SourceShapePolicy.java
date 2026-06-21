package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import dev.lanwen.frmtr.FormatterOptions;

/**
 * Single per-run home for "should the formatter respect the author's source shape here?" decisions.
 *
 * <p>Formatting features such as preserving a deliberately multiline call, keeping a blank line between members, or
 * keeping a constructor compact are all reads of the original token layout. Historically each of those reads was
 * spelled inline at the call site against {@link RawSource}, {@link SourceText}, or raw {@code getRange()} arithmetic,
 * so the same conceptual question ("was this already multiline?", "was there a blank line between these?") had several
 * subtly different definitions. This policy exists so a printer asks one named, intent-revealing question and never
 * reconstructs source-shape logic inline; with a single definition per decision the formatter has exactly one fixed
 * point to reason about for idempotence.
 *
 * <p>The policy is read-only and built once per formatting run from the source helpers it consults: {@link SourceText}
 * for offset/line indexing, {@link RawSource} for raw token extraction, {@link CompactSourceText} for
 * source-equivalent compact text, {@link JavaCommentPlacementPolicy} for comment associations, and
 * {@link FormatterOptions} for option-gated behavior. It deliberately does <em>not</em> absorb those collaborators'
 * own concerns: it does not own offset/slicing math ({@link SourceText}), raw-output comment accounting
 * ({@link RawPreservedSource}), or parse-recovery boundary rules. It calls them; it does not re-own them.
 *
 * <p>This is the first concrete slice of the deferred formatter-owned syntax view: a narrow metadata owner for
 * layout-from-source decisions that a larger view could later absorb. During the staged migration the existing
 * {@link SourceShape} predicate surface continues to work by delegating its canonical "was multiline" definition here,
 * so call sites can move behind the policy incrementally without changing formatter output.
 */
final class SourceShapePolicy {

    private final SourceText sourceText;

    private final RawSource rawSource;

    private final CompactSourceText compactSource;

    private final JavaCommentPlacementPolicy commentPolicy;

    private final FormatterOptions options;

    SourceShapePolicy(
            SourceText sourceText,
            RawSource rawSource,
            CompactSourceText compactSource,
            JavaCommentPlacementPolicy commentPolicy,
            FormatterOptions options
    ) {
        this.sourceText = sourceText;
        this.rawSource = rawSource;
        this.compactSource = compactSource;
        this.commentPolicy = commentPolicy;
        this.options = options;
    }

    /**
     * Reports whether the node's own source spanned more than one line, the single canonical "was this multiline?"
     * definition for the whole formatter.
     *
     * <p>The decision is range-first with a raw-text fallback: when JavaParser exposes a position range, a node is
     * multiline iff its begin and end lines differ; when the range is absent (for example inside unparsed or recovered
     * regions), it falls back to scanning the node's raw source for a newline after its own attached comment is removed
     * so the comment's own line breaks do not count. Every printer that needs to know whether the author already broke a
     * call, lambda, initializer, or chain across lines asks this one method, so the formatter has exactly one fixed point
     * to reason about for idempotence rather than several range-vs-raw definitions that could disagree on the same node.
     */
    boolean wasMultiline(Node node) {
        return node.getRange()
                .map(range -> range.begin.line < range.end.line)
                .orElseGet(() -> rawSource.rawWithoutOwnComment(node).contains("\n"));
    }
}
