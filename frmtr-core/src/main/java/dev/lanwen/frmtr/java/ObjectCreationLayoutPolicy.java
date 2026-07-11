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

    private final SourceShapePolicy sourceShapePolicy;

    ObjectCreationLayoutPolicy(SourceShapePolicy sourceShapePolicy) {
        this.sourceShapePolicy = sourceShapePolicy;
    }

    /**
     * Reports whether a constructor's argument list is rendered SOURCE-NEUTRALLY — always by the width-driven
     * {@code Doc.group}, never by a source-multiline-preserving branch — so a chain whose root is this constructor can be
     * fanned identically on every pass regardless of how the author laid the arguments out.
     *
     * <p>ANY non-anonymous, comment-free, non-try-resource constructor renders its arguments through
     * {@link ObjectCreationPrinter#objectCreation}'s (or {@link ObjectCreationPrinter#widthDrivenObjectCreation}'s)
     * width-driven {@code Doc.group}, collapsing or breaking them purely by the rendered column, independent of argument
     * count: a four-plus-argument constructor root is as column-invariant as a two-argument one, so the
     * object-creation-ROOT arm of {@link VariableInitializerLayout#variableInitializerFanBestFitting} may fan it
     * source-neutrally too. Try resources render through the try-statement printer's own header-width layout, so they are
     * excluded here; anonymous bodies keep their own body-owned layout after the header.
     */
    boolean constructorArgumentsAreWidthDriven(ObjectCreationExpr expression) {
        return expression.getAnonymousClassBody().isEmpty()
            && expression.getAllContainedComments().isEmpty()
            && !isTryResourceObjectCreation(expression);
    }

    /**
     * Reports whether a constructor root can stay compact (its whole argument list on one line) when a surrounding
     * method-call chain is forced to break — purely a WIDTH verdict.
     *
     * <p>A constructor root stays compact exactly when its compact rendering fits the line, regardless of how many
     * arguments it has or how the author laid them out. A wide root (e.g. {@code new LogValidator(12 args)}) fails
     * {@code compactWidth <= lineWidth} and so breaks its argument list; a narrow four-plus-argument root that fits stays
     * on one line, identically on every pass.
     */
    boolean canKeepCompactChainRoot(ObjectCreationExpr expression, int compactWidth, int lineWidth) {
        return expression.getAnonymousClassBody().isEmpty()
            && expression.getAllContainedComments().isEmpty()
            && compactWidth <= lineWidth;
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
