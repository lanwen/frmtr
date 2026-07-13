package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayAccessExpr;
import com.github.javaparser.ast.expr.ArrayCreationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.ClassExpr;
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
import com.github.javaparser.ast.expr.UnaryExpr;
import dev.lanwen.frmtr.doc.Doc;

/**
 * Routes already-formattable expression content to expression-specific printers.
 *
 * <p>This helper owns only the broad {@link Expression} subtype dispatch after {@link ExpressionRuleEnvelope} has
 * selected expression content rendering. The boundary exists so {@link JavaPrinter} can wire callbacks and outer syntax
 * contexts without also carrying the full expression-kind decision tree, while the specialized printers keep the layout
 * rules for assignments, calls, arrays, conditionals, lambdas, switches, and other expression shapes.
 *
 * <p>Callers still decide when expression content rendering is allowed and provide each specialized renderer plus the
 * compact fallback source policy. Specialized expression printers decide their own wrapping, source-sensitive
 * formatting, and recursive expression callbacks.
 */
final class ExpressionDispatcher {

    private final JavaFormatRule<AssignExpr> assignments;

    private final JavaFormatRule<ArrayAccessExpr> arrayAccesses;

    private final JavaFormatRule<ArrayCreationExpr> arrayCreations;

    private final JavaFormatRule<ArrayInitializerExpr> arrayInitializers;

    private final JavaFormatRule<AnnotationExpr> annotationExpressions;

    private final JavaFormatRule<BinaryExpr> binaries;

    private final JavaFormatRule<CastExpr> casts;

    private final JavaFormatRule<ClassExpr> classExpressions;

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

    private final JavaFormatRule<UnaryExpr> unaries;

    private final CompactSourceText compactSource;

    ExpressionDispatcher(
            JavaFormatRule<AssignExpr> assignments,
            JavaFormatRule<ArrayAccessExpr> arrayAccesses,
            JavaFormatRule<ArrayCreationExpr> arrayCreations,
            JavaFormatRule<ArrayInitializerExpr> arrayInitializers,
            JavaFormatRule<AnnotationExpr> annotationExpressions,
            JavaFormatRule<BinaryExpr> binaries,
            JavaFormatRule<CastExpr> casts,
            JavaFormatRule<ClassExpr> classExpressions,
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
            JavaFormatRule<UnaryExpr> unaries,
            CompactSourceText compactSource
    ) {
        this.assignments = assignments;
        this.arrayAccesses = arrayAccesses;
        this.arrayCreations = arrayCreations;
        this.arrayInitializers = arrayInitializers;
        this.annotationExpressions = annotationExpressions;
        this.binaries = binaries;
        this.casts = casts;
        this.classExpressions = classExpressions;
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
        this.unaries = unaries;
        this.compactSource = compactSource;
    }

    /**
     * Dispatches a formatted expression by AST kind.
     *
     * <p>The {@link SwitchExpr} branch is only a delegation to {@link SwitchPrinter#switchExpression(SwitchExpr)} so
     * switch formatting stays in the switch slice. This dispatcher does not own switch selectors, labels, guards,
     * entries, or switch block layout.
     */
    Doc expressionContent(Expression expression, LayoutContext layout) {
        return switch (expression) {
            case AssignExpr assignExpr -> assignments.format(assignExpr, layout);
            case ArrayAccessExpr arrayAccessExpr -> arrayAccesses.format(arrayAccessExpr, layout);
            case ArrayCreationExpr arrayCreationExpr -> arrayCreations.format(arrayCreationExpr, layout);
            case ArrayInitializerExpr arrayInitializerExpr -> arrayInitializers.format(arrayInitializerExpr, layout);
            case AnnotationExpr annotationExpr -> annotationExpressions.format(annotationExpr, layout);
            case BinaryExpr binaryExpr -> binaries.format(binaryExpr, layout);
            case CastExpr castExpr -> casts.format(castExpr, layout);
            case ClassExpr classExpr -> classExpressions.format(classExpr, layout);
            case ConditionalExpr conditionalExpr -> conditionals.format(conditionalExpr, layout);
            case EnclosedExpr enclosedExpr -> enclosedExpressions.format(enclosedExpr, layout);
            case FieldAccessExpr fieldAccessExpr -> fieldAccesses.format(fieldAccessExpr, layout);
            case InstanceOfExpr instanceOfExpr -> instanceOfExpressions.format(instanceOfExpr, layout);
            case LambdaExpr lambdaExpr -> lambdas.format(lambdaExpr, layout);
            case MethodCallExpr methodCallExpr -> methodCalls.format(methodCallExpr, layout);
            case MethodReferenceExpr methodReferenceExpr -> methodReferences.format(methodReferenceExpr, layout);
            case ObjectCreationExpr objectCreationExpr -> objectCreations.format(objectCreationExpr, layout);
            case SwitchExpr switchExpr -> switches.format(switchExpr, layout);
            case TextBlockLiteralExpr textBlockLiteralExpr -> textBlocks.format(textBlockLiteralExpr, layout);
            case UnaryExpr unaryExpr -> unaries.format(unaryExpr, layout);
            default -> Doc.text(compactSource.compact(expression));
        };
    }
}
