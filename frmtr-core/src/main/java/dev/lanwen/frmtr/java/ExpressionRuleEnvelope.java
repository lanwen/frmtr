package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import dev.lanwen.frmtr.FormatterException;
import dev.lanwen.frmtr.doc.Doc;

/**
 * Applies expression-level entry gates before formatted expression content is dispatched.
 *
 * <p>This helper owns the outer expression rule envelope: shared expression entry points and source-sensitive
 * comment-removal variants that should behave the same regardless of the expression subtype. The boundary keeps those
 * broad expression concerns out of {@link ExpressionDispatcher}, which only narrows already-formattable expression
 * content, and out of specialized expression printers, which render the selected expression grammar.
 *
 * <p>Callers still choose when an expression context is needed and provide the already-wired content dispatcher.
 * Assignment, call, array, conditional, lambda, switch-expression, text-block, and compact fallback layout stay with
 * their existing owners.
 */
final class ExpressionRuleEnvelope {
    private final JavaFormatRule<Expression> expressionContent;

    ExpressionRuleEnvelope(JavaFormatRule<Expression> expressionContent) {
        this.expressionContent = expressionContent;
    }

    /**
     * Routes a normal expression rendering request to expression-content dispatch.
     */
    Doc expression(Expression expression) {
        requireFullyParsed(expression);
        return Doc.label("java.expression:" + expression.getClass().getSimpleName(), expressionContent.format(expression));
    }

    /**
     * Removes only the expression node's own attached comment before normal expression rendering.
     *
     * <p>The expression is cloned first so the shared JavaParser tree keeps its original comment attachment for later
     * layout decisions; only this one rendering request sees the comment-free node.
     */
    Doc expressionWithoutOwnComment(Expression expression) {
        Expression clone = expression.clone();
        clone.removeComment();
        return expression(clone);
    }

    private static void requireFullyParsed(Expression expression) {
        if (expression.stream().allMatch(node -> node.getParsed() == Node.Parsedness.PARSED)) {
            return;
        }
        // TODO: Expose the rejected recovered expression through formatter diagnostics once recovery reporting exists.
        throw new FormatterException("Unsupported Java parse-error recovery reached expression formatter: "
                + expression.getClass().getSimpleName());
    }
}
