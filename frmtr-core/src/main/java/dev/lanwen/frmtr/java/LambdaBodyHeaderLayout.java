package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Chooses lambda layouts that keep the arrow and the first body token on the header line.
 *
 * <p>Source-multiline method-call bodies can already have a useful first source line that fits after the lambda arrow.
 * This helper owns that source-shape decision so {@link LambdaExpressionPrinter} can keep the high-level lambda branch
 * order readable while method-call rendering and parameter formatting remain delegated to their canonical owners.
 */
final class LambdaBodyHeaderLayout {

    private final SourceShapePolicy sourceShapePolicy;

    private final RawSource rawSource;

    private final FormatterOptions options;

    private final JavaFormatRule<Expression> expressionRenderer;

    private final Predicate<LambdaExpr> lambdaParametersHaveComments;

    private final BiPredicate<LambdaExpr, String> lambdaParametersShouldBreak;

    private final BiFunction<LambdaExpr, String, Doc> lambdaParametersForHeader;

    private final ToIntFunction<String> currentIndentedWidth;

    LambdaBodyHeaderLayout(
            SourceShapePolicy sourceShapePolicy,
            RawSource rawSource,
            FormatterOptions options,
            JavaFormatRule<Expression> expressionRenderer,
            Predicate<LambdaExpr> lambdaParametersHaveComments,
            BiPredicate<LambdaExpr, String> lambdaParametersShouldBreak,
            BiFunction<LambdaExpr, String, Doc> lambdaParametersForHeader,
            ToIntFunction<String> currentIndentedWidth
    ) {
        this.sourceShapePolicy = sourceShapePolicy;
        this.rawSource = rawSource;
        this.options = options;
        this.expressionRenderer = expressionRenderer;
        this.lambdaParametersHaveComments = lambdaParametersHaveComments;
        this.lambdaParametersShouldBreak = lambdaParametersShouldBreak;
        this.lambdaParametersForHeader = lambdaParametersForHeader;
        this.currentIndentedWidth = currentIndentedWidth;
    }

    Optional<Doc> sourceMultilineMethodCallBodyWithHeader(
            LambdaExpr lambda,
            String parameters,
            Expression body
    ) {
        if (
            lambdaParametersHaveComments.test(lambda)
            || lambdaParametersShouldBreak.test(lambda, parameters)
            || !(body instanceof MethodCallExpr)
            || !sourceShapePolicy.wasMultiline(body)
        ) {
            return Optional.empty();
        }
        String firstBodyLine = rawSource.rawWithoutOwnComment(body)
                .strip()
                .lines()
                .findFirst()
                .orElse("");
        if (
            firstBodyLine.isEmpty()
            || currentIndentedWidth.applyAsInt(parameters + " -> " + firstBodyLine) > options.lineWidth()
        ) {
            return Optional.empty();
        }
        return Optional.of(
            Doc.concat(
                lambdaParametersForHeader.apply(lambda, parameters),
                Doc.text(" -> "),
                expressionRenderer.format(body)
            )
        );
    }
}
