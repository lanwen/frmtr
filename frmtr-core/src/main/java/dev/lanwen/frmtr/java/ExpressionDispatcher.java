package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.InstanceOfExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.SwitchExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import dev.lanwen.frmtr.doc.Doc;

/**
 * Narrows expression AST kinds after callers have already decided they need expression rendering.
 *
 * <p>This helper owns only the broad {@link Expression} subtype dispatch that chooses which specialized expression
 * printer should receive a node. The boundary exists so {@link JavaPrinter} can wire callbacks and outer syntax
 * contexts without also carrying the full expression-kind decision tree, while the specialized printers keep the
 * layout rules for assignments, calls, arrays, conditionals, lambdas, switches, and other expression shapes.
 *
 * <p>Callers still decide when an expression context is needed, how comments and formatter pragmas have already been
 * handled, and when compact source fallback is acceptable. Specialized expression printers decide their own wrapping,
 * source-sensitive formatting, and recursive expression callbacks.
 */
final class ExpressionDispatcher {
    private final AssignmentExpressionPrinter assignments;
    private final ArrayExpressionPrinter arrays;
    private final AnnotationExpressionPrinter annotationExpressions;
    private final BinaryExpressionPrinter binaries;
    private final CastExpressionPrinter casts;
    private final ConditionalExpressionPrinter conditionals;
    private final EnclosedExpressionPrinter enclosedExpressions;
    private final FieldAccessPrinter fieldAccesses;
    private final InstanceOfExpressionPrinter instanceOfExpressions;
    private final LambdaExpressionPrinter lambdas;
    private final MethodCallPrinter methodCalls;
    private final MethodReferencePrinter methodReferences;
    private final ObjectCreationPrinter objectCreations;
    private final SwitchPrinter switches;
    private final TextBlockPrinter textBlocks;
    private final CompactSourceText compactSource;

    ExpressionDispatcher(
            AssignmentExpressionPrinter assignments,
            ArrayExpressionPrinter arrays,
            AnnotationExpressionPrinter annotationExpressions,
            BinaryExpressionPrinter binaries,
            CastExpressionPrinter casts,
            ConditionalExpressionPrinter conditionals,
            EnclosedExpressionPrinter enclosedExpressions,
            FieldAccessPrinter fieldAccesses,
            InstanceOfExpressionPrinter instanceOfExpressions,
            LambdaExpressionPrinter lambdas,
            MethodCallPrinter methodCalls,
            MethodReferencePrinter methodReferences,
            ObjectCreationPrinter objectCreations,
            SwitchPrinter switches,
            TextBlockPrinter textBlocks,
            CompactSourceText compactSource) {
        this.assignments = assignments;
        this.arrays = arrays;
        this.annotationExpressions = annotationExpressions;
        this.binaries = binaries;
        this.casts = casts;
        this.conditionals = conditionals;
        this.enclosedExpressions = enclosedExpressions;
        this.fieldAccesses = fieldAccesses;
        this.instanceOfExpressions = instanceOfExpressions;
        this.lambdas = lambdas;
        this.methodCalls = methodCalls;
        this.methodReferences = methodReferences;
        this.objectCreations = objectCreations;
        this.switches = switches;
        this.textBlocks = textBlocks;
        this.compactSource = compactSource;
    }

    /**
     * Dispatches a formatted expression by AST kind.
     *
     * <p>The {@link SwitchExpr} branch is only a delegation to {@link SwitchPrinter#switchExpression(SwitchExpr)} so
     * switch formatting stays in the switch slice. This dispatcher does not own switch selectors, labels, guards,
     * entries, or switch block layout.
     */
    Doc expression(Expression expression) {
        if (expression instanceof AssignExpr assignExpr) {
            return assignments.assignment(assignExpr);
        }
        if (expression instanceof ArrayAccessExpr arrayAccessExpr) {
            return arrays.arrayAccess(arrayAccessExpr);
        }
        if (expression instanceof ArrayCreationExpr arrayCreationExpr) {
            return arrays.arrayCreation(arrayCreationExpr);
        }
        if (expression instanceof ArrayInitializerExpr arrayInitializerExpr) {
            return arrays.arrayInitializer(arrayInitializerExpr);
        }
        if (expression instanceof AnnotationExpr annotationExpr) {
            return annotationExpressions.annotation(annotationExpr);
        }
        if (expression instanceof BinaryExpr binaryExpr) {
            return binaries.binaryExpression(binaryExpr);
        }
        if (expression instanceof CastExpr castExpr) {
            return casts.castExpression(castExpr);
        }
        if (expression instanceof ConditionalExpr conditionalExpr) {
            return conditionals.conditionalExpression(conditionalExpr);
        }
        if (expression instanceof EnclosedExpr enclosedExpr) {
            return enclosedExpressions.enclosedExpression(enclosedExpr);
        }
        if (expression instanceof FieldAccessExpr fieldAccessExpr) {
            return fieldAccesses.fieldAccess(fieldAccessExpr);
        }
        if (expression instanceof InstanceOfExpr instanceOfExpr) {
            return instanceOfExpressions.instanceOfExpression(instanceOfExpr);
        }
        if (expression instanceof LambdaExpr lambdaExpr) {
            return lambdas.lambdaExpression(lambdaExpr);
        }
        if (expression instanceof MethodCallExpr methodCallExpr) {
            return methodCalls.methodCall(methodCallExpr);
        }
        if (expression instanceof MethodReferenceExpr methodReferenceExpr) {
            return methodReferences.methodReference(methodReferenceExpr);
        }
        if (expression instanceof ObjectCreationExpr objectCreationExpr) {
            return objectCreations.objectCreation(objectCreationExpr);
        }
        if (expression instanceof SwitchExpr switchExpr) {
            return switches.switchExpression(switchExpr);
        }
        if (expression instanceof TextBlockLiteralExpr textBlockLiteralExpr) {
            return textBlocks.textBlockLiteral(textBlockLiteralExpr);
        }
        return Doc.text(compactSource.compact(expression));
    }

    /**
     * Removes only the expression node's own attached comment before dispatching through normal expression rendering.
     *
     * <p>The expression is cloned first so the shared JavaParser tree keeps its original comment attachment for later
     * layout decisions; only this one rendering request sees the comment-free node.
     */
    Doc expressionWithoutOwnComment(Expression expression) {
        Expression clone = expression.clone();
        clone.removeComment();
        return expression(clone);
    }
}
