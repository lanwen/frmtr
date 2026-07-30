package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.Parameter;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Prints shared method and constructor throws-clause placement after declaration headers have been assembled.
 *
 * <p>This helper owns the same-line versus broken {@code throws ...} decision shared by normal methods, abstract
 * methods, normal constructors, and compact constructors. The boundary exists because those declarations build
 * different prefixes, parameter documents, and body suffixes, but their throws-clause width rule is one formatter
 * concern.
 *
 * <p>Callers still decide declaration header assembly, parameter-list rendering, body or semicolon suffix selection,
 * and compact source text policy. The same-line versus broken choice is a {@link Doc#conditionalGroup} ranked at the
 * declaration's true rendered column, so a nested type's deeper indentation is judged correctly with no width probe.
 */
final class ThrowsClausePrinter {

    private final Function<Node, String> compact;

    private final Function<List<? extends Node>, String> compactJoin;

    ThrowsClausePrinter(
            Function<Node, String> compact,
            Function<List<? extends Node>, String> compactJoin
    ) {
        this.compact = compact;
        this.compactJoin = compactJoin;
    }

    Doc throwsClause(
            Node declaration,
            String prefix,
            NodeList<Parameter> parameters,
            NodeList<? extends Node> thrownExceptions,
            LayoutContext layout
    ) {
        String exceptions = compactJoin.apply(thrownExceptions);
        String throwsText = "throws " + exceptions;
        // The renderer decides same-line versus broken at the true rendered column: both candidates are built once and
        // ranked there (fits-flat wins, else the broken form), instead of re-deriving the line's width from a
        // source-shaped probe string against a fixed indentation baseline. The caller's same-line trailer — the body's
        // `{` or an abstract declaration's `;` — is reserved so the flat clause is judged with it on the line.
        Doc flatCandidate = Doc.text(" " + throwsText);
        Doc brokenCandidate = brokenThrowsClause(thrownExceptions);
        return Doc.reserving(
            Doc.conditionalGroup(List.of(flatCandidate, brokenCandidate)),
            layout.trailingContent().length()
        );
    }

    /**
     * Lays out a {@code throws ...} clause on its own indented continuation line, greedily packing the thrown
     * exceptions across as many lines as the width allows instead of emitting them as one unbreakable blob.
     *
     * <p>The clause keyword stays glued to the first exception, then a {@link Doc#fill(List)} drives the inter-exception
     * separators: each {@code ,} plus {@link Doc#LINE} is laid out flat while the next exception still fits on the
     * current line and broken to a fresh continuation line only where it does not.
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
