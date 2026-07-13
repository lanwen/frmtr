package dev.lanwen.frmtr.java;

import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.List;

/**
 * Threads a fixed left-padding string into every line-start of a chain segment's {@link Doc} tree, for
 * {@link MethodCallChainPrinter}.
 *
 * <p>This helper owns the one Doc-tree rewrite the chain printer uses to align a chain's selectors under a
 * <em>short</em> root. When a chain root renders narrower than one indent unit ({@code x}, {@code io}), the printer does
 * not push the selectors to the standard double-indent continuation column; it instead pads each continuation line so the
 * dots line up just past the root. Computing that padding string and deciding when it applies stays with the caller
 * ({@code MethodCallChainPrinter.chainContinuation(Expression, List)}); this helper only takes the already-computed
 * padding and re-emits the segment {@code Doc} with that padding prepended at every position a new line begins —
 * unconditionally after a hard line, and guarded behind the break for a soft/normal line so the flat rendering is
 * untouched.
 *
 * <p>The rewrite is a structural walk over the {@link Doc} algebra: it recurses through the container nodes
 * ({@code Concat}, {@code Fill}, {@code Indent}, {@code Group}, {@code Label}, {@code IfBreak}) threading a running
 * "is the next token at a line start?" flag, treats each alternatives node ({@code ConditionalGroup}, {@code BestFitting})
 * as mutually exclusive layouts padded from the same incoming line-start, and turns the line nodes into padded lines. The
 * {@link PaddedDoc} pair carries the rewritten sub-doc alongside the updated line-start flag so a sibling walk knows
 * whether it begins a fresh line. The boundary exists so this padding-threading recursion — a pure function of the input
 * {@code Doc} and the padding string, with no formatter state — lives behind one seam instead of inside the chain
 * printer's continuation grammar.
 *
 * <p>The helper claims no ownership of chain analysis, width, or the padding policy: it neither measures the root nor
 * decides that the short-root alignment applies. It rewrites the {@code Doc} it is handed with the padding it is handed,
 * and nothing more.
 */
final class ChainSegmentPaddingLayout {

    Doc linePadded(Doc doc, String padding) {
        if (padding.isEmpty()) {
            return doc;
        }
        return linePadded(doc, padding, true).doc();
    }

    private PaddedDoc linePadded(Doc doc, String padding, boolean lineStart) {
        return switch (doc) {
            case Doc.Text ignored -> new PaddedDoc(lineStart ? Doc.concat(Doc.text(padding), doc) : doc, false);
            case Doc.Concat concat -> {
                List<Doc> children = new ArrayList<>();
                boolean nextLineStart = lineStart;
                for (Doc child : concat.docs()) {
                    PaddedDoc padded = linePadded(child, padding, nextLineStart);
                    children.add(padded.doc());
                    nextLineStart = padded.lineStart();
                }
                yield new PaddedDoc(Doc.concat(children), nextLineStart);
            }
            // A fill threads continuation padding through its parts exactly like a concat, preserving the alternating
            // content/separator structure so its own greedy packing still applies after re-padding.
            case Doc.Fill fill -> {
                List<Doc> parts = new ArrayList<>();
                boolean nextLineStart = lineStart;
                for (Doc part : fill.parts()) {
                    PaddedDoc padded = linePadded(part, padding, nextLineStart);
                    parts.add(padded.doc());
                    nextLineStart = padded.lineStart();
                }
                yield new PaddedDoc(Doc.fill(parts), nextLineStart);
            }
            // A conditional group's alternatives are mutually exclusive layouts; only one renders, so each is padded from
            // the same incoming line-start rather than threaded in sequence. Like IfBreak, the choice is deferred to the
            // renderer, so the result conservatively reports lineStart=false for the token that follows the group.
            case Doc.ConditionalGroup conditionalGroup -> {
                List<Doc> alternatives = new ArrayList<>();
                for (Doc alternative : conditionalGroup.alternatives()) {
                    alternatives.add(linePadded(alternative, padding, lineStart).doc());
                }
                yield new PaddedDoc(Doc.conditionalGroup(alternatives), false);
            }
            // A best-fitting node's alternatives are mutually exclusive layouts too; only the rank-winner renders, so
            // each is padded from the same incoming line-start rather than threaded in sequence, and the token that
            // follows conservatively reports lineStart=false because which alternative rendered is a renderer decision.
            case Doc.BestFitting bestFitting -> {
                List<Doc> alternatives = new ArrayList<>();
                for (Doc alternative : bestFitting.alternatives()) {
                    alternatives.add(linePadded(alternative, padding, lineStart).doc());
                }
                yield new PaddedDoc(Doc.bestFitting(alternatives), false);
            }
            case Doc.Line ignored -> new PaddedDoc(
                Doc.concat(Doc.LINE, Doc.ifBreak(Doc.text(padding), Doc.EMPTY)),
                false
            );
            case Doc.SoftLine ignored -> new PaddedDoc(
                Doc.concat(Doc.SOFT_LINE, Doc.breakOnly(Doc.text(padding))),
                false
            );
            case Doc.HardLine ignored -> new PaddedDoc(Doc.concat(Doc.HARD_LINE, Doc.text(padding)), false);
            case Doc.Indent indented -> {
                PaddedDoc padded = linePadded(indented.doc(), padding, lineStart);
                yield new PaddedDoc(Doc.indent(padded.doc()), padded.lineStart());
            }
            case Doc.Group group -> {
                PaddedDoc padded = linePadded(group.doc(), padding, lineStart);
                // Preserve any group identity through re-padding so a dependent IfBreak still resolves this group.
                yield new PaddedDoc(Doc.group(padded.doc(), group.groupId()), padded.lineStart());
            }
            case Doc.IfBreak conditional -> new PaddedDoc(
                Doc.ifBreak(
                    linePadded(conditional.breakDoc(), padding, lineStart).doc(),
                    linePadded(conditional.flatDoc(), padding, lineStart).doc(),
                    conditional.groupId()
                ),
                false
            );
            case Doc.Label label -> {
                PaddedDoc padded = linePadded(label.doc(), padding, lineStart);
                yield new PaddedDoc(Doc.label(label.label(), padded.doc()), padded.lineStart());
            }
            // A line suffix renders nothing at its position and flushes at the line break, so it neither consumes the
            // line-start padding slot nor needs continuation padding inside its deferred content.
            case Doc.LineSuffix lineSuffix -> new PaddedDoc(lineSuffix, lineStart);
            // A break-parent marker renders nothing and only influences the enclosing group's fit, so it passes
            // through untouched and leaves the line-start padding slot for the next visible token.
            case Doc.BreakParent ignored -> new PaddedDoc(doc, lineStart);
        };
    }

    private record PaddedDoc(Doc doc, boolean lineStart) {}
}
