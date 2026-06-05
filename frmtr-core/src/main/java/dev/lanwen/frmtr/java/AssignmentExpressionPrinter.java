package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.AssignExpr;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Renders assignment expressions after broad expression dispatch has selected {@code target op value} syntax.
 *
 * <p>This helper owns the assignment-specific width decision tree: compact fallback assembly, the statement-width gate
 * that activates broken assignment shapes, suffixed enclosed values, binary-value continuations, anonymous-class-free
 * object creations, method-call value hooks, conditional value hooks, and nested assignment continuations. The boundary
 * exists because assignments are ordinary expressions, but they make context-specific choices about when the value should
 * move below the operator.
 *
 * <p>{@link JavaPrinter} still owns broad expression dispatch, compact source text, statement width calculations,
 * enclosed suffix handling, binary expression layout, object creation layout, method-call assignment layout, and
 * conditional assignment layout. This helper receives those decisions as callbacks and only walks the assignment
 * decision tree in the same order as the previous inline printer path.
 */
final class AssignmentExpressionPrinter {
    private final FormatterOptions options;
    private final Function<Expression, Doc> expression;
    private final Function<Node, String> compact;
    private final ToIntFunction<String> blockStatementWidth;
    private final BiFunction<Expression, Boolean, Optional<Doc>> suffixedEnclosedExpression;
    private final Predicate<BinaryExpr> shouldKeepCastDivisionContinuationFlat;
    private final BiFunction<Expression, Boolean, Doc> binaryExpressionLines;
    private final Function<ObjectCreationExpr, Doc> brokenObjectCreation;
    private final BiFunction<AssignExpr, MethodCallExpr, Optional<Doc>> methodCallAssignment;
    private final BiFunction<AssignExpr, ConditionalExpr, Optional<Doc>> conditionalAssignment;

    AssignmentExpressionPrinter(
            FormatterOptions options,
            Function<Expression, Doc> expression,
            Function<Node, String> compact,
            ToIntFunction<String> blockStatementWidth,
            BiFunction<Expression, Boolean, Optional<Doc>> suffixedEnclosedExpression,
            Predicate<BinaryExpr> shouldKeepCastDivisionContinuationFlat,
            BiFunction<Expression, Boolean, Doc> binaryExpressionLines,
            Function<ObjectCreationExpr, Doc> brokenObjectCreation,
            BiFunction<AssignExpr, MethodCallExpr, Optional<Doc>> methodCallAssignment,
            BiFunction<AssignExpr, ConditionalExpr, Optional<Doc>> conditionalAssignment) {
        this.options = options;
        this.expression = expression;
        this.compact = compact;
        this.blockStatementWidth = blockStatementWidth;
        this.suffixedEnclosedExpression = suffixedEnclosedExpression;
        this.shouldKeepCastDivisionContinuationFlat = shouldKeepCastDivisionContinuationFlat;
        this.binaryExpressionLines = binaryExpressionLines;
        this.brokenObjectCreation = brokenObjectCreation;
        this.methodCallAssignment = methodCallAssignment;
        this.conditionalAssignment = conditionalAssignment;
    }

    /**
     * Prints an assignment flat unless the whole expression statement would exceed the configured line width.
     *
     * <p>The break tree is intentionally gated by {@code flat + ";"} because assignment expressions most often appear as
     * expression statements. If that full statement fits, the formatter preserves the compact {@code target op value}
     * shape even when the value has sub-shapes that could break in other contexts.
     */
    Doc assignment(AssignExpr expression) {
        String flat = compact.apply(expression);
        if (blockStatementWidth.applyAsInt(flat + ";") > options.lineWidth()) {
            Optional<Doc> brokenAssignment = brokenAssignment(expression);
            if (brokenAssignment.isPresent()) {
                return brokenAssignment.orElseThrow();
            }
        }
        return flatAssignment(expression);
    }

    /**
     * Tries the width-triggered assignment branches in the same order as the old inline printer.
     *
     * <p>Earlier branches handle shapes that have stronger local layout constraints: enclosed suffixes must stay attached
     * to their broken receiver, binary expressions have operator-position rules, constructor calls can break their
     * argument list, and ternaries/method calls have dedicated assignment hooks. Only when none of those cases applies
     * does the caller fall back to flat assignment assembly.
     */
    private Optional<Doc> brokenAssignment(AssignExpr expression) {
        Optional<Doc> suffixedEnclosedValue = assignmentWithSuffixedEnclosedValue(expression);
        if (suffixedEnclosedValue.isPresent()) {
            return suffixedEnclosedValue;
        }
        Optional<Doc> binaryValue = assignmentWithBinaryValue(expression);
        if (binaryValue.isPresent()) {
            return binaryValue;
        }
        Optional<Doc> objectCreationValue = assignmentWithObjectCreationValue(expression);
        if (objectCreationValue.isPresent()) {
            return objectCreationValue;
        }
        Optional<Doc> methodCallValue = assignmentWithMethodCallValue(expression);
        if (methodCallValue.isPresent()) {
            return methodCallValue;
        }
        Optional<Doc> conditionalValue = assignmentWithConditionalValue(expression);
        if (conditionalValue.isPresent()) {
            return conditionalValue;
        }
        return assignmentWithNestedAssignmentValue(expression);
    }

    /**
     * Lets parenthesized/enclosed suffix renderers keep the suffix attached to their own broken receiver.
     *
     * <p>This is tried before value-kind checks because a method call, array access, or method reference after an
     * enclosed receiver has already decided where its suffix must appear. The assignment only supplies the
     * {@code target op} prefix and leaves that suffix-sensitive shape intact.
     */
    private Optional<Doc> assignmentWithSuffixedEnclosedValue(AssignExpr expression) {
        Optional<Doc> suffixedEnclosedValue = suffixedEnclosedExpression.apply(expression.getValue(), true);
        return suffixedEnclosedValue.map(value -> Doc.concat(
                this.expression.apply(expression.getTarget()),
                Doc.text(" " + expression.getOperator().asString() + " "),
                value));
    }

    /**
     * Moves a binary value below the assignment operator while preserving binary-specific continuation policy.
     *
     * <p>The cast-division exception delegates the decision to {@link BinaryExpressionPrinter}: {@code x = (T) a / b}
     * stays as one continuation line when that line still fits, so the cast remains visually attached to the divided
     * value. Other binary values use the shared broken binary lines because operator placement and operand wrapping are
     * not assignment-specific decisions.
     */
    private Optional<Doc> assignmentWithBinaryValue(AssignExpr expression) {
        if (!(expression.getValue() instanceof BinaryExpr binaryExpression)) {
            return Optional.empty();
        }
        if (shouldKeepCastDivisionContinuationFlat.test(binaryExpression)) {
            return Optional.of(Doc.concat(
                    this.expression.apply(expression.getTarget()),
                    Doc.text(" " + expression.getOperator().asString()),
                    Doc.indent(Doc.concat(Doc.HARD_LINE, this.expression.apply(binaryExpression)))));
        }
        return Optional.of(Doc.concat(
                this.expression.apply(expression.getTarget()),
                Doc.text(" " + expression.getOperator().asString()),
                Doc.indent(Doc.concat(Doc.HARD_LINE, binaryExpressionLines.apply(expression.getValue(), true)))));
    }

    /**
     * Breaks constructor-call arguments under the assignment when there is no anonymous class body.
     *
     * <p>Anonymous classes own the layout after their constructor header, so this branch only handles ordinary object
     * creations and leaves anonymous-class member sequencing to {@link ObjectCreationPrinter}.
     */
    private Optional<Doc> assignmentWithObjectCreationValue(AssignExpr expression) {
        if (!(expression.getValue() instanceof ObjectCreationExpr objectCreationExpression)
                || objectCreationExpression.getAnonymousClassBody().isPresent()) {
            return Optional.empty();
        }
        return Optional.of(Doc.concat(
                this.expression.apply(expression.getTarget()),
                Doc.text(" " + expression.getOperator().asString() + " "),
                brokenObjectCreation.apply(objectCreationExpression)));
    }

    /**
     * Defers method-call assignment wrapping to the method-call printer.
     *
     * <p>That printer already knows whether the call prefix fits after {@code target op} and whether the argument list
     * can break without moving the whole value. The assignment helper only asks for that specialized shape when the value
     * is a direct method call.
     */
    private Optional<Doc> assignmentWithMethodCallValue(AssignExpr expression) {
        if (!(expression.getValue() instanceof MethodCallExpr methodCall)) {
            return Optional.empty();
        }
        return methodCallAssignment.apply(expression, methodCall);
    }

    /**
     * Defers ternary assignment wrapping to the conditional printer.
     *
     * <p>Conditional expressions have their own fork for keeping the condition on the assignment line versus moving the
     * whole ternary below the operator, so this helper only routes direct conditional values into that existing policy.
     */
    private Optional<Doc> assignmentWithConditionalValue(AssignExpr expression) {
        if (!(expression.getValue() instanceof ConditionalExpr conditionalExpression)) {
            return Optional.empty();
        }
        return conditionalAssignment.apply(expression, conditionalExpression);
    }

    /**
     * Moves a nested assignment onto the continuation line after the outer operator.
     *
     * <p>Nested assignments are not passed through another specialized helper because the existing expression dispatch
     * can render the inner assignment recursively once the outer assignment has chosen the continuation boundary.
     */
    private Optional<Doc> assignmentWithNestedAssignmentValue(AssignExpr expression) {
        if (!(expression.getValue() instanceof AssignExpr nestedAssignment)) {
            return Optional.empty();
        }
        return Optional.of(Doc.concat(
                this.expression.apply(expression.getTarget()),
                Doc.text(" " + expression.getOperator().asString()),
                Doc.indent(Doc.concat(Doc.HARD_LINE, this.expression.apply(nestedAssignment)))));
    }

    private Doc flatAssignment(AssignExpr expression) {
        return Doc.concat(
                this.expression.apply(expression.getTarget()),
                Doc.text(" " + expression.getOperator().asString() + " "),
                this.expression.apply(expression.getValue()));
    }
}
