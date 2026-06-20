package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.UnaryExpr;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.ToIntFunction;

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

    private final SourceShape sourceShape;

    private final FormatterOptions options;

    private final Function<Expression, Doc> expressionRenderer;

    private final Function<Expression, String> compact;

    private final Function<List<? extends Node>, String> compactJoin;

    private final Function<MethodCallExpr, Optional<Doc>> forcedMethodCallChain;

    private final ToIntFunction<String> blockStatementWidth;

    ControlConditionMethodCallLayout(
            SourceShape sourceShape,
            FormatterOptions options,
            Function<Expression, Doc> expressionRenderer,
            Function<Expression, String> compact,
            Function<List<? extends Node>, String> compactJoin,
            Function<MethodCallExpr, Optional<Doc>> forcedMethodCallChain,
            ToIntFunction<String> blockStatementWidth
    ) {
        this.sourceShape = sourceShape;
        this.options = options;
        this.expressionRenderer = expressionRenderer;
        this.compact = compact;
        this.compactJoin = compactJoin;
        this.forcedMethodCallChain = forcedMethodCallChain;
        this.blockStatementWidth = blockStatementWidth;
    }

    Optional<Doc> brokenCondition(MethodCallExpr expression) {
        if (expression.getArguments().isEmpty() || !expression.getAllContainedComments().isEmpty()) {
            return Optional.empty();
        }
        Optional<Doc> sourceMultilineChain = parenthesizedSourceMultilineMethodCallChain(expression);
        if (sourceMultilineChain.isPresent()) {
            return sourceMultilineChain;
        }
        String prefix = methodCallPrefix(expression);
        if (blockStatementWidth.applyAsInt("if (" + prefix + "(") > options.lineWidth()) {
            return Optional.empty();
        }
        return Optional.of(parenthesizedBrokenMethodCall(expression, prefix));
    }

    /**
     * Renders a source-multiline method-call logical operand without compacting its argument list back into the logical
     * line.
     */
    Optional<Doc> sourceMultilineLogicalOperand(Expression expression) {
        if (
            expression instanceof MethodCallExpr methodCall
            && sourceShape.methodCallOperandSpansMultipleLines(methodCall)
        ) {
            return forcedMethodCallChain.apply(methodCall)
                    .or(() -> Optional.of(brokenSourceMultilineMethodCall(methodCall)));
        }
        if (
            expression instanceof UnaryExpr unaryExpr
            && unaryExpr.getOperator() == UnaryExpr.Operator.LOGICAL_COMPLEMENT
            && unaryExpr.getExpression() instanceof MethodCallExpr methodCall
            && sourceShape.methodCallOperandSpansMultipleLines(methodCall)
        ) {
            return forcedMethodCallChain.apply(methodCall)
                    .map(chain -> Doc.concat(Doc.text("!"), chain))
                    .or(() -> Optional.of(Doc.concat(Doc.text("!"), brokenSourceMultilineMethodCall(methodCall))));
        }
        return Optional.empty();
    }

    boolean sourceMultilineLogicalConditionHasMethodCallOperand(Expression expression) {
        if (expression instanceof BinaryExpr binaryExpr && isLogicalConditionOperator(binaryExpr)) {
            return sourceMultilineLogicalConditionHasMethodCallOperand(binaryExpr.getLeft())
                || sourceMultilineLogicalConditionHasMethodCallOperand(binaryExpr.getRight());
        }
        if (expression instanceof EnclosedExpr enclosedExpr) {
            return sourceMultilineLogicalConditionHasMethodCallOperand(enclosedExpr.getInner());
        }
        return sourceMultilineLogicalOperand(expression).isPresent();
    }

    boolean sourceMultilineArgumentsStartAfterName(MethodCallExpr expression) {
        return sourceShape.methodCallFirstArgumentStartsAfterName(expression);
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

    private Optional<Doc> parenthesizedSourceMultilineMethodCallChain(MethodCallExpr expression) {
        if (!sourceShape.spansMultipleLines(expression)) {
            return Optional.empty();
        }
        return forcedMethodCallChain.apply(expression)
                .map(chain -> Doc.concat(
                        Doc.text("("),
                        Doc.indent(Doc.concat(Doc.HARD_LINE, chain)),
                        Doc.HARD_LINE,
                        Doc.text(")")
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

    private Doc brokenSourceMultilineMethodCall(MethodCallExpr expression) {
        String prefix = methodCallPrefix(expression);
        Doc argumentLines = Doc.join(
            Doc.concat(Doc.text(","), Doc.HARD_LINE),
            expression.getArguments()
                    .stream()
                    .map(expressionRenderer)
                    .toList()
        );
        return Doc.concat(
            Doc.text(prefix + "("),
            Doc.indent(Doc.concat(Doc.HARD_LINE, argumentLines)),
            Doc.HARD_LINE,
            Doc.text(")")
        );
    }

    private String methodCallPrefix(MethodCallExpr expression) {
        return expression.getScope().map(scope -> compact.apply(scope) + ".").orElse("")
            + expression.getTypeArguments()
                    .map(typeArguments -> "<" + compactJoin.apply(typeArguments) + ">")
                    .orElse("")
            + expression.getNameAsString();
    }

    private boolean isLogicalConditionOperator(BinaryExpr expression) {
        return expression.getOperator() == BinaryExpr.Operator.AND
            || expression.getOperator() == BinaryExpr.Operator.OR;
    }
}
