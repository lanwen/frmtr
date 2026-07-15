package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Renders method-call expression bodies that start on an expression-lambda header.
 *
 * <p>This helper owns the method-call-chain body shapes for {@code parameters -> body} after
 * {@link LambdaExpressionPrinter} has chosen expression-lambda syntax. The boundary keeps lambda parameter/header
 * selection in the lambda printer while method-call selector text, argument text, and chain-style continuations stay
 * with the expression-lambda argument layout helpers.
 *
 * <p>Callers still decide whether the lambda itself can use this shape in their surrounding context.
 */
final class ExpressionLambdaMethodCallBodyLayout {

    private final SourceShapePolicy sourceShapePolicy;

    private final FormatterOptions options;

    private final JavaFormatRule<Expression> expressionRenderer;

    private final Function<List<? extends Node>, String> compactJoin;

    private final Function<MethodCallExpr, String> methodCallPrefix;

    private final Function<MethodCallExpr, String> methodCallSelector;

    private final BiFunction<NodeList<Expression>, Doc, Doc> methodCallArgumentList;

    private final BiFunction<String, MethodCallExpr, Optional<Doc>> packedMethodCallChainBodyRenderer;

    private final ToIntFunction<String> expressionFirstLineWidth;

    ExpressionLambdaMethodCallBodyLayout(
            SourceShapePolicy sourceShapePolicy,
            FormatterOptions options,
            JavaFormatRule<Expression> expressionRenderer,
            Function<List<? extends Node>, String> compactJoin,
            Function<MethodCallExpr, String> methodCallPrefix,
            Function<MethodCallExpr, String> methodCallSelector,
            BiFunction<NodeList<Expression>, Doc, Doc> methodCallArgumentList,
            BiFunction<String, MethodCallExpr, Optional<Doc>> packedMethodCallChainBodyRenderer,
            ToIntFunction<String> expressionFirstLineWidth
    ) {
        this.sourceShapePolicy = sourceShapePolicy;
        this.options = options;
        this.expressionRenderer = expressionRenderer;
        this.compactJoin = compactJoin;
        this.methodCallPrefix = methodCallPrefix;
        this.methodCallSelector = methodCallSelector;
        this.methodCallArgumentList = methodCallArgumentList;
        this.packedMethodCallChainBodyRenderer = packedMethodCallChainBodyRenderer;
        this.expressionFirstLineWidth = expressionFirstLineWidth;
    }

    Optional<Doc> bodyWithHeader(String parameters, MethodCallExpr methodCall) {
        Optional<Doc> scopedCallBody = scopedCallBodyWithHeader(parameters, methodCall);
        if (scopedCallBody.isPresent()) {
            return scopedCallBody;
        }
        return packedMethodCallChainBodyRenderer.apply(parameters + " ->", methodCall)
                .map(body -> Doc.concat(Doc.text(parameters + " -> "), body));
    }

    private Optional<Doc> scopedCallBodyWithHeader(String parameters, MethodCallExpr methodCall) {
        if (
            !methodCall.getArguments().isEmpty()
            || methodCall.getScope().filter(MethodCallExpr.class::isInstance).isEmpty()
            || sourceShapePolicy.hasContainedComments(methodCall)
        ) {
            return Optional.empty();
        }
        MethodCallExpr scopeCall = (MethodCallExpr) methodCall.getScope().orElseThrow();
        if (scopeCall.getArguments().isEmpty()) {
            return Optional.empty();
        }
        // The single-argument scope-call branch below renders {@code scopeCall.getScope()} on the header line, so a
        // bare-call scope ({@code assertThat(x).isNull()} whose {@code assertThat(x)} has no receiver) has nothing to
        // place there. Bodies of this shape route through this layout, so guard the empty-scope single-argument case and
        // defer to the packed/broken body renderer rather than throwing on the missing scope.
        if (scopeCall.getArguments().size() <= 1 && scopeCall.getScope().isEmpty()) {
            return Optional.empty();
        }
        // Defer to the packed chain renderer when the single-argument scope call carries a lambda argument: a lambda
        // body cannot be collapsed onto the single inlined continuation line this layout builds below, so that case
        // belongs to the chain renderer that keeps the lambda multiline. This intentionally keys off a deterministic AST
        // property (the argument kind) rather than whether the scope call happened to be multiline in the input. A simple
        // non-lambda single argument (a name, literal, or short call) does inline cleanly here, so it keeps this fitting
        // single-line continuation shape.
        if (
            scopeCall.getArguments().size() <= 1
            && scopeCall.getArguments().stream().anyMatch(LambdaExpr.class::isInstance)
        ) {
            return Optional.empty();
        }
        String opener = methodCallPrefix.apply(scopeCall) + "(";
        if (expressionFirstLineWidth.applyAsInt(parameters + " -> " + opener) > options.lineWidth()) {
            return Optional.empty();
        }
        if (scopeCall.getArguments().size() > 1) {
            return Optional.of(
                Doc.concat(
                    Doc.text(parameters + " -> " + opener),
                    Doc.indent(
                        Doc.concat(
                            Doc.HARD_LINE,
                            methodCallArgumentList.apply(
                                scopeCall.getArguments(),
                                Doc.HARD_LINE
                            )
                        )
                    ),
                    Doc.HARD_LINE,
                    Doc.text(")." + methodCallSelector.apply(methodCall) + "()")
                )
            );
        }
        return Optional.of(
            Doc.concat(
                Doc.text(parameters + " -> "),
                expressionRenderer.format(scopeCall.getScope().orElseThrow(), LayoutContext.root()),
                Doc.indent(
                    Doc.indent(
                        Doc.concat(
                            Doc.HARD_LINE,
                            Doc.text(
                                "." + methodCallSelector.apply(scopeCall) + "(" + compactJoin.apply(
                                    scopeCall.getArguments()
                                ) + ")"
                            ),
                            Doc.HARD_LINE,
                            Doc.text("." + methodCallSelector.apply(methodCall) + "()")
                        )
                    )
                )
            )
        );
    }
}
