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
import java.util.function.BiFunction;
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

    private final BiFunction<String, MethodCallExpr, Optional<String>> blockLambdaSegmentFirstLine;

    private final ChainFanLayout.RootLineWidth rootLineWidth;

    private final Function<MethodCallExpr, Optional<Doc>> forcedRootMethodCallChain;

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
            BiFunction<String, MethodCallExpr, Optional<String>> blockLambdaSegmentFirstLine,
            ChainFanLayout.RootLineWidth rootLineWidth,
            Function<MethodCallExpr, Optional<Doc>> forcedRootMethodCallChain
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
        this.blockLambdaSegmentFirstLine = blockLambdaSegmentFirstLine;
        this.rootLineWidth = rootLineWidth;
        this.forcedRootMethodCallChain = forcedRootMethodCallChain;
    }

    Doc methodCallChainRootDoc(
            MethodCallChainSourcePlanner.MethodCallChainPlan chainPlan,
            ToIntFunction<String> firstLineWidth,
            LayoutContext layout
    ) {
        return switch (chainPlan.rootRendering()) {
            case INLINE_PROMOTED_METHOD_CALL -> chainPlan.root() instanceof MethodCallExpr methodCall
                ? promotedMethodCallRoot(methodCall, firstLineWidth, layout)
                : expressionRenderer.format(chainPlan.root(), LayoutContext.root());
            case GROUPED_PROMOTED_METHOD_CALL -> chainPlan.root() instanceof MethodCallExpr methodCall
                ? groupedPromotedMethodCall(methodCall)
                : expressionRenderer.format(chainPlan.root(), LayoutContext.root());
            case BROKEN_OBJECT_CREATION -> brokenObjectCreationRenderer.apply((ObjectCreationExpr) chainPlan.root());
            case EXPRESSION_RENDERER -> expressionRenderedChainRoot(chainPlan.root(), firstLineWidth);
        };
    }

    private Doc expressionRenderedChainRoot(
            Expression root,
            ToIntFunction<String> firstLineWidth
    ) {
        if (expressionRenderedChainRootBreaksMethodCall(root, firstLineWidth)) {
            return calls.brokenMethodCall((MethodCallExpr) root);
        }
        return expressionRenderer.format(root, LayoutContext.root());
    }

    /**
     * Whether {@link #expressionRenderedChainRoot} breaks a multi-argument root through
     * {@link MethodCallPrinter#brokenMethodCall} rather than plain expression dispatch. The {@code false} case matches the
     * root {@link ChainFanLayout#chainFanOut} builds, so the multi-segment fall-through routes through the shared fan-out
     * builder byte-identically only then. Side-effect-free, so steering the fall-through with it never double-claims a comment.
     */
    boolean expressionRenderedChainRootBreaksMethodCall(
            Expression root,
            ToIntFunction<String> firstLineWidth
    ) {
        return root instanceof MethodCallExpr methodCall
            && methodCall.getArguments().size() > 1
            && firstLineWidth.applyAsInt(compactSourceWidthText(methodCall)) > options.lineWidth();
    }

    /**
     * Ranks the single-segment method root against a broken alternative at the true rendered column when one is offered,
     * so the renderer — not a source-estimate gate — decides the break; with no broken alternative the inline root stands.
     */
    Doc singleSegmentMethodRootDoc(MethodCallExpr methodRoot) {
        Optional<Doc> sourceMultilineArguments =
            calls.sourceMultilineArguments(methodRoot);
        if (sourceMultilineArguments.isPresent()) {
            return sourceMultilineArguments.orElseThrow();
        }
        Doc inline = expressionRenderer.format(methodRoot, LayoutContext.root());
        Optional<Doc> broken = brokenTypeLikeScopedMethodRoot(methodRoot)
                .or(() -> forcedRootMethodCallChain.apply(methodRoot));
        if (broken.isEmpty()) {
            return inline;
        }
        return Doc.bestFitting(List.of(inline, broken.orElseThrow()));
    }

    /**
     * The type-like scoped-root break shape ({@code Type.factory(a, b)} broken, selector continued) offered as the broken
     * ranking candidate when the root's scope is a promoting multi-argument call. Width is left to the renderer, which
     * ranks this against the inline root at the true column.
     */
    private Optional<Doc> brokenTypeLikeScopedMethodRoot(MethodCallExpr methodRoot) {
        Optional<MethodCallExpr> scopedCall = methodRoot.getScope()
                .filter(MethodCallExpr.class::isInstance)
                .map(MethodCallExpr.class::cast)
                .filter(call -> call.getArguments().size() > 1)
                .filter(call -> call.getScope().filter(methodChainPlanner::promotesFirstCall).isPresent());
        if (scopedCall.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
            Doc.concat(
                calls.brokenMethodCall(scopedCall.orElseThrow()),
                chainContinuation.apply(segmentRenderer.methodCallChainSegment(methodRoot))
            )
        );
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
        boolean multiArg = expression.getArguments().size() > 1;
        boolean blockLambda = methodCallSegmentHasBlockLambdaArgument.test(expression);
        if (multiArg && !blockLambda) {
            // Rank the grouped selector shape against the fully-broken argument list at the true rendered column; the
            // grouped form wins by fewer lines whenever it fits, so the renderer breaks arguments only when it must. A
            // block-lambda argument keeps the estimate pre-emption below — its hard-break body defeats fewest-lines ranking.
            Doc grouped = groupedPromotedSelector(expression);
            return Doc.bestFitting(List.of(grouped, calls.brokenMethodCall(expression)));
        }
        if (
            multiArg
            // Measure the promoted block-lambda call at its true rendered block/type depth (nodeLine) instead of CURRENT.
            && !sourceShapePolicy.fitsOnOneLine(expression, text -> layoutWidth.nodeLine(expression, text))
        ) {
            return calls.brokenMethodCall(expression);
        }
        Optional<Doc> huggableExpressionLambda =
            groupedPromotedExpressionLambda(expression);
        if (huggableExpressionLambda.isPresent()) {
            return huggableExpressionLambda.orElseThrow();
        }
        if (methodCallSegmentHasBlockLambdaArgument.test(expression)) {
            // Gate the hug on the block-lambda first line at its true rendered block/type depth (nodeLine); the
            // hard-break lambda body defeats fewest-lines ranking, so keep the opener width gate rather than bestFitting.
            return blockLambdaSegmentFirstLine.apply(compactSource.compact(expression.getScope().orElseThrow()), expression)
                    .filter(firstLine -> layoutWidth.nodeLine(expression, firstLine) <= options.lineWidth())
                    .map(ignored -> expressionRenderer.format(expression, LayoutContext.root()))
                    .orElseGet(() -> Doc.concat(
                            expressionRenderer.format(expression.getScope().orElseThrow(), LayoutContext.root()),
                            chainContinuation.apply(segmentRenderer.methodCallChainSegment(expression))
                    ));
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
            Expression root,
            Doc rootDoc,
            MethodCallExpr expression,
            MethodCallChainTail finalSegmentSuffix,
            LayoutContext layout
    ) {
        if (methodCallSegmentHasBlockLambdaArgument.test(expression)) {
            return blockLambdaSegmentFirstLine.apply(compactSource.compact(root), expression)
                    // Measure the promoted-root block-lambda first line at the root's true rendered block/type
                    // depth ({@link LayoutWidth#nodeLine}) instead of the fixed BLOCK baseline.
                    .filter(firstLine -> layoutWidth.nodeLine(root, firstLine) <= options.lineWidth())
                    .map(ignored -> Doc.concat(rootDoc, segmentRenderer.methodCallChainSegment(expression, finalSegmentSuffix)))
                    .orElseGet(() -> Doc.concat(
                            rootDoc,
                            chainContinuation.apply(segmentRenderer.methodCallChainSegment(expression, finalSegmentSuffix))
                    ));
        }
        return Doc.group(
            Doc.concat(
                rootDoc,
                // Measure the segment on its own continuation line (softChainContinuation drops it there when it breaks),
                // not the beside-a-token source column which reads the author's shape and flips the argument list across passes.
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
