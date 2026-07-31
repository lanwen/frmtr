package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import dev.lanwen.frmtr.doc.Doc;
import java.util.List;
import java.util.function.Function;

/**
 * Owns method-call operand layout inside parenthesized control conditions.
 *
 * <p>This helper exists because control conditions need a narrow method-call policy that differs from ordinary
 * expression dispatch: source-multiline method-call operands must stay broken inside logical terms, and over-wide
 * top-level method-call conditions need their argument list measured with the surrounding {@code if (...)} budget.
 * {@link ControlConditionPrinter} still chooses when a condition should be broken and owns the parenthesized condition
 * wrapper; this helper only renders the method-call operand or reports method-call shape facts needed for that choice.
 */
final class ControlConditionMethodCallLayout {

    private final SourceShapePolicy sourceShapePolicy;

    private final Function<Expression, Doc> expressionRenderer;

    private final Function<Expression, String> compact;

    private final Function<List<? extends Node>, String> compactJoin;

    ControlConditionMethodCallLayout(
            SourceShapePolicy sourceShapePolicy,
            Function<Expression, Doc> expressionRenderer,
            Function<Expression, String> compact,
            Function<List<? extends Node>, String> compactJoin
    ) {
        this.sourceShapePolicy = sourceShapePolicy;
        this.expressionRenderer = expressionRenderer;
        this.compact = compact;
        this.compactJoin = compactJoin;
    }

    /**
     * Whether a method-call condition can offer the broken one-argument-per-line shape at all: needs at least one
     * argument, and must be comment-free so building the shape unconditionally never double-claims a comment.
     */
    boolean brokenConditionEligible(MethodCallExpr expression) {
        return !expression.getArguments().isEmpty() && !sourceShapePolicy.hasContainedComments(expression);
    }

    /**
     * Builds the broken one-argument-per-line method-call condition unconditionally; callers rank it against sibling
     * shapes at the true rendered column instead of pre-filtering by an estimated opener width. Only valid once
     * {@link #brokenConditionEligible(MethodCallExpr)} holds.
     */
    Doc brokenCondition(MethodCallExpr expression) {
        return parenthesizedBrokenMethodCall(expression, methodCallPrefix(expression));
    }

    boolean hasComplexArgument(MethodCallExpr expression) {
        return expression.getArguments()
                .stream()
                .anyMatch(argument -> !(
                    argument.isNameExpr()
                        || argument.isFieldAccessExpr()
                        || argument.isThisExpr()
                        || argument.isSuperExpr()
                        || argument.isLiteralExpr()
                ));
    }

    private Doc parenthesizedBrokenMethodCall(MethodCallExpr expression, String prefix) {
        Doc argumentLines = Doc.join(
            Doc.concat(Doc.text(","), Doc.HARD_LINE),
            expression.getArguments()
                    .stream()
                    .map(expressionRenderer)
                    .toList()
        );
        return Doc.concat(
            Doc.text("(" + prefix + "("),
            Doc.indent(Doc.indent(Doc.concat(Doc.HARD_LINE, argumentLines))),
            Doc.indent(
                Doc.concat(
                    Doc.HARD_LINE,
                    Doc.text("))")
                )
            )
        );
    }

    private String methodCallPrefix(MethodCallExpr expression) {
        return expression.getScope().map(scope -> compact.apply(scope) + ".").orElse("")
            + expression.getTypeArguments()
                    .map(typeArguments -> "<" + compactJoin.apply(typeArguments) + ">")
                    .orElse("")
            + expression.getNameAsString();
    }
}
