package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.expr.MethodCallExpr;
import dev.lanwen.frmtr.FormatterOptions;

/**
 * Records a method-call chain's flat-width break for the {@code --explain} report, without ever influencing layout.
 *
 * <p>This helper owns the after-the-fact width attribution for a chain the printer has already decided to break: it
 * measures the chain's compact single-line form and, only when that form overflows the line budget, logs a width
 * decision with a short chain preview and the segment count. The boundary exists so the chain printer can commit to a
 * broken one-segment-per-line layout on its own terms and then hand the explain bookkeeping to a single place, instead
 * of threading compact measurement and preview building through the layout code.
 *
 * <p>The caller still owns every layout decision and, critically, <em>when</em> to record: this helper never changes
 * the {@link dev.lanwen.frmtr.doc.Doc} that is produced, so the caller must invoke it only after it has committed to
 * the broken chain the recorded "N segments, one per line" attribution describes.
 */
final class ChainWidthBreakExplain {

    private final CompactSourceText compactSource;

    private final LayoutWidth layoutWidth;

    private final FormatterOptions options;

    private final LayoutDecisionLog layoutDecisions;

    ChainWidthBreakExplain(
            CompactSourceText compactSource,
            LayoutWidth layoutWidth,
            FormatterOptions options,
            LayoutDecisionLog layoutDecisions
    ) {
        this.compactSource = compactSource;
        this.layoutWidth = layoutWidth;
        this.options = options;
        this.layoutDecisions = layoutDecisions;
    }

    /**
     * Records the chain's flat-width decision when width is the actual cause of the break, so explain can report real
     * arithmetic instead of an opaque forced break.
     *
     * <p>Only a chain whose compact single-line form overflows the line budget is recorded as a width break: chains
     * forced apart purely by comments or by already-multiline source are not width decisions, so attributing them to
     * width would mislead. This is called after the printer has already committed to breaking, so it never changes the
     * layout that is produced.
     */
    void record(
            MethodCallExpr expression,
            MethodCallChainSourcePlanner.MethodCallChainAnalysis analysis,
            LayoutWidth.LineBudget lineBudget
    ) {
        String compact = compactSource.compact(expression);
        int flatWidth = layoutWidth.line(lineBudget, compact);
        if (flatWidth <= options.lineWidth()) {
            return;
        }
        int segments = analysis.calls().size() + 1;
        layoutDecisions.recordWidthBreak(
            "method chain",
            "java.expression:" + expression.getClass().getSimpleName(),
            chainPreview(compact),
            flatWidth,
            options.lineWidth(),
            segments
        );
    }

    /**
     * Builds a short headline snippet of the chain: the first two call selectors followed by an ellipsis when the chain
     * is longer, so the reader recognizes the construct without seeing the whole line.
     */
    private String chainPreview(String compact) {
        int firstCall = compact.indexOf('(');
        if (firstCall < 0) {
            return compact;
        }
        int firstClose = matchingClose(compact, firstCall);
        if (firstClose < 0) {
            return compact;
        }
        int secondDot = compact.indexOf('.', firstClose);
        if (secondDot < 0) {
            return compact.substring(0, firstClose + 1);
        }
        int secondCall = compact.indexOf('(', secondDot);
        if (secondCall < 0) {
            return compact.substring(0, firstClose + 1) + "…";
        }
        int secondClose = matchingClose(compact, secondCall);
        if (secondClose < 0) {
            return compact.substring(0, firstClose + 1) + "…";
        }
        String head = compact.substring(0, secondClose + 1);
        return secondClose + 1 < compact.length() ? head + "…" : head;
    }

    private int matchingClose(String text, int open) {
        int depth = 0;
        for (int index = open; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character == '(') {
                depth++;
            } else if (character == ')') {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }
        return -1;
    }
}
