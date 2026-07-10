package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.TextBlockLiteralExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import dev.lanwen.frmtr.doc.Doc;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Preserves source-multiline lambda call bodies when call, chain, and return contexts need the same shape.
 *
 * <p>This helper owns the source-shape predicates for expression-lambda bodies that are method calls, the attached
 * first-segment rendering used by method chains, and block-lambda parameter lists that were already multiline in
 * source. The boundary exists so the large call, lambda, and return printers can keep their surrounding grammar and
 * width decisions without each reconstructing lambda parameters or method-call body arguments independently.
 *
 * <p>Callers still own root promotion, chain suffix placement, object-construction policy, and final statement syntax.
 * This helper only answers whether a source-multiline lambda call body should stay attached, and renders the lambda
 * fragment once that surrounding context has been chosen.
 */
final class SourceMultilineLambdaCallLayout {

    private final SourceShapePolicy sourceShapePolicy;

    private final Function<Expression, Doc> expressionRenderer;

    private final Function<LambdaExpr, String> lambdaParameters;

    private final Function<MethodCallExpr, String> methodCallPrefix;

    private final Function<MethodCallExpr, String> chainSegmentPrefix;

    private final MethodCallArgumentRenderer methodCallArguments;

    SourceMultilineLambdaCallLayout(
            SourceShapePolicy sourceShapePolicy,
            Function<Expression, Doc> expressionRenderer,
            Function<LambdaExpr, String> lambdaParameters,
            Function<MethodCallExpr, String> methodCallPrefix,
            Function<MethodCallExpr, String> chainSegmentPrefix,
            MethodCallArgumentRenderer methodCallArguments
    ) {
        this.sourceShapePolicy = sourceShapePolicy;
        this.expressionRenderer = expressionRenderer;
        this.lambdaParameters = lambdaParameters;
        this.methodCallPrefix = methodCallPrefix;
        this.chainSegmentPrefix = chainSegmentPrefix;
        this.methodCallArguments = methodCallArguments;
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

    Optional<Doc> attachedFirstSegment(Expression root, MethodCallExpr firstCall) {
        if (
            firstCall.getArgument(0) instanceof LambdaExpr lambda
            && lambdaBodyMethodCall(lambda).isPresent()
        ) {
            MethodCallExpr body = lambdaBodyMethodCall(lambda).orElseThrow();
            if (
                body.getArguments().isEmpty()
                || body.getArguments().stream().anyMatch(argument -> argument instanceof LambdaExpr)
                || !lambdaBodyStartsAfterHeader(lambda)
            ) {
                return Optional.empty();
            }
            String bodyPrefix = methodCallPrefix.apply(body);
            return Optional.of(
                Doc.concat(
                    expressionRenderer.apply(root),
                    Doc.text(
                        chainSegmentPrefix.apply(firstCall) + "(" + lambdaParameters.apply(lambda) + " -> " + bodyPrefix + "("
                    ),
                    Doc.indent(
                        Doc.concat(
                            Doc.HARD_LINE,
                            methodCallArguments.render(bodyPrefix, body.getArguments(), Doc.HARD_LINE)
                        )
                    ),
                    Doc.HARD_LINE,
                    Doc.text("))")
                )
            );
        }
        return Optional.empty();
    }

    Optional<AttachedFirstSegment> attachedFirstSegmentPlan(MethodCallExpr firstCall) {
        if (!canAttachExpressionLambdaBody(firstCall)) {
            return Optional.empty();
        }
        return Optional.of(
            new AttachedFirstSegment(chainSegmentPrefix(firstCall))
        );
    }

    boolean canAttachExpressionLambdaBody(MethodCallExpr call) {
        if (call.getArguments().isEmpty() || !(call.getArgument(0) instanceof LambdaExpr lambda)) {
            return false;
        }
        Optional<MethodCallExpr> body = lambdaBodyMethodCall(lambda);
        return body.isPresent() && lambdaBodyStartsAfterHeader(lambda);
    }

    private String chainSegmentPrefix(MethodCallExpr expression) {
        return chainSegmentPrefix.apply(expression);
    }

    private Optional<MethodCallExpr> lambdaBodyMethodCall(LambdaExpr expression) {
        return expression.getExpressionBody()
                .filter(MethodCallExpr.class::isInstance)
                .map(MethodCallExpr.class::cast)
                .filter(methodCall -> methodCall.getScope().filter(ObjectCreationExpr.class::isInstance).isEmpty());
    }

    // The #190 segment-column keystone: whether an expression-lambda method-call body "starts after the arrow line" in
    // source is no longer read. It used to gate the attach-first-segment shape ({@code root.call(p -> body.call(}⏎ broken
    // args ⏎{@code ))}) that hugs the body-call opener and keeps the enclosing chain compact — but that decision is now
    // width-driven: the chain fans one selector per line when it fits at its true column (the source-neutral
    // {@code chainFanOut} / {@code sourceNeutralExpressionLambdaSegment} path), and a segment still opens its own body-call
    // arguments only on genuine overflow. Reading the author's arrow line here made the hug fire on a body written below
    // the arrow and vanish once a prior pass attached it, so the shape flipped attach⇄fan forever (the
    // {@code lambda-expression-argument-opener} fixture's {@code assertThatThrownBy(() -> …)} /
    // {@code probe.withVirtualTime(() -> …)} cases). Constant false retires that read; the enclosing
    // {@code canAttachExpressionLambdaBody} / {@code attachedFirstSegment} shapes it fed are now inert and the width path
    // owns the decision, matching the sibling constant-false {@code bodyFirstSourceLineFits} retirement.
    private boolean lambdaBodyStartsAfterHeader(LambdaExpr expression) {
        return false;
    }

    @FunctionalInterface
    interface MethodCallArgumentRenderer {
        Doc render(String prefix, NodeList<Expression> arguments, Doc line);
    }

    record AttachedFirstSegment(String chainSegmentPrefix) {}
}
