package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.expr.Expression;
import dev.lanwen.frmtr.doc.Doc;
import java.util.function.Function;

/**
 * Bundles the two generic "render a nested child expression" entries so an expression-domain leaf printer takes one
 * collaborator instead of re-threading the child-render callback (and its no-own-comment variant) through its own
 * constructor.
 *
 * <p>This facade owns nothing but the delegation back to the shared expression dispatch: {@link #render} routes a nested
 * child expression through the top-level expression rules, and {@link #renderWithoutOwnComment} does the same after the
 * caller-facing "strip the node's own comment first" behavior. Both entries were previously wired independently into a
 * dozen leaf printers &mdash; some as a layout-discarding {@code JavaFormatRule<Expression>}, some as a
 * {@code Function<Expression, Doc>} &mdash; even though every one of them resolved to the same shared render call. The
 * boundary exists so that identical plumbing is constructed once and injected as a single collaborator.
 *
 * <p>This facade intentionally does not decide which child expressions are rendered, when a context needs the
 * comment-stripping variant, or any width, break, or shape choice. Those stay with the leaf printers. It also leaves the
 * shape-specific cross-printer callbacks (broken calls, forced or fanned chains, broken object creation, huggable lambda
 * arguments, binary continuation lines) alone: those carry distinct behavior and are threaded separately, not through
 * this seam.
 */
final class ExpressionRendering {

    private final Function<Expression, Doc> expressionRenderer;

    private final Function<Expression, Doc> expressionWithoutOwnCommentRenderer;

    ExpressionRendering(
            Function<Expression, Doc> expressionRenderer,
            Function<Expression, Doc> expressionWithoutOwnCommentRenderer
    ) {
        this.expressionRenderer = expressionRenderer;
        this.expressionWithoutOwnCommentRenderer = expressionWithoutOwnCommentRenderer;
    }

    /**
     * Renders a nested child expression through the shared expression dispatch, identical to the top-level expression
     * entry the leaf printers previously reached through their own injected callback.
     */
    Doc render(Expression expression) {
        return expressionRenderer.apply(expression);
    }

    /**
     * Renders a nested child expression after removing the node's own attached comment, matching the no-own-comment
     * variant the assignment and conditional printers previously took as a separate callback.
     */
    Doc renderWithoutOwnComment(Expression expression) {
        return expressionWithoutOwnCommentRenderer.apply(expression);
    }
}
