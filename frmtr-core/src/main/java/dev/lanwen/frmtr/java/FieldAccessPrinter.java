package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.comments.BlockComment;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import dev.lanwen.frmtr.doc.Doc;

/**
 * Renders field access expressions after broad expression dispatch has selected a dotted field segment.
 *
 * <p>This helper owns the field-access-specific assembly of a rendered scope plus the accessed field name, including
 * the token-sensitive fork for comments attached directly to the field-name token. The boundary exists because method
 * chains and compact source text also inspect field accesses, but their broader chain and fallback decisions stay with
 * {@link MethodCallPrinter} and {@link JavaPrinter}.
 *
 * <p>{@link JavaPrinter} still owns broad expression dispatch, compact source-derived text, method-call chain layout,
 * and enclosed-expression suffixes. This helper receives scope rendering as a callback and only decides how a selected
 * {@link FieldAccessExpr} is assembled.
 */
final class FieldAccessPrinter {

    private final CommentTracker comments;

    private final ExpressionRendering rendering;

    FieldAccessPrinter(CommentTracker comments, ExpressionRendering rendering) {
        this.comments = comments;
        this.rendering = rendering;
    }

    /**
     * Prints {@code scope.name}, preserving comments that JavaParser attaches to the name token itself.
     *
     * <p>The hard line is only for a line or block comment owned by the field-name token, so the comment stays between
     * the rendered scope and the {@code .name} segment. General dotted-chain wrapping remains method-call-owned and is
     * not inferred here from line width.
     */
    Doc fieldAccess(FieldAccessExpr expression) {
        Doc scope = rendering.render(expression.getScope());
        Doc nameComment = comments.ownComment(
            expression.getName(),
            comment -> comment instanceof LineComment
                    || comment instanceof BlockComment
        );
        if (nameComment != Doc.EMPTY) {
            return Doc.concat(scope, nameComment, Doc.HARD_LINE, Doc.text("." + expression.getNameAsString()));
        }
        return Doc.concat(scope, Doc.text("." + expression.getNameAsString()));
    }
}
