package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.VariableDeclarationExpr;
import com.github.javaparser.ast.stmt.TryStmt;

/**
 * Owns source-shape policy for constructor calls before printers choose the concrete document layout.
 *
 * <p>This helper keeps the small-constructor threshold and source-multiline preservation rules in one place because direct
 * constructor expressions, returned constructors, and constructor roots of method-call chains all need the same policy but
 * render through different printers. It deliberately returns booleans rather than docs: {@link ObjectCreationPrinter},
 * {@link ReturnExpressionPrinter}, and {@link MethodCallChainSourcePlanner} still own their surrounding grammar and final
 * document assembly.
 */
final class ObjectCreationLayoutPolicy {

    private static final int MAX_FLAT_ARGUMENTS = 3;

    private final SourceShape sourceShape;

    ObjectCreationLayoutPolicy(SourceShape sourceShape) {
        this.sourceShape = sourceShape;
    }

    /**
     * Reports whether an existing source-multiline constructor argument list should be kept multiline.
     *
     * <p>Try resources keep their legacy source-multiline behavior. Other direct constructor calls need more than three
     * arguments before the source break is honored, so compact small constructors can still collapse when they fit.
     */
    boolean shouldPreserveSourceMultilineArguments(ObjectCreationExpr expression) {
        return sourceShape.objectCreationArgumentsSpanMultipleLines(expression)
            && expression.getAllContainedComments().isEmpty()
            && (isTryResourceObjectCreation(expression) || !hasFlatArgumentCount(expression));
    }

    /**
     * Reports whether an anonymous-class constructor should keep an existing source-multiline argument list.
     *
     * <p>Anonymous class bodies often contain comments under the same object-creation subtree. Those body comments must
     * not make the constructor header collapse or get treated as argument comments, so this variant keeps the same
     * argument-count rule while ignoring comments that belong to the anonymous body.
     */
    boolean shouldPreserveAnonymousSourceMultilineArguments(ObjectCreationExpr expression) {
        return sourceShape.objectCreationArgumentsSpanMultipleLines(expression) && !hasFlatArgumentCount(expression);
    }

    /**
     * Reports whether a returned constructor should break because its source argument list was intentionally multiline.
     *
     * <p>This keeps return-specific constructor preservation with the rest of the constructor source-shape policy while
     * leaving the return printer to decide whether width alone also forces the same broken constructor document.
     */
    boolean shouldPreserveReturnSourceMultilineArguments(ObjectCreationExpr expression) {
        return (
            expression.getArguments().size() > 2
            && sourceShape.spansMultipleLines(expression)
            && expression
                    .getArguments()
                    .stream()
                    .anyMatch(argument -> argument.getRange()
                                .map(
                                    argumentRange -> argumentRange.begin.line
                                            > expression.getType().getRange().map(typeRange -> typeRange.end.line).orElse(argumentRange.begin.line)
                                )
                                .orElse(false)
                    )
        );
    }

    /**
     * Reports whether a constructor root can stay compact when a surrounding method-call chain is forced to break.
     */
    boolean canKeepCompactChainRoot(ObjectCreationExpr expression, int compactWidth, int lineWidth) {
        return hasFlatArgumentCount(expression)
            && expression.getAnonymousClassBody().isEmpty()
            && expression.getAllContainedComments().isEmpty()
            && !sourceShape.objectCreationArgumentsSpanMultipleLines(expression)
            && compactWidth <= lineWidth;
    }

    private boolean hasFlatArgumentCount(ObjectCreationExpr expression) {
        return expression.getArguments().size() <= MAX_FLAT_ARGUMENTS;
    }

    private boolean isTryResourceObjectCreation(ObjectCreationExpr expression) {
        return expression.getParentNode()
                .filter(VariableDeclarator.class::isInstance)
                .flatMap(Node::getParentNode)
                .filter(VariableDeclarationExpr.class::isInstance)
                .flatMap(Node::getParentNode)
                .filter(TryStmt.class::isInstance)
                .isPresent();
    }
}
