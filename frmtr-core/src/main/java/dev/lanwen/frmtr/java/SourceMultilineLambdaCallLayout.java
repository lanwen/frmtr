package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import dev.lanwen.frmtr.doc.Doc;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Renders block-lambda arguments whose parameter lists were already multiline in source.
 *
 * <p>This helper owns the block-lambda argument-index bookkeeping and the source-multiline block-lambda parameter
 * rendering used by {@link LambdaExpressionPrinter}. The boundary exists so the large lambda printer can keep its
 * surrounding grammar without reconstructing the multiline parameter header independently.
 *
 * <p>The expression-lambda attach-first-segment predicates and rendering this helper used to own were width-driven
 * dead code (the chain fans by width, not the author's line breaks) and have been swept; only the static
 * block-lambda-parameter utilities remain.
 */
final class SourceMultilineLambdaCallLayout {

    private SourceMultilineLambdaCallLayout() {
    }

    static Optional<Doc> blockLambdaArgumentWithSourceMultilineParameters(
            String prefix,
            NodeList<Expression> arguments,
            int lambdaIndex,
            LambdaExpr lambda,
            String leadingArguments,
            Function<List<? extends Node>, String> compactJoin,
            LambdaParameterHeaderLayout lambdaHeaders,
            JavaFormatRule<BlockStmt> lambdaBlockRenderer
    ) {
        if (
            (prefix.startsWith("new ") || prefix.equals("super") || prefix.equals("this"))
            || lambda.getParameters().size() < 2
            || !lambda.getBody().isBlockStmt()
            || !lambdaHeaders.hasSourceMultilineParameters(lambda)
        ) {
            return Optional.empty();
        }
        String trailingArguments = compactJoin.apply(arguments.subList(lambdaIndex + 1, arguments.size()));
        if (!trailingArguments.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
            Doc.concat(
                Doc.text(prefix + "(" + (leadingArguments.isEmpty() ? "" : leadingArguments + ", ")),
                lambdaHeaders.sourceMultilineForHeader(lambda),
                Doc.text(" -> "),
                lambdaBlockRenderer.format(lambda.getBody().asBlockStmt(), LayoutContext.root()),
                Doc.text(")")
            )
        );
    }

    static int blockLambdaArgumentIndex(NodeList<Expression> arguments) {
        int lambdaIndex = -1;
        for (int i = 0; i < arguments.size(); i++) {
            if (arguments.get(i) instanceof LambdaExpr lambda && lambda.getBody().isBlockStmt()) {
                if (lambdaIndex >= 0) {
                    return -1;
                }
                lambdaIndex = i;
            }
        }
        return lambdaIndex;
    }

    static boolean hasOtherLambdaArgument(NodeList<Expression> arguments, int lambdaIndex) {
        for (int i = 0; i < arguments.size(); i++) {
            if (i != lambdaIndex && arguments.get(i) instanceof LambdaExpr) {
                return true;
            }
        }
        return false;
    }
}
