package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import dev.lanwen.frmtr.doc.Doc;

/**
 * Formats one JavaParser node after a dispatcher has selected the broad syntax category that should own the node.
 *
 * <p>This boundary is intentionally smaller than a printer: it names the node-rule handoff between
 * {@link JavaPrinter}'s state-machine wiring and the specialized helpers that already own declaration, statement, or
 * expression layout. The rule receives a typed AST node and returns a document, but it does not decide whether the node
 * should be formatted, emitted raw, preceded by comments, or routed through a compact fallback.
 *
 * <p>Callers still own formatter pragma state, comment attachment, raw-source recovery, compact-source policy, and the
 * decision that a declaration, statement, or expression context has been reached. Implementations own only the layout
 * for the node shape they are handed.
 */
@FunctionalInterface
interface JavaFormatRule<N extends Node> {
    Doc format(N node);
}
