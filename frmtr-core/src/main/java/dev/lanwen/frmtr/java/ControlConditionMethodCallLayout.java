package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
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

    private final SourceShapePolicy sourceShapePolicy;

    private final FormatterOptions options;

    private final Function<Expression, Doc> expressionRenderer;

    private final Function<Expression, String> compact;

    private final Function<List<? extends Node>, String> compactJoin;

    private final ToIntFunction<String> blockStatementWidth;

    ControlConditionMethodCallLayout(
            SourceShapePolicy sourceShapePolicy,
            FormatterOptions options,
            Function<Expression, Doc> expressionRenderer,
            Function<Expression, String> compact,
            Function<List<? extends Node>, String> compactJoin,
            ToIntFunction<String> blockStatementWidth
    ) {
        this.sourceShapePolicy = sourceShapePolicy;
        this.options = options;
        this.expressionRenderer = expressionRenderer;
        this.compact = compact;
        this.compactJoin = compactJoin;
        this.blockStatementWidth = blockStatementWidth;
    }

    Optional<Doc> brokenCondition(MethodCallExpr expression) {
        if (expression.getArguments().isEmpty() || !expression.getAllContainedComments().isEmpty()) {
            return Optional.empty();
        }
        String prefix = methodCallPrefix(expression);
        if (blockStatementWidth.applyAsInt("if (" + prefix + "(") > options.lineWidth()) {
            return Optional.empty();
        }
        return Optional.of(parenthesizedBrokenMethodCall(expression, prefix));
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
