package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;

/**
 * Block-lambda argument-index utilities shared across call-layout helpers.
 *
 * <p>This helper owns the block-lambda-index scan and the other-lambda-argument check used by
 * {@link BlockLambdaArgumentLayout} and {@link LambdaExpressionPrinter}. The boundary exists so those callers share
 * one authority for "where is the single block-lambda, and is there another lambda?" without duplicating the scan.
 */
final class SourceMultilineLambdaCallLayout {

    private SourceMultilineLambdaCallLayout() {
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
