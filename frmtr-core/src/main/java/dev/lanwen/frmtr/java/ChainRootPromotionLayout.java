package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import dev.lanwen.frmtr.java.MethodCallChainPrinter.MethodCallChainTail;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Renders the chain root — the receiver the selectors dot onto — in each promotion shape the planner selects: inline,
 * grouped, broken, expression-rendered, and their field-access / no-arg / single-segment sub-shapes. This helper owns the
 * root-doc switch and the width gates that decide whether a promoted root breaks its own arguments; it leaves segment
 * rendering, the continuation indent, chain analysis, and the imperative dispatch to the caller.
 *
 * <p>Kicks in when the chain root is promoted or grouped rather than plainly dotted onto, e.g. the static-factory root of
 * {@code IntStream.iterate(50, next -> next + 7).limit(22).toArray()}. See fixtures
 * {@code expression-lambda-factory-promotion}, {@code promoted-root-segment-continuation-width}, and
 * {@code qualified-static-chain-root}.
 */
final class ChainRootPromotionLayout {

    private final MethodCallPrinter calls;

    private final TypePrinter types;

    private final CompactSourceText compactSource;

    private final LayoutWidth layoutWidth;

    private final FormatterOptions options;

    private final SourceShapePolicy sourceShapePolicy;

    private final MethodCallChainSourcePlanner methodChainPlanner;

    private final CommentedExpressionListPrinter commentedExpressionLists;

    private final ChainSegmentRenderer segmentRenderer;

    private final JavaFormatRule<Expression> expressionRenderer;

    private final Function<ObjectCreationExpr, Doc> brokenObjectCreationRenderer;

    private final Function<Doc, Doc> chainContinuation;

    private final Function<Doc, Doc> softChainContinuation;

    private final Predicate<MethodCallExpr> methodCallSegmentHasBlockLambdaArgument;

    private final ChainFanLayout.RootLineWidth rootLineWidth;

    ChainRootPromotionLayout(
            MethodCallPrinter calls,
            TypePrinter types,
            CompactSourceText compactSource,
            LayoutWidth layoutWidth,
            FormatterOptions options,
            SourceShapePolicy sourceShapePolicy,
            MethodCallChainSourcePlanner methodChainPlanner,
            CommentedExpressionListPrinter commentedExpressionLists,
            ChainSegmentRenderer segmentRenderer,
            JavaFormatRule<Expression> expressionRenderer,
            Function<ObjectCreationExpr, Doc> brokenObjectCreationRenderer,
            Function<Doc, Doc> chainContinuation,
            Function<Doc, Doc> softChainContinuation,
            Predicate<MethodCallExpr> methodCallSegmentHasBlockLambdaArgument,
            ChainFanLayout.RootLineWidth rootLineWidth
    ) {
        this.calls = calls;
        this.types = types;
        this.compactSource = compactSource;
        this.layoutWidth = layoutWidth;
        this.options = options;
        this.sourceShapePolicy = sourceShapePolicy;
        this.methodChainPlanner = methodChainPlanner;
        this.commentedExpressionLists = commentedExpressionLists;
        this.segmentRenderer = segmentRenderer;
        this.expressionRenderer = expressionRenderer;
        this.brokenObjectCreationRenderer = brokenObjectCreationRenderer;
        this.chainContinuation = chainContinuation;
        this.softChainContinuation = softChainContinuation;
        this.methodCallSegmentHasBlockLambdaArgument = methodCallSegmentHasBlockLambdaArgument;
        this.rootLineWidth = rootLineWidth;
    }

    Doc methodCallChainRootDoc(
            MethodCallChainSourcePlanner.MethodCallChainPlan chainPlan,
            ToIntFunction<String> firstLineWidth,
            LayoutContext layout,
            boolean chainIsCommentFree
    ) {
        return switch (chainPlan.rootRendering()) {
            case INLINE_PROMOTED_METHOD_CALL -> chainPlan.root() instanceof MethodCallExpr methodCall
                ? promotedMethodCallRoot(methodCall, firstLineWidth, layout)
                : expressionRenderer.format(chainPlan.root(), LayoutContext.root());
            case GROUPED_PROMOTED_METHOD_CALL -> chainPlan.root() instanceof MethodCallExpr methodCall
                ? groupedPromotedMethodCall(methodCall)
                : expressionRenderer.format(chainPlan.root(), LayoutContext.root());
            case BROKEN_OBJECT_CREATION -> brokenObjectCreationRenderer.apply((ObjectCreationExpr) chainPlan.root());
            case EXPRESSION_RENDERER -> expressionRenderedChainRoot(chainPlan.root(), firstLineWidth, chainIsCommentFree);
        };
    }

    /**
     * The EXPRESSION_RENDERER chain root: a multi-argument {@link MethodCallExpr} root ranks the flat and
     * force-broken shapes at the true rendered column via {@link Doc#bestFitting}, so the renderer decides instead
     * of a source-width estimate. {@code chainIsCommentFree} — whether the WHOLE chain (not just this root) carries
     * no comment — must gate the ranking: building both candidates renders (and claims) the root twice, which would
     * double-claim any comment anywhere in the chain, including one attached between this root and its first
     * selector that the caller has not resolved yet when this is called.
     */
    private Doc expressionRenderedChainRoot(
            Expression root,
            ToIntFunction<String> firstLineWidth,
            boolean chainIsCommentFree
    ) {
        if (!(root instanceof MethodCallExpr methodCall) || methodCall.getArguments().size() <= 1) {
            return expressionRenderer.format(root, LayoutContext.root());
        }
        if (!chainIsCommentFree) {
            return firstLineWidth.applyAsInt(compactSourceWidthText(methodCall)) > options.lineWidth()
                ? calls.brokenMethodCall(methodCall)
                : expressionRenderer.format(root, LayoutContext.root());
        }
        return Doc.bestFitting(List.of(expressionRenderer.format(root, LayoutContext.root()), calls.brokenMethodCall(methodCall)));
    }

    /**
     * The single-segment chain root, source-multiline arguments kept verbatim when present; otherwise the plain
     * inline expression form (a method-call root reaching here is always scope-less, so no broken alternative applies).
     */
    Doc singleSegmentMethodRootDoc(MethodCallExpr methodRoot) {
        Optional<Doc> sourceMultilineArguments =
            calls.sourceMultilineArguments(methodRoot);
        if (sourceMultilineArguments.isPresent()) {
            return sourceMultilineArguments.orElseThrow();
        }
        return expressionRenderer.format(methodRoot, LayoutContext.root());
    }

    private String compactSourceWidthText(Expression expression) {
        // Source-neutral compact form, not normalizeWhitespace(rawWithoutOwnComment): the latter turns each source
        // newline into a space, so an expression the author already wrapped measures wider than its flat form and the
        // width gate that consumes it flips its verdict between passes.
        return compactSource.compactWithoutOwnComment(expression);
    }

    Doc groupedPromotedMethodCall(MethodCallExpr expression) {
        Optional<Doc> sourceMultilineArguments =
            calls.sourceMultilineArguments(expression);
        if (sourceMultilineArguments.isPresent()) {
            return sourceMultilineArguments.orElseThrow();
        }
        Optional<Doc> huggableExpressionLambda =
            groupedPromotedExpressionLambda(expression);
        if (huggableExpressionLambda.isPresent()) {
            return huggableExpressionLambda.orElseThrow();
        }
        if (methodCallSegmentHasBlockLambdaArgument.test(expression)) {
            // Rank the block-lambda hug against dropping the selector to its own continuation line at the true rendered
            // column: both arms carry the same hard-break body, so first-line fit — not fewest lines — must decide.
            Doc hug = expressionRenderer.format(expression, LayoutContext.root());
            Doc broken = Doc.concat(
                    expressionRenderer.format(expression.getScope().orElseThrow(), LayoutContext.root()),
                    chainContinuation.apply(segmentRenderer.methodCallChainSegment(expression))
            );
            return Doc.bestFittingFirstLine(List.of(hug, broken));
        }
        return groupedPromotedSelector(expression);
    }

    /**
     * The grouped promoted-root shape: the scope with its selector on a soft continuation, wrapped in a group so the
     * renderer keeps it flat when it fits and drops the selector to its own line otherwise. Scope-less calls fall back to
     * plain expression dispatch.
     */
    private Doc groupedPromotedSelector(MethodCallExpr expression) {
        return expression.getScope()
                .map(scope -> Doc.group(
                        Doc.concat(
                            expressionRenderer.format(scope, LayoutContext.root()),
                            softChainContinuation.apply(segmentRenderer.methodCallChainSegment(expression))
                        )
                ))
                .orElseGet(() -> expressionRenderer.format(expression, LayoutContext.root()));
    }

    /**
     * A no-op stub: a promoted segment's trailing expression lambda is hugged or exploded by width upstream, so this
     * always returns empty. Retained so the candidate-ladder dispatch in {@link #groupedPromotedMethodCall} stays wired.
     */
    private Optional<Doc> groupedPromotedExpressionLambda(MethodCallExpr expression) {
        return Optional.empty();
    }

    Doc groupedPromotedRootWithSingleSegment(
            Doc rootDoc,
            MethodCallExpr expression,
            MethodCallChainTail finalSegmentSuffix
    ) {
        if (methodCallSegmentHasBlockLambdaArgument.test(expression)) {
            // Rank the block-lambda hug against dropping the selector to its own continuation line at the true rendered
            // column: both arms carry the same hard-break body, so first-line fit — not fewest lines — must decide.
            Doc segment = segmentRenderer.methodCallChainSegment(expression, finalSegmentSuffix);
            Doc hug = Doc.concat(rootDoc, segment);
            Doc broken = Doc.concat(rootDoc, chainContinuation.apply(segment));
            return Doc.bestFittingFirstLine(List.of(hug, broken));
        }
        return Doc.group(
            Doc.concat(
                rootDoc,
                // Measure the segment on its own continuation line, where softChainContinuation drops it when it breaks.
                softChainContinuation.apply(
                    segmentRenderer.methodCallChainSegment(
                        expression,
                        Optional.empty(),
                        finalSegmentSuffix,
                        layoutWidth::continuationStatement,
                        true
                    )
                )
            )
        );
    }

    Doc inlineMethodCall(MethodCallExpr expression) {
        Doc scope = expression.getScope()
                .map(node -> expressionRenderer.format(node, LayoutContext.root()))
                .orElse(Doc.EMPTY);
        String typeArguments = expression.getTypeArguments()
                .map(arguments -> "<" + types.compactJoinTypeLike(arguments) + ">")
                .orElse("");
        String arguments = "(" + compactSource.compactJoin(expression.getArguments()) + ")";
        return Doc.concat(scope, Doc.text("." + typeArguments + expression.getNameAsString() + arguments));
    }

    private Doc promotedMethodCallRoot(
            MethodCallExpr expression,
            ToIntFunction<String> firstLineWidth,
            LayoutContext layout
    ) {
        Optional<Doc> sourceMultilineArguments =
            calls.sourceMultilineArguments(expression);
        if (sourceMultilineArguments.isPresent()) {
            return sourceMultilineArguments.orElseThrow();
        }
        if (promotedRootArgumentsShouldBreak(expression, firstLineWidth, layout)) {
            return brokenPromotedMethodCallRoot(expression);
        }
        if (promotedNoArgRootScopeOverflows(expression, firstLineWidth)) {
            return expression.getScope()
                    .filter(FieldAccessExpr.class::isInstance)
                    .map(FieldAccessExpr.class::cast)
                    .map(scope -> promotedFieldAccessRootMethodCall(scope, expression))
                    .or(() -> expression.getScope().map(
                            scope -> Doc.concat(
                                expressionRenderer.format(scope, LayoutContext.root()),
                                chainContinuation.apply(segmentRenderer.methodCallChainSegment(expression))
                            )
                    ))
                    .orElseGet(() -> inlineMethodCall(expression));
        }
        return inlineMethodCall(expression);
    }

    private Doc promotedFieldAccessRootMethodCall(FieldAccessExpr scope, MethodCallExpr expression) {
        String typeArguments = expression.getTypeArguments()
                .map(arguments -> "<" + types.compactJoinTypeLike(arguments) + ">")
                .orElse("");
        return Doc.concat(
            Doc.text(compactSource.compact(scope.getScope())),
            chainContinuation.apply(
                Doc.text("." + scope.getNameAsString() + "." + typeArguments + expression.getNameAsString() + "()")
            )
        );
    }

    boolean promotedNoArgRootScopeOverflows(
            MethodCallExpr expression,
            ToIntFunction<String> firstLineWidth
    ) {
        return expression.getArguments().isEmpty()
            && expression.getScope().filter(FieldAccessExpr.class::isInstance).isPresent()
            && !sourceShapePolicy.fitsOnOneLine(expression, firstLineWidth);
    }

    boolean promotedRootArgumentsShouldBreak(
            MethodCallExpr expression,
            ToIntFunction<String> firstLineWidth,
            LayoutContext layout
    ) {
        if (expression.getArguments().size() <= 1) {
            return false;
        }
        String compact = compactSource.compact(expression);
        // The fixed one-indent term was OR-dominated — rootLineWidth is never smaller than
        // nodeIndentWidth(root) + compact.length(), which already dominates the one-indent baseline — so the
        // rendered-column comparison alone yields the identical verdict.
        return rootLineWidth.measure(expression, compact, layout) > options.lineWidth();
    }

    /**
     * True-column width oracle for a fanned chain selector's expression-lambda hug: the selector's rendered continuation
     * column ({@code nodeIndentWidth + indentUnit * 2}) widened with {@code Math.max} against the caller's fixed budget,
     * so it is monotone — only ever measures the hug wider, never relaxes a break, and stays a pure function of the AST.
     * Still under-counts a selector nested several argument levels deep (needs {@code leftEdgePrefix} threaded), but is
     * never worse than the budget it replaces.
     */
    ToIntFunction<String> fannedSelectorColumnWidth(MethodCallExpr expression, ToIntFunction<String> fallback) {
        int continuationColumn = layoutWidth.nodeIndentWidth(expression) + options.indentUnit().length() * 2;
        return text -> Math.max(fallback.applyAsInt(text), continuationColumn + text.length());
    }

    private Doc brokenPromotedMethodCallRoot(MethodCallExpr expression) {
        String prefix = calls.methodCallPrefix(expression);
        // Route through the comment-aware argument-list renderer so unclaimed gap comments on the root's arguments
        // survive; parenthesized() returns empty when there are none, so the comment-free path below stays byte-identical.
        Optional<Doc> commentedArguments = commentedExpressionLists.parenthesized(prefix, expression, expression.getArguments());
        if (commentedArguments.isPresent()) {
            return commentedArguments.orElseThrow();
        }
        return Doc.concat(
            Doc.text(prefix + "("),
            Doc.indent(
                Doc.concat(
                    Doc.HARD_LINE,
                    calls.methodCallArgumentList(prefix, expression.getArguments(), Doc.HARD_LINE)
                )
            ),
            Doc.HARD_LINE,
            Doc.text(")")
        );
    }
}
