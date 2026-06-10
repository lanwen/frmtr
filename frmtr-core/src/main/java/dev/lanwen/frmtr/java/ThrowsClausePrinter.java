package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.Parameter;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
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
            ToIntFunction<String> currentIndentedWidth) {
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
            String suffix) {
        return throwsClause(prefix, parameters, thrownExceptions, suffix, false);
    }

    /**
     * Places {@code throws ...} on the current line or preserves a caller-detected source break before it.
     */
    Doc throwsClause(
            String prefix,
            NodeList<Parameter> parameters,
            NodeList<? extends Node> thrownExceptions,
            String suffix,
            boolean forceBreak) {
        String exceptions = compactJoin.apply(thrownExceptions);
        String throwsText = "throws " + exceptions;
        if (forceBreak) {
            return Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.text(throwsText)));
        }
        String flatParameters = "(" + parameters.stream()
                .map(compact)
                .reduce((left, right) -> left + ", " + right)
                .orElse("") + ")";
        String flatSignature = prefix + flatParameters;
        boolean parametersBreak = currentIndentedWidth.applyAsInt(flatSignature) > options.lineWidth();
        int sameLineWidth = parametersBreak
                ? currentIndentedWidth.applyAsInt(") " + throwsText + suffix)
                : currentIndentedWidth.applyAsInt(flatSignature + " " + throwsText + suffix);
        if (sameLineWidth <= options.lineWidth()) {
            return Doc.text(" " + throwsText);
        }
        return Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.text(throwsText)));
    }
}
