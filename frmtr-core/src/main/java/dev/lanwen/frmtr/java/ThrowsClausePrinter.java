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
 * compact source text policy, and the active width calculation. This helper only asks for the already assembled flat
 * pieces needed to choose where the throws clause lands.
 */
final class ThrowsClausePrinter {

    private final FormatterOptions options;

    private final Function<Node, String> compact;

    private final Function<List<? extends Node>, String> compactJoin;

    private final ToIntFunction<String> currentIndentedWidth;

    ThrowsClausePrinter(
            FormatterOptions options,
            Function<Node, String> compact,
            Function<List<? extends Node>, String> compactJoin,
            ToIntFunction<String> currentIndentedWidth
    ) {
        this.options = options;
        this.compact = compact;
        this.compactJoin = compactJoin;
        this.currentIndentedWidth = currentIndentedWidth;
    }

    /**
     * Places {@code throws ...} on the current line when it fits, otherwise on its own indented continuation line.
     *
     * <p>If the parameter list already breaks, the caller will have printed the closing {@code )} on the current line,
     * so the same-line check only measures {@code ) throws ...} plus the declaration suffix. If parameters stay flat,
     * the check measures the complete flat signature because the throws clause competes with the whole header.
     */
    Doc throwsClause(
            String prefix,
            NodeList<Parameter> parameters,
            NodeList<? extends Node> thrownExceptions,
            String suffix
    ) {
        return throwsClause(prefix, parameters, thrownExceptions, suffix, false, parametersBreak(prefix, parameters));
    }

    /**
     * Places {@code throws ...} on the current line or preserves a caller-detected source break before it.
     *
     * <p>When parameters already broke, the closing {@code )} owns the current line. In that shape a source break before
     * {@code throws} is kept only if the compact {@code ) throws ...} continuation would overflow.
     */
    Doc throwsClause(
            String prefix,
            NodeList<Parameter> parameters,
            NodeList<? extends Node> thrownExceptions,
            String suffix,
            boolean forceBreak
    ) {
        return throwsClause(
            prefix,
            parameters,
            thrownExceptions,
            suffix,
            forceBreak,
            parametersBreak(prefix, parameters)
        );
    }

    Doc throwsClause(
            String prefix,
            NodeList<Parameter> parameters,
            NodeList<? extends Node> thrownExceptions,
            String suffix,
            boolean forceBreak,
            boolean parametersBreak
    ) {
        String exceptions = compactJoin.apply(thrownExceptions);
        String throwsText = "throws " + exceptions;
        String flatParameters = "("
            + parameters.stream().map(compact).reduce((left, right) -> left + ", " + right).orElse("")
            + ")";
        String flatSignature = prefix + flatParameters;
        int sameLineWidth = parametersBreak
            ? currentIndentedWidth.applyAsInt(") " + throwsText + suffix)
            : currentIndentedWidth.applyAsInt(flatSignature + " " + throwsText + suffix);
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

    private boolean parametersBreak(String prefix, NodeList<Parameter> parameters) {
        String flatParameters = "("
            + parameters.stream().map(compact).reduce((left, right) -> left + ", " + right).orElse("")
            + ")";
        return currentIndentedWidth.applyAsInt(prefix + flatParameters) > options.lineWidth();
    }
}
