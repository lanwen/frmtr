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
    private final JavaFormatRule<AssignExpr> assignments;
    private final JavaFormatRule<ArrayAccessExpr> arrayAccesses;
    private final JavaFormatRule<ArrayCreationExpr> arrayCreations;
    private final JavaFormatRule<ArrayInitializerExpr> arrayInitializers;
    private final JavaFormatRule<AnnotationExpr> annotationExpressions;
    private final JavaFormatRule<BinaryExpr> binaries;
    private final JavaFormatRule<CastExpr> casts;
    private final JavaFormatRule<ConditionalExpr> conditionals;
    private final JavaFormatRule<EnclosedExpr> enclosedExpressions;
    private final JavaFormatRule<FieldAccessExpr> fieldAccesses;
    private final JavaFormatRule<InstanceOfExpr> instanceOfExpressions;
    private final JavaFormatRule<LambdaExpr> lambdas;
    private final JavaFormatRule<MethodCallExpr> methodCalls;
    private final JavaFormatRule<MethodReferenceExpr> methodReferences;
    private final JavaFormatRule<ObjectCreationExpr> objectCreations;
    private final JavaFormatRule<SwitchExpr> switches;
    private final JavaFormatRule<TextBlockLiteralExpr> textBlocks;
    private final CompactSourceText compactSource;

    ExpressionDispatcher(
            JavaFormatRule<AssignExpr> assignments,
            JavaFormatRule<ArrayAccessExpr> arrayAccesses,
            JavaFormatRule<ArrayCreationExpr> arrayCreations,
            JavaFormatRule<ArrayInitializerExpr> arrayInitializers,
            JavaFormatRule<AnnotationExpr> annotationExpressions,
            JavaFormatRule<BinaryExpr> binaries,
            JavaFormatRule<CastExpr> casts,
            JavaFormatRule<ConditionalExpr> conditionals,
            JavaFormatRule<EnclosedExpr> enclosedExpressions,
            JavaFormatRule<FieldAccessExpr> fieldAccesses,
            JavaFormatRule<InstanceOfExpr> instanceOfExpressions,
            JavaFormatRule<LambdaExpr> lambdas,
            JavaFormatRule<MethodCallExpr> methodCalls,
            JavaFormatRule<MethodReferenceExpr> methodReferences,
            JavaFormatRule<ObjectCreationExpr> objectCreations,
            JavaFormatRule<SwitchExpr> switches,
            JavaFormatRule<TextBlockLiteralExpr> textBlocks,
            CompactSourceText compactSource) {
        this.assignments = assignments;
        this.arrayAccesses = arrayAccesses;
        this.arrayCreations = arrayCreations;
        this.arrayInitializers = arrayInitializers;
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
            return assignments.format(assignExpr);
        }
        if (expression instanceof ArrayAccessExpr arrayAccessExpr) {
            return arrayAccesses.format(arrayAccessExpr);
        }
        if (expression instanceof ArrayCreationExpr arrayCreationExpr) {
            return arrayCreations.format(arrayCreationExpr);
        }
        if (expression instanceof ArrayInitializerExpr arrayInitializerExpr) {
            return arrayInitializers.format(arrayInitializerExpr);
        }
        if (expression instanceof AnnotationExpr annotationExpr) {
            return annotationExpressions.format(annotationExpr);
        }
        if (expression instanceof BinaryExpr binaryExpr) {
            return binaries.format(binaryExpr);
        }
        if (expression instanceof CastExpr castExpr) {
            return casts.format(castExpr);
        }
        if (expression instanceof ConditionalExpr conditionalExpr) {
            return conditionals.format(conditionalExpr);
        }
        if (expression instanceof EnclosedExpr enclosedExpr) {
            return enclosedExpressions.format(enclosedExpr);
        }
        if (expression instanceof FieldAccessExpr fieldAccessExpr) {
            return fieldAccesses.format(fieldAccessExpr);
        }
        if (expression instanceof InstanceOfExpr instanceOfExpr) {
            return instanceOfExpressions.format(instanceOfExpr);
        }
        if (expression instanceof LambdaExpr lambdaExpr) {
            return lambdas.format(lambdaExpr);
        }
        if (expression instanceof MethodCallExpr methodCallExpr) {
            return methodCalls.format(methodCallExpr);
        }
        if (expression instanceof MethodReferenceExpr methodReferenceExpr) {
            return methodReferences.format(methodReferenceExpr);
        }
        if (expression instanceof ObjectCreationExpr objectCreationExpr) {
            return objectCreations.format(objectCreationExpr);
        }
        if (expression instanceof SwitchExpr switchExpr) {
            return switches.format(switchExpr);
        }
        if (expression instanceof TextBlockLiteralExpr textBlockLiteralExpr) {
            return textBlocks.format(textBlockLiteralExpr);
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
