package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.Parameter;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Prints shared method and constructor throws-clause placement after declaration headers have been assembled.
 *
 * <p>This helper owns the same-line versus broken {@code throws ...} decision shared by normal methods, abstract
 * methods, normal constructors, and compact constructors. The boundary exists because those declarations build
 * different prefixes, parameter documents, and body suffixes, but their throws-clause width rule is one formatter
 * concern.
 *
 * <p>Callers still decide declaration header assembly, parameter-list rendering, body or semicolon suffix selection,
 * and compact source text policy. This helper owns the width calculation and measures the candidate same-line throws
 * clause at the declaration's real rendered column (its block/type nesting depth) rather than a fixed baseline, so a
 * {@code throws …} on a member of an inner class or nested type is judged against the column where it actually renders.
 * It asks the caller only for the declaration node (for that depth) plus the already assembled flat pieces needed to
 * choose where the throws clause lands.
 */
final class ThrowsClausePrinter {

    private final FormatterOptions options;

    private final Function<Node, String> compact;

    private final Function<List<? extends Node>, String> compactJoin;

    private final ToIntFunction<String> currentIndentedWidth;

    private final LayoutWidth layoutWidth;

    ThrowsClausePrinter(
            FormatterOptions options,
            Function<Node, String> compact,
            Function<List<? extends Node>, String> compactJoin,
            ToIntFunction<String> currentIndentedWidth,
            LayoutWidth layoutWidth
    ) {
        this.options = options;
        this.compact = compact;
        this.compactJoin = compactJoin;
        this.currentIndentedWidth = currentIndentedWidth;
        this.layoutWidth = layoutWidth;
    }

    Doc throwsClause(
            Node declaration,
            String prefix,
            NodeList<Parameter> parameters,
            NodeList<? extends Node> thrownExceptions,
            LayoutContext layout,
            boolean forceBreak,
            boolean parametersBreak
    ) {
        // The same-line content the caller emits after the throws clause — the "{" of a body or the ";" of an abstract
        // declaration — is carried on the context as trailing content rather than passed as a loose suffix string, so
        // the width gate reads "what follows me on this line" from its position (LayoutContext #218) instead of the
        // caller re-describing it here.
        String suffix = layout.trailingContent();
        String exceptions = compactJoin.apply(thrownExceptions);
        String throwsText = "throws " + exceptions;
        String flatParameters = "("
            + parameters.stream().map(compact).reduce((left, right) -> left + ", " + right).orElse("")
            + ")";
        String flatSignature = prefix + flatParameters;
        // C10 (#220): the same-line throws width is measured at the declaration's real rendered column, not the fixed
        // one-indent-level `currentIndented` baseline. A `throws …` on a member of an inner class / nested type renders
        // one block/type level deeper per enclosing scope, and the old baseline under-counted that indentation, so an
        // over-width nested clause was kept inline against reality. `LayoutWidth.nodeLine` counts every enclosing
        // TypeDeclaration/BlockStmt around the declaration and floors at one level, and the `currentIndentedWidth` term
        // is kept as a floor so a top-level member is still measured against at least one unit — leaving top-level
        // declarations byte-identical while correcting the deeper-nested ones (mirrors the LDM-2 unary/ternary/return
        // gates and the try-with-resources opener gate #219). The trailer still arrives from LayoutContext (above).
        String sameLine = parametersBreak
            ? ") " + throwsText + suffix
            : flatSignature + " " + throwsText + suffix;
        int sameLineWidth = Math.max(
            layoutWidth.nodeLine(declaration, sameLine),
            currentIndentedWidth.applyAsInt(sameLine)
        );
        if (forceBreak && (!parametersBreak || sameLineWidth > options.lineWidth())) {
            return brokenThrowsClause(thrownExceptions);
        }
        if (sameLineWidth <= options.lineWidth()) {
            return Doc.text(" " + throwsText);
        }
        return brokenThrowsClause(thrownExceptions);
    }

    /**
     * Lays out a {@code throws ...} clause on its own indented continuation line, greedily packing the thrown
     * exceptions across as many lines as the width allows instead of emitting them as one unbreakable blob.
     *
     * <p>The clause keyword stays glued to the first exception, then a {@link Doc#fill(List)} drives the inter-exception
     * separators: each {@code ,} plus {@link Doc#LINE} is laid out flat while the next exception still fits on the
     * current line and broken to a fresh continuation line only where it does not. This replaces the prior
     * all-or-nothing shape, where a long exception list moved to its own line but still overflowed because it was a
     * single {@link Doc#text(String)} string.
     *
     * @param thrownExceptions the declared thrown exceptions; must be non-empty, since the keyword is glued to
     *     {@code .get(0)} and the packing loop assumes at least one exception. Every call site already guards
     *     {@code isEmpty()} (a method or constructor with no {@code throws} clause never reaches a throws-clause
     *     layout at all).
     */
    private Doc brokenThrowsClause(NodeList<? extends Node> thrownExceptions) {
        List<Doc> parts = new ArrayList<>();
        parts.add(Doc.text("throws " + compact.apply(thrownExceptions.get(0))));
        for (int i = 1; i < thrownExceptions.size(); i++) {
            parts.add(Doc.concat(Doc.text(","), Doc.LINE));
            parts.add(Doc.text(compact.apply(thrownExceptions.get(i))));
        }
        return Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.fill(parts)));
    }
}
