package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.comments.BlockComment;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.comments.JavadocComment;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import dev.lanwen.frmtr.doc.DocRenderer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Renders method-call chains after {@link MethodCallPrinter} has identified a call-shaped expression.
 *
 * <p>This helper owns chain analysis, source-shaped chain preservation, root promotion, final segment suffixes, and
 * chain comments. The boundary keeps ordinary method-call argument dispatch in {@link MethodCallPrinter} while making
 * dotted-chain layout a separate state machine instead of another branch inside the call dispatcher.
 */
final class MethodCallChainPrinter {

    private final CommentTracker comments;

    private final JavaCommentPlacementPolicy commentPlacement;

    private final RawSource rawSource;

    private final SourceShapePolicy sourceShapePolicy;

    private final MethodCallChainSourcePlanner methodChainPlanner;

    private final FormatterOptions options;

    private final CompactSourceText compactSource;

    private final LayoutWidth layoutWidth;

    private final MethodCallPrinter calls;

    private final TypePrinter types;

    private final CommentedExpressionListPrinter commentedExpressionLists;

    private final JavaFormatRule<Expression> expressionRenderer;

    private final Function<ObjectCreationExpr, Doc> brokenObjectCreationRenderer;

    private final Function<ObjectCreationExpr, Doc> widthDrivenObjectCreationRenderer;

    private final Function<ObjectCreationExpr, String> objectCreationPrefix;

    private final BiFunction<String, NodeList<Expression>, Optional<Doc>> huggableBlockLambdaArguments;

    private final BiFunction<String, NodeList<Expression>, Optional<String>> huggableBlockLambdaFirstLine;

    private final BiFunction<String, MethodCallExpr, Optional<Doc>> commentedExpressionLambdaArgument;

    private final ExpressionLambdaArgumentLayout.HuggableExpressionLambdaArguments huggableExpressionLambdaArguments;

    private final ExpressionLambdaArgumentLayout.PlanFactory expressionLambdaArgumentPlan;

    private final LayoutDecisionLog layoutDecisions;

    private final SourceMultilineLambdaCallLayout sourceMultilineLambdaCalls;

    private final ChainWidthBreakExplain chainWidthBreakExplain;

    private final MixedFieldMethodCallChainLayout mixedFieldChains;

    private final PackedMethodCallChainLayout packedChains;

    private final Function<LambdaExpr, String> lambdaParameters;

    private final Function<LambdaExpr, Optional<Doc>> huggedGapCommentedLambdaBody;

    private final ExpressionLambdaArgumentLayout.ExpressionLambdaMethodCallBodyOpener expressionLambdaMethodCallBodyOpener;

    private final ExpressionLambdaArgumentLayout.ExpressionLambdaLogicalBinaryBodyOpenerHug expressionLambdaLogicalBinaryBodyOpenerHug;

    private final ChainSelectorLambdaLayout chainSelectorLambda;

    private final ChainFanLayout chainFan;

    MethodCallChainPrinter(
            JavaFormatContext context,
            MethodCallPrinter calls,
            TypePrinter types,
            CommentedExpressionListPrinter commentedExpressionLists,
            JavaFormatRule<Expression> expressionRenderer,
            Function<ObjectCreationExpr, Doc> brokenObjectCreationRenderer,
            Function<ObjectCreationExpr, Doc> widthDrivenObjectCreationRenderer,
            Function<ObjectCreationExpr, String> objectCreationPrefix,
            BiFunction<String, NodeList<Expression>, Optional<Doc>> huggableBlockLambdaArguments,
            BiFunction<String, NodeList<Expression>, Optional<String>> huggableBlockLambdaFirstLine,
            BiFunction<String, MethodCallExpr, Optional<Doc>> commentedExpressionLambdaArgument,
            ExpressionLambdaArgumentLayout.HuggableExpressionLambdaArguments huggableExpressionLambdaArguments,
            ExpressionLambdaArgumentLayout.PlanFactory expressionLambdaArgumentPlan,
            Function<LambdaExpr, Optional<Doc>> huggedGapCommentedLambdaBody,
            Function<LambdaExpr, String> lambdaParameters,
            ExpressionLambdaArgumentLayout.ExpressionLambdaMethodCallBodyOpener expressionLambdaMethodCallBodyOpener,
            ExpressionLambdaArgumentLayout.ExpressionLambdaLogicalBinaryBodyOpenerHug expressionLambdaLogicalBinaryBodyOpenerHug
    ) {
        this.comments = context.comments;
        this.commentPlacement = context.commentPlacementPolicy;
        this.rawSource = context.rawSource;
        this.sourceShapePolicy = context.sourceShapePolicy;
        this.options = context.options;
        this.compactSource = context.compactSource;
        this.layoutWidth = context.layoutWidth;
        this.methodChainPlanner = new MethodCallChainSourcePlanner(context, lineWidth(LayoutWidth.LineBudget.CURRENT));
        this.calls = calls;
        this.types = types;
        this.commentedExpressionLists = commentedExpressionLists;
        this.expressionRenderer = expressionRenderer;
        this.brokenObjectCreationRenderer = brokenObjectCreationRenderer;
        this.widthDrivenObjectCreationRenderer = widthDrivenObjectCreationRenderer;
        this.objectCreationPrefix = objectCreationPrefix;
        this.huggableBlockLambdaArguments = huggableBlockLambdaArguments;
        this.huggableBlockLambdaFirstLine = huggableBlockLambdaFirstLine;
        this.commentedExpressionLambdaArgument = commentedExpressionLambdaArgument;
        this.huggableExpressionLambdaArguments = huggableExpressionLambdaArguments;
        this.expressionLambdaArgumentPlan = expressionLambdaArgumentPlan;
        this.layoutDecisions = context.layoutDecisions;
        this.lambdaParameters = lambdaParameters;
        this.huggedGapCommentedLambdaBody = huggedGapCommentedLambdaBody;
        this.expressionLambdaMethodCallBodyOpener = expressionLambdaMethodCallBodyOpener;
        this.expressionLambdaLogicalBinaryBodyOpenerHug = expressionLambdaLogicalBinaryBodyOpenerHug;
        this.sourceMultilineLambdaCalls = new SourceMultilineLambdaCallLayout(
            context.sourceShapePolicy,
            node -> expressionRenderer.format(node, LayoutContext.root()),
            lambdaParameters,
            calls::methodCallPrefix,
            this::methodCallSegmentPrefixText,
            calls::methodCallArgumentList
        );
        this.chainWidthBreakExplain = new ChainWidthBreakExplain(
            context.compactSource,
            context.layoutWidth,
            context.options,
            context.layoutDecisions
        );
        this.mixedFieldChains = new MixedFieldMethodCallChainLayout(
            expressionRenderer,
            this::chainContinuation,
            this::methodCallChainSegment,
            this::fieldAccessMethodCallSegment
        );
        this.packedChains = new PackedMethodCallChainLayout(
            context.options,
            context.layoutWidth,
            context.sourceShapePolicy,
            this::chainContinuation,
            objectCreationPrefix,
            brokenObjectCreationRenderer,
            this::methodCallChainAnalysis,
            methodChainPlanner::rootIsObjectCreation,
            this::lineWidth,
            this::compactMethodCallChainRoot,
            this::compactMethodCallChainSegmentCanStayFlat,
            (objectCreation, rootDoc, call, rootRendering, sourceMultilineChain, lineBudget, firstLineWidth, layout) ->
                objectRootSingleSegmentChain(
                    objectCreation,
                    rootDoc,
                    call,
                    MethodCallChainTail.EMPTY,
                    rootRendering,
                    sourceMultilineChain,
                    lineBudget,
                    firstLineWidth,
                    layout
                ),
            this::forcedMethodCallChain
        );
        this.chainSelectorLambda = new ChainSelectorLambdaLayout(
            context.comments,
            context.compactSource,
            context.layoutWidth,
            context.options,
            expressionRenderer,
            lambdaParameters,
            huggedGapCommentedLambdaBody,
            huggableExpressionLambdaArguments,
            expressionLambdaMethodCallBodyOpener,
            expressionLambdaLogicalBinaryBodyOpenerHug,
            this::methodCallSegmentPrefixText,
            this::methodCallChainRootIsObjectCreation,
            this::compactMethodCallChainSegmentCanStayFlat,
            this::appendFinalSegmentSuffix,
            this::fannedSelectorColumnWidth,
            this::brokenMethodCallSegment
        );
        this.chainFan = new ChainFanLayout(
            context.options,
            context.compactSource,
            expressionRenderer,
            chainWidthBreakExplain,
            widthDrivenObjectCreationRenderer,
            this::methodCallChainAnalysis,
            this::chainBreaksByRule,
            methodChainPlanner::promotesFirstCall,
            this::methodCallSegmentHasComment,
            this::methodCallSegmentHasBlockLambdaArgument,
            this::methodCallSegmentHasExpressionLambdaArgument,
            this::methodCallChainHasFinalTrailingLineComment,
            this::finalTrailingLineComments,
            this::trailingLineCommentsBeforeNextSegment,
            this::rootHasTrailingLineCommentBeforeFirstSegment,
            this::groupedPromotedMethodCall,
            calls::methodCallPrefix,
            calls::methodCallArgumentList,
            this::chainContinuation,
            this::chainContinuation,
            this::methodCallChainSegment,
            this::methodCallChainSegments,
            this::rootLineWidth,
            this::compactSingleLineRoot
        );
    }

    Optional<Doc> methodCallChain(MethodCallExpr expression) {
        return methodCallChain(expression, LayoutContext.root());
    }

    Optional<Doc> methodCallChain(MethodCallExpr expression, LayoutContext layout) {
        return methodCallChain(expression, MethodCallBreakMode.AUTO, layout);
    }

    /**
     * Canonical-fan cutover seam (End-state A): routes a fan-threshold, comment/lambda-free chain to the source-neutral
     * fan builder, independent of the author's source shape. Delegates to {@link ChainFanLayout}, which owns the
     * fan-position rules, the fan-shape rules, and the source-neutral root builders.
     */
    Optional<Doc> canonicalFanChain(MethodCallExpr expression, String finalSegmentSuffix, LayoutContext layout) {
        return chainFan.canonicalFanChain(expression, finalSegmentSuffix, layout);
    }

    /**
     * Reports whether a chain is one {@link #canonicalFanChain} would fan (the End-state A structural rule fires and no
     * carve-out applies). Delegates to {@link ChainFanLayout}.
     */
    boolean chainFansByCanonicalRule(MethodCallExpr expression) {
        return chainFan.chainFansByCanonicalRule(expression);
    }

    /**
     * Reports whether a chain fans by WIDTH rather than the author's line breaks — a trivial-receiver two-selector chain
     * or an enclosed/cast-rooted fanning chain. Delegates to {@link ChainFanLayout}.
     */
    private boolean chainIsWidthDrivenTwoSelectorFan(MethodCallChainSourcePlanner.MethodCallChainAnalysis analysis) {
        return chainFan.chainIsWidthDrivenTwoSelectorFan(analysis);
    }

    /**
     * Reports whether {@code argument} is a lambda whose body forces its own multi-line layout. Delegates to
     * {@link ChainFanLayout}.
     */
    private boolean lambdaArgumentForcesMultilineBody(Expression argument) {
        return chainFan.lambdaArgumentForcesMultilineBody(argument);
    }

    /**
     * Reports whether {@code expression} is a canonical fan carrying a trailing line comment — the flip case a with-tail
     * caller routes through the source-neutral fan on every pass. Delegates to {@link ChainFanLayout}.
     */
    boolean chainFansByCanonicalRuleWithTrailingLineComment(MethodCallExpr expression) {
        return chainFan.chainFansByCanonicalRuleWithTrailingLineComment(expression);
    }

    /**
     * Reports whether {@code expression} is a binary/ternary containing a flattened operand the canonical-fan rule fans.
     * Delegates to {@link ChainFanLayout}.
     */
    boolean binaryFansChainOperand(Expression expression) {
        return chainFan.binaryFansChainOperand(expression);
    }

    /**
     * The lambda-body position (U7) of the canonical-fan cutover: whether an expression-lambda-body chain should fan by
     * the End-state A rule and its root is one the lambda-body fan renders idempotently. Delegates to
     * {@link ChainFanLayout}.
     */
    boolean lambdaBodyChainFansByCanonicalRule(MethodCallExpr expression) {
        return chainFan.lambdaBodyChainFansByCanonicalRule(expression);
    }

    Optional<Doc> forcedMethodCallChain(MethodCallExpr expression) {
        return forcedMethodCallChain(expression, LayoutWidth.LineBudget.CURRENT);
    }

    Optional<Doc> forcedMethodCallChain(MethodCallExpr expression, LayoutWidth.LineBudget lineBudget) {
        return forcedMethodCallChain(expression, lineBudget, LayoutContext.root());
    }

    // LDM-2f (#190): the layout-carrying entry seam. A caller that shares its first line with a fixed prefix (the return
    // chain threads {@code layout.withLeftEdgePrefix("return ")}) hands that context through here so the chain width gates
    // can attribute the prefix at the rendered column. The no-{@code layout} overload above passes {@code root()} (empty
    // prefix), so a forced-chain caller that threads no prefix measures with none.
    Optional<Doc> forcedMethodCallChain(
            MethodCallExpr expression,
            LayoutWidth.LineBudget lineBudget,
            LayoutContext layout
    ) {
        return forcedMethodCallChain(expression, lineBudget, lineWidth(lineBudget), layout);
    }

    Optional<Doc> forcedMethodCallChain(
            MethodCallExpr expression,
            ToIntFunction<String> firstLineWidth
    ) {
        return forcedMethodCallChain(expression, firstLineWidth, LayoutContext.root());
    }

    Optional<Doc> forcedMethodCallChain(
            MethodCallExpr expression,
            ToIntFunction<String> firstLineWidth,
            LayoutContext layout
    ) {
        return forcedMethodCallChain(expression, LayoutWidth.LineBudget.CURRENT, firstLineWidth, layout);
    }

    /**
     * Greedy-packs a chain onto its first line, spilling the overflow one segment per line. Delegates to
     * {@link PackedMethodCallChainLayout}, which owns the packed-chain shapes.
     */
    Optional<Doc> packedMethodCallChain(
            MethodCallExpr expression,
            ToIntFunction<String> firstLineWidth
    ) {
        return packedChains.packedMethodCallChain(expression, firstLineWidth);
    }

    private record SourceMultilineLambdaChainPlan(
        boolean rootCanAttachExpressionLambdaBody,
        List<Boolean> callCanAttachExpressionLambdaBody,
        Optional<SourceMultilineLambdaCallLayout.AttachedFirstSegment> firstCall
    ) {
        SourceMultilineLambdaChainPlan {
            callCanAttachExpressionLambdaBody = List.copyOf(callCanAttachExpressionLambdaBody);
        }

        boolean callCanAttachExpressionLambdaBody(int index) {
            return index >= 0
                && index < callCanAttachExpressionLambdaBody.size()
                && callCanAttachExpressionLambdaBody.get(index);
        }

        boolean anyNonFinalCallCanAttachExpressionLambdaBody() {
            for (int index = 0; index < Math.max(0, callCanAttachExpressionLambdaBody.size() - 1); index++) {
                if (callCanAttachExpressionLambdaBody.get(index)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Reports whether the root or any selector in this chain has a source-multiline expression-lambda body that could
         * hug its call opener. The canonical-fan cutover ({@link #canonicalFanChain}) withholds any such chain so the
         * lambda-hug↔break shape stays with the deferred lambda-arrow seam rather than being flattened into the fan.
         */
        boolean canAttachAnyExpressionLambdaBody() {
            return rootCanAttachExpressionLambdaBody || callCanAttachExpressionLambdaBody.stream().anyMatch(Boolean::booleanValue);
        }
    }

    private Optional<Doc> forcedMethodCallChain(
            MethodCallExpr expression,
            LayoutWidth.LineBudget lineBudget,
            ToIntFunction<String> firstLineWidth,
            LayoutContext layout
    ) {
        return methodCallChain(
            expression,
            MethodCallBreakMode.FORCED,
            MethodCallChainTail.EMPTY,
            lineBudget,
            firstLineWidth,
            layout
        );
    }

    Optional<Doc> compactRootWithBrokenFinalChainSegment(MethodCallExpr expression) {
        return compactRootWithBrokenFinalChainSegment(expression, LayoutWidth.LineBudget.CURRENT);
    }

    Optional<Doc> compactRootWithBrokenFinalChainSegment(
            MethodCallExpr expression,
            LayoutWidth.LineBudget lineBudget
    ) {
        return compactRootWithBrokenFinalChainSegment(expression, lineBudget, LayoutContext.root());
    }

    // LDM-2f (#190): the layout-carrying entry seam for the compact-root-with-broken-final-segment shape. The return chain
    // threads {@code layout.withLeftEdgePrefix("return ")} through here so {@code compactRootLineWidth} can attribute the
    // {@code return } prefix at the rendered column. The no-{@code layout} overload above passes {@code root()} (empty
    // prefix), so a caller that threads no prefix measures with none.
    Optional<Doc> compactRootWithBrokenFinalChainSegment(
            MethodCallExpr expression,
            LayoutWidth.LineBudget lineBudget,
            LayoutContext layout
    ) {
        List<MethodCallExpr> calls = new ArrayList<>();
        Expression root = methodChainPlanner.methodCallChainRoot(expression, calls);
        SourceMultilineLambdaChainPlan sourceMultilineLambdaPlan = sourceMultilineLambdaChainPlan(root, calls);
        Optional<Doc> sourceMultilineFirstExpressionLambda = comments.speculatively(
            () -> sourceMultilineFirstExpressionLambdaChain(
                expression,
                root,
                calls,
                MethodCallChainTail.EMPTY,
                sourceMultilineLambdaPlan
            )
        );
        if (sourceMultilineFirstExpressionLambda.isPresent()) {
            return sourceMultilineFirstExpressionLambda;
        }
        if (
            !calls.isEmpty()
            && methodCallChainIsSourceMultiline(expression)
            && methodCallSegmentHasExpressionLambdaArgument(calls.getLast())
        ) {
            return Optional.empty();
        }
        if (
            root instanceof MethodCallExpr methodRoot
            && methodCallSegmentHasExpressionLambdaArgument(methodRoot)
        ) {
            return Optional.empty();
        }
        if (
            calls.stream()
                    .limit(Math.max(0, calls.size() - 1))
                    .anyMatch(this::methodCallSegmentHasExpressionLambdaArgument)
        ) {
            return Optional.empty();
        }
        if (root instanceof MethodCallExpr methodRoot && calls.size() == 1) {
            return compactRootWithBrokenFinalSegment(methodRoot, calls.getFirst(), lineBudget, layout);
        }
        if (methodChainPlanner.promotesFirstCall(root) && calls.size() == 2) {
            return compactRootWithBrokenFinalSegment(calls.getFirst(), calls.get(1), lineBudget, layout);
        }
        return Optional.empty();
    }

    /**
     * Builds the chain fragment used when an expression-lambda body is packed after {@code ->}. Delegates to
     * {@link PackedMethodCallChainLayout}, which owns the packed-chain shapes.
     */
    Optional<Doc> packedExpressionLambdaBodyChain(String firstLine, MethodCallExpr expression) {
        return packedChains.packedExpressionLambdaBodyChain(firstLine, expression);
    }

    private Optional<String> compactMethodCallChainRoot(MethodCallExpr expression, List<String> segments) {
        if (!compactMethodCallChainSegmentCanStayFlat(expression)) {
            return Optional.empty();
        }
        Optional<Expression> scope = expression.getScope();
        if (scope.isEmpty()) {
            return Optional.empty();
        }
        Expression scoped = scope.orElseThrow();
        if (scoped instanceof MethodCallExpr methodCallScope) {
            Optional<String> root = compactMethodCallChainRoot(methodCallScope, segments);
            root.ifPresent(ignored -> segments.add(compactMethodCallChainSegment(expression)));
            return root;
        }
        if (!scoped.getAllContainedComments().isEmpty()) {
            return Optional.empty();
        }
        segments.add(compactMethodCallChainSegment(expression));
        return Optional.of(compactSource.compact(scoped));
    }

    Optional<String> compactMethodCallChainRoot(MethodCallExpr expression) {
        return compactMethodCallChainRoot(expression, new ArrayList<>());
    }

    private boolean compactMethodCallChainSegmentCanStayFlat(MethodCallExpr expression) {
        return expression.getArguments()
                .stream()
                .noneMatch(argument -> argument instanceof LambdaExpr
                        || !argument.getAllContainedComments().isEmpty()
                );
    }

    private String compactMethodCallChainSegment(MethodCallExpr expression) {
        return "."
            + expression.getTypeArguments()
                    .map(typeArguments -> "<" + compactSource.compactJoin(typeArguments) + ">")
                    .orElse("")
            + expression.getNameAsString()
            + "("
            + compactSource.compactJoin(expression.getArguments())
            + ")";
    }

    /**
     * A no-op stub: an already-multiline call statement is not kept broken; its argument list breaks by width, so this
     * always returns empty. Retained so the statement-printer hook stays wired.
     */
    Optional<Doc> sourceMultilineMethodCallStatement(
            MethodCallExpr expression,
            ExpressionStmt statement
    ) {
        return Optional.empty();
    }

    /**
     * Prints a dotted call chain when the call is naturally chain-shaped or when a caller forces the chain break.
     *
     * <p>Auto mode leaves short uncommented calls alone. Forced mode is used by return, assignment, statement, and field
     * contexts that already know the surrounding line overflowed and need a broken call shape.
     */
    Optional<Doc> methodCallChain(MethodCallExpr expression, boolean force) {
        return methodCallChain(expression, MethodCallBreakMode.fromForced(force), LayoutContext.root());
    }

    private Optional<Doc> methodCallChain(
            MethodCallExpr expression,
            MethodCallBreakMode breakMode,
            LayoutContext layout
    ) {
        return methodCallChain(expression, breakMode, "", LayoutWidth.LineBudget.CURRENT, layout);
    }

    Optional<Doc> methodCallChain(
            MethodCallExpr expression,
            MethodCallBreakMode breakMode,
            String finalSegmentSuffix,
            LayoutContext layout
    ) {
        return methodCallChain(
            expression,
            breakMode,
            finalSegmentSuffix,
            LayoutWidth.LineBudget.CURRENT,
            layout
        );
    }

    Optional<Doc> methodCallChain(
            MethodCallExpr expression,
            MethodCallBreakMode breakMode,
            String finalSegmentSuffix,
            LayoutWidth.LineBudget lineBudget,
            LayoutContext layout
    ) {
        return methodCallChain(expression, breakMode, MethodCallChainTail.of(finalSegmentSuffix), lineBudget, layout);
    }

    Optional<Doc> methodCallChain(
            MethodCallExpr expression,
            MethodCallBreakMode breakMode,
            String finalSegmentSuffix,
            LayoutWidth.LineBudget lineBudget,
            ToIntFunction<String> firstLineWidth,
            LayoutContext layout
    ) {
        return methodCallChain(
            expression,
            breakMode,
            MethodCallChainTail.of(finalSegmentSuffix),
            lineBudget,
            firstLineWidth,
            layout
        );
    }

    private Optional<Doc> methodCallChain(
            MethodCallExpr expression,
            MethodCallBreakMode breakMode,
            MethodCallChainTail finalSegmentSuffix,
            LayoutContext layout
    ) {
        return methodCallChain(expression, breakMode, finalSegmentSuffix, LayoutWidth.LineBudget.CURRENT, layout);
    }

    private Optional<Doc> methodCallChain(
            MethodCallExpr expression,
            MethodCallBreakMode breakMode,
            MethodCallChainTail finalSegmentSuffix,
            LayoutWidth.LineBudget lineBudget,
            LayoutContext layout
    ) {
        return methodCallChain(expression, breakMode, finalSegmentSuffix, lineBudget, lineWidth(lineBudget), layout);
    }

    private Optional<Doc> methodCallChain(
            MethodCallExpr expression,
            MethodCallBreakMode breakMode,
            MethodCallChainTail finalSegmentSuffix,
            LayoutWidth.LineBudget lineBudget,
            ToIntFunction<String> firstLineWidth,
            LayoutContext layout
    ) {
        MethodCallChainSourcePlanner.MethodCallChainAnalysis analysis = methodCallChainAnalysis(expression);
        boolean rootObjectCreationNeedsBreak = methodChainPlanner.rootObjectCreationNeedsBreak(analysis);
        SourceMultilineLambdaChainPlan sourceMultilineLambdaPlan = sourceMultilineLambdaChainPlan(analysis);
        boolean sourceMultilineArguments = chainHasSourceMultilineArguments(analysis, sourceMultilineLambdaPlan);
        if (
            !breakMode.isForced()
            && finalBlockLambdaSegmentCanStayCompact(expression, lineBudget)
        ) {
            return Optional.empty();
        }
        if (
            (!breakMode.isForced()
                && !analysis.hasComments()
                && !analysis.hasBlockLambdaArgument()
                && !analysis.sourceMultilineChain()
                // A chain carrying an inter-segment `//` line comment must not stay flat, so its fan-only
                // comment-preserving render is used. See {@link #chainHasInterSegmentLineComment}.
                && !analysis.hasInterSegmentLineComment()
                // A chain that reaches its link-count/root-kind threshold ({@code chainBreaksByRule}) MUST fan one
                // selector per line even when the flat form fits, so it does not stay flat here; the break is routed to
                // the source-neutral `chainFanOut` builder (the early canonical-fan route below), not the imperative
                // ladder downstream.
                && !chainBreaksByRule(analysis)
                && !sourceMultilineArguments
                && !rootObjectCreationNeedsBreak
                // The stay-flat probe must measure the chain at the same line position it will actually occupy. When the
                // chain shares its line with a prefix (an assignment target plus operator, an initializer name, etc.) the
                // caller threads that prefix through {@code firstLineWidth}; measuring with a prefix-blind width here would
                // keep a chain flat whose real line overflows. {@code firstLineWidth} defaults to {@code lineWidth(lineBudget)},
                // so a prefix-less caller measures with a plain {@code lineWidth(lineBudget)} probe.
                //
                // The same channel now also carries NESTING DEPTH: a chain rendered as a wrapped call argument or a
                // nested initializer (e.g. {@code RetryPlan.create(...).toRetry()} as the argument of {@code .retryWhen(...)})
                // sits at its enclosing argument list's continuation indentation, deeper than the {@code CURRENT} budget
                // the AUTO entry assumes. The argument-list caller threads that deeper budget ({@code CONTINUATION}) as
                // {@code lineBudget}, so {@code firstLineWidth} here measures the chain at its real column and breaks a
                // chain whose flat line only fits the shallow budget but overflows where it actually renders.
                && firstLineWidth.applyAsInt(compactSource.compact(expression)) <= options.lineWidth())
            || expression.getScope().isEmpty()
        ) {
            return Optional.empty();
        }
        Expression root = analysis.root();
        List<MethodCallExpr> calls = analysis.calls();
        if (
            calls.isEmpty()
            || (calls.size() < 2
                && !(root instanceof MethodCallExpr)
                && !forcedSingleCallPrefixOverflows(breakMode, expression, lineBudget)
                && !(breakMode.isForced() && root instanceof ObjectCreationExpr)
                && !rootObjectCreationNeedsBreak
                && !analysis.sourceMultilineChain()
                && !analysis.rootHasComments()
                && !analysis.singleCommentedSegment())
        ) {
            return Optional.empty();
        }
        // Route a fan-threshold chain straight to the source-neutral `chainFanOut` builder rather than the
        // source-shape-sensitive imperative ladder below. `chainFanOut` is a pure function of the AST (root + each
        // selector on its own dotted line, root rendered through ordinary expression dispatch), so both passes see the
        // identical AST and rebuild the identical fan — idempotent by construction. Gated comment-free / block-lambda-free:
        // `chainFanOut` re-renders the root once, and a comment-bearing root re-render would double-claim its comments (the
        // same guard the single-segment rankers use for their `chainFanOut` arm). Comment/lambda chains fall through to the
        // imperative ladder.
        if (
            chainBreaksByRule(analysis)
            && !analysis.hasComments()
            && !analysis.hasBlockLambdaArgument()
            && !sourceMultilineArguments
            && calls.stream().noneMatch(this::methodCallSegmentHasComment)
        ) {
            chainWidthBreakExplain.record(expression, analysis, layout);
            return Optional.of(chainFanOut(root, calls, finalSegmentSuffix, layout));
        }
        // The two chain families the canonical link-count/root-kind rule does NOT claim — a trivial-receiver TWO-selector
        // chain and an enclosed/cast-rooted fanning chain ({@link #chainIsWidthDrivenTwoSelectorFan}) — fan by WIDTH, not
        // by the author's line breaks. Route them through a WIDTH-driven {@link Doc#bestFitting}: the flat compact form
        // when it fits, the source-neutral {@code chainFanOut} on overflow. The fan arm is a pure function of the AST (root
        // + one selector per dotted line), so both passes rebuild the identical fan — a fixpoint. The enclosed/cast family
        // is ALWAYS-FAN, not bestFitting: its {@code fanRootDoc} renders the enclosed/cast root at {@code root()} (column
        // zero), so a flat arm measured at the real column would flip flat<->fan against the fan arm's column-zero render
        // and oscillate; an enclosed fanning root also already spans lines, so the flat arm can never win. Carries the
        // SAME comment/lambda carve-out as the early canonical route above — {@code chainFanOut} re-renders the root once
        // and must not double-claim a comment. Comment-bearing chains stay on the caller's comment-aware routing (kept
        // engaged for an inter-segment line comment by {@link #methodCallChainIsSourceMultiline}) and never reach this arm.
        if (
            chainIsWidthDrivenTwoSelectorFan(analysis)
            && !analysis.hasComments()
            && !analysis.hasBlockLambdaArgument()
            && !sourceMultilineArguments
            && calls.stream().noneMatch(this::methodCallSegmentHasComment)
        ) {
            chainWidthBreakExplain.record(expression, analysis, layout);
            Doc fanOut = chainFanOut(root, calls, finalSegmentSuffix, layout);
            if (rootIsEnclosedFanningChain(root)) {
                return Optional.of(fanOut);
            }
            Doc flat = appendFinalSegmentSuffix(Doc.text(compactSource.compact(expression)), finalSegmentSuffix);
            // The flat-vs-fan choice is a {@link Doc#conditionalGroup}, NOT {@link Doc#bestFitting}, for
            // every chain WHOSE FLAT FORM CAN ACTUALLY FIT. bestFitting ranks by rendered line count under a depth
            // bound and by a fewest-lines tie-break; a chain nested deeper than {@link DocWidths#MAX_BEST_FITTING_DEPTH}
            // best-fitting levels (a two-selector {@code forEach}/{@code map} chain whose lambda body is itself an
            // object-creation-rooted chain, e.g. {@code data.topics().forEach(t -> results.add(new X()...))}) collapses to
            // arm 0 (the flat compact) — and even at a shallow depth, when BOTH arms overflow, the fewest-lines tie-break
            // picks the single flat line, jamming the whole body onto one 300+ column line. A conditionalGroup chooses the
            // flat compact ONLY when it genuinely fits flat at the real column and otherwise renders the fan in break mode,
            // regardless of nesting depth. For the shallow single-level case where the flat arm fits, both combinators
            // agree, and the conditionalGroup is strictly better when the flat arm overflows.
            //
            // A chain whose final selector carries a lambda whose body CANNOT render flat (a block lambda, or an expression
            // lambda whose body itself nests a lambda — {@link #lambdaArgumentForcesMultilineBody}) is the exception: its
            // flat compact never "fits flat", so a conditionalGroup would fan the receiver on every pass, but the standalone
            // lambda-body renderer ({@code LambdaExpressionPrinter}) still decides that body's shape from a deferred
            // lambda-arrow source-shape read, which oscillates once the receiver is un-collapsed. (The sibling
            // method-call-body arrow read {@code lambdaBodyStartsAfterHeader} was retired by the #190 F2 slice; the
            // block/nested-lambda arrow reads here are not yet.) Such a chain keeps the {@link Doc#bestFitting} arm
            // (rendered collapsed), so it does not introduce a new oscillation.
            List<Doc> arms = List.of(flat, fanOut);
            boolean bodyForcesMultiline = calls.getLast().getArguments().stream()
                    .anyMatch(this::lambdaArgumentForcesMultilineBody);
            // A body-forces-multiline chain whose lambda body carries a contained comment cannot use the {@code flat}
            // compact arm at all: {@code compactSource.compact} routes a comment-bearing subtree through
            // {@code compactTokenText}, which only collapses whitespace RUNS, so every {@code //} line comment de-indents
            // to column one and merges the following token into itself (the nested {@code forEach(… -> // note … body)}
            // shape PR #279 flagged). {@link Doc#bestFitting} would still rank that malformed one-line-ish arm fewest-lines
            // and emit it. Fan unconditionally instead — {@link #chainFanOut} dispatches the lambda body through its own
            // printer, which keeps each comment on its own line at the body indent. The enclosing {@code !analysis.hasComments()}
            // guard already kept CHAIN-level comments out of this branch, so the only comments here live inside the lambda
            // body argument and are claimed exactly once by that body's renderer, never double-claimed by the fan root.
            if (bodyForcesMultiline && !expression.getAllContainedComments().isEmpty()) {
                return Optional.of(fanOut);
            }
            return Optional.of(bodyForcesMultiline ? Doc.bestFitting(arms) : Doc.conditionalGroup(arms));
        }
        // A chain that must fan ONLY to preserve an inter-segment {@code //} line comment ({@link #chainHasInterSegmentLineComment})
        // would otherwise collapse in the source-shape fall-through below and DROP the comment (the
        // {@code encode(x) // note}⏎{@code .replaceAll(...)} MirrorMaker shape, and the leading-{@code //}-before-a-selector
        // dot-gap shape). Route those chains to the comment-preserving one-segment-per-line fan here — the root rendered
        // with its trailing line comment, then {@link #methodCallChainSegments} re-emitting each selector's leading /
        // trailing line comment on its own continuation line. This claims the same comment candidate sets the imperative
        // render consumes (each rendered exactly once), so it is comment-safe and structural: the fan verdict keys on
        // comment presence, not on the author's line breaks, so both passes fan identically. Multi-selector-comment /
        // block-lambda / expression-lambda-selector chains are left to the existing comment-carrying imperative paths.
        Optional<Doc> flatHeadHuggedFinalLambda = comments.speculatively(
            () -> chainSelectorLambda.flatHeadHuggedCommentLambdaChain(expression, analysis, finalSegmentSuffix)
        );
        if (flatHeadHuggedFinalLambda.isPresent()) {
            return flatHeadHuggedFinalLambda;
        }
        if (canBreakAfterCompactExpressionLambdaRoot(breakMode, root, calls, sourceMultilineLambdaPlan, layout)) {
            return Optional.of(
                Doc.concat(
                    Doc.text(compactSource.compact(root)),
                    chainContinuation(methodCallChainSegment(calls.getFirst(), finalSegmentSuffix))
                )
            );
        }
        if (
            breakMode.isForced()
            && calls.size() == 1
            && root.getAllContainedComments().isEmpty()
            && calls.getFirst().getAllContainedComments().isEmpty()
            && !methodCallSegmentHasComment(calls.getFirst())
            && !analysis.rootHasBlockLambdaArgument()
        ) {
            Expression probeRoot = root;
            List<MethodCallExpr> probeCalls = calls;
            Optional<Doc> compactRootWithBrokenSegment = comments.speculatively(
                () -> compactRootWithBrokenFinalSegment(
                    probeRoot,
                    probeCalls.getFirst(),
                    finalSegmentSuffix,
                    lineBudget,
                    layout
                )
            );
            if (compactRootWithBrokenSegment.isPresent()) {
                return compactRootWithBrokenSegment;
            }
        }
        if (calls.size() == 1 && root instanceof MethodCallExpr methodRoot) {
            Doc rootDoc = singleSegmentMethodRootDoc(methodRoot);
            Doc rootTrailingComment = rootTrailingLineCommentBeforeFirstSegment(methodRoot, calls);
            if (rootTrailingComment != Doc.EMPTY) {
                rootDoc = Doc.concat(rootDoc, Doc.lineSuffix(Doc.concat(Doc.text(" "), rootTrailingComment)));
            }
            // A leading line comment on the only segment ({@code lookup(a)} then {@code // c1} on its own line then
            // {@code .orElseThrow(x)}) must own its own continuation line so the comment stays above the segment selector.
            // Attaching such a segment to the root close glued the comment onto the root's closing parenthesis
            // ({@code lookup(a)// c1}); a scope-rooted chain already avoids this because its segments go one-per-line, so
            // route the single-segment case the same way once the segment carries a leading comment.
            if (methodCallSegmentHasLeadingLineComment(calls.getFirst())) {
                return Optional.of(
                    Doc.concat(
                        rootDoc,
                        chainContinuation(methodCallChainSegment(calls.getFirst(), finalSegmentSuffix))
                    )
                );
            }
            // A block comment parked in the gap between a method-call root and its only selector
            // ({@code create() /* doc *}{@code / .seal()}) is missed by the stay-flat gate's contained-comment scan, so
            // the chain reaches this single-segment branch. Gluing the segment to the root close
            // ({@code methodCallChainSegmentAttachedToRootClose}) renders it flat and drops the source space before the
            // comment ({@code create()/* doc *}{@code / .seal()}). Route it through the breaking continuation instead, the
            // same escape the leading-line-comment case uses, so the selector's own segment prefix re-emits the comment
            // with its space on its own continuation line. Other single-segment method roots keep the attached-flat shape.
            if (methodCallSegmentHasLeadingGapBlockComment(methodRoot, calls.getFirst())) {
                return Optional.of(
                    Doc.concat(
                        rootDoc,
                        chainContinuation(methodCallChainSegment(calls.getFirst(), finalSegmentSuffix))
                    )
                );
            }
            return Optional.of(
                Doc.concat(
                    rootDoc,
                    methodCallChainSegmentAttachedToRootClose(calls.getFirst(), finalSegmentSuffix, lineBudget)
                )
            );
        }
        if (
            root instanceof FieldAccessExpr fieldAccess
            && fieldAccess.getScope() instanceof MethodCallExpr methodRoot
            && calls.size() == 1
        ) {
            return Optional.of(
                Doc.concat(
                    expressionRenderer.format(methodRoot, LayoutContext.root()),
                    chainContinuation(
                        appendFinalSegmentSuffix(
                            fieldAccessMethodCallSegment(fieldAccess, calls.getFirst()),
                            finalSegmentSuffix
                        )
                    )
                )
            );
        }
        MethodCallChainSourcePlanner.MethodCallChainPlan chainPlan = methodChainPlanner.plan(
            analysis,
            breakMode.isForced()
        );
        chainPlan = promoteFirstBlockLambdaCallWithLambdaBodyComments(analysis, chainPlan).orElse(chainPlan);
        root = chainPlan.root();
        calls = chainPlan.calls();
        sourceMultilineLambdaPlan = sourceMultilineLambdaChainPlan(root, calls);
        Doc rootDoc = methodCallChainRootDoc(chainPlan, firstLineWidth, layout);
        // Track whether {@code rootDoc} is still the plain {@code expressionRenderer.format(root, root())} doc — the exact
        // root {@link #chainFanOut} rebuilds — so the multi-segment fall-through below can route through the shared fan-out
        // builder byte-identically only in that case. It holds only for an EXPRESSION_RENDERER root that did not fall to the
        // broken-method-call shape; a promoted/grouped/broken-object-creation root, a first-segment-attached root, or a
        // root-trailing-comment-wrapped root produces a different {@code rootDoc} and stays on the inline construction.
        //
        // The comment-free gate is load-bearing: the fall-through routing through {@code chainFanOut} re-renders the root a
        // second time (the {@code rootDoc} built here is discarded in that path), and re-rendering a comment-bearing root
        // would re-claim its already-{@code printed} comments and trip the strict-claims guardrail — the same reason the
        // landed single-segment rankers gate their {@code chainFanOut} arm comment-free. A comment-free root re-renders to a
        // byte-identical {@code Doc}; a comment-bearing chain keeps the unchanged inline construction (rendered once).
        boolean rootDocIsPlainExpressionRenderRoot =
            chainPlan.rootRendering() == MethodCallChainSourcePlanner.ChainRootRendering.EXPRESSION_RENDERER
            && !analysis.hasComments()
            && !expressionRenderedChainRootBreaksMethodCall(chainPlan.root(), firstLineWidth);
        boolean firstSegmentAttachedToRoot = false;
        Expression sourceMultilineProbeRoot = root;
        List<MethodCallExpr> sourceMultilineProbeCalls = calls;
        SourceMultilineLambdaChainPlan sourceMultilineProbePlan = sourceMultilineLambdaPlan;
        Optional<Doc> sourceMultilineFirstExpressionLambda = comments.speculatively(
            () -> sourceMultilineFirstExpressionLambdaChain(
                expression,
                sourceMultilineProbeRoot,
                sourceMultilineProbeCalls,
                finalSegmentSuffix,
                sourceMultilineProbePlan
            )
        );
        if (sourceMultilineFirstExpressionLambda.isPresent()) {
            return sourceMultilineFirstExpressionLambda;
        }
        if (canAttachFirstSegmentToSimpleRoot(expression, chainPlan, calls, analysis)) {
            MethodCallExpr firstCall = calls.getFirst();
            root = firstCall;
            calls = new ArrayList<>(calls.subList(1, calls.size()));
            rootDoc = firstSegmentAttachedToSimpleRootDoc(
                chainPlan.root(),
                firstCall,
                sourceMultilineLambdaPlan.firstCall()
            );
            firstSegmentAttachedToRoot = true;
            // The first segment is now glued onto the root, so {@code rootDoc} is the attached-root shape, not the plain
            // expression-renderer root chainFanOut would build; keep this chain on the inline construction.
            rootDocIsPlainExpressionRenderRoot = false;
        }
        if (calls.isEmpty()) {
            return Optional.of(appendFinalSegmentSuffix(rootDoc, finalSegmentSuffix));
        }
        if (
            !analysis.sourceMultilineChain()
            && root instanceof MethodCallExpr methodRoot
            && calls.size() == 1
            && root.getAllContainedComments().isEmpty()
            && !methodCallSegmentHasComment(calls.getFirst())
            && methodCallSegmentHasBlockLambdaArgument(calls.getFirst())
            && blockLambdaSegmentFirstLine(compactSource.compact(methodRoot), calls.getFirst())
                    .filter(firstLine -> layoutWidth.line(LayoutWidth.LineBudget.BLOCK, firstLine) <= options.lineWidth())
                    .isPresent()
        ) {
            return Optional.empty();
        }
        Doc rootTrailingComment = rootTrailingLineCommentBeforeFirstSegment(root, calls);
        if (rootTrailingComment != Doc.EMPTY) {
            if (
                root instanceof ObjectCreationExpr objectCreation
                && chainPlan.rootRendering() == MethodCallChainSourcePlanner.ChainRootRendering.EXPRESSION_RENDERER
            ) {
                rootDoc = brokenObjectCreationRenderer.apply(objectCreation);
            }
            rootDoc = Doc.concat(rootDoc, Doc.lineSuffix(Doc.concat(Doc.text(" "), rootTrailingComment)));
            // The root now carries a trailing line comment suffix, so {@code rootDoc} is not the plain
            // expression-renderer root; a multi-segment chain reaching the fall-through below stays on the inline
            // construction so the comment suffix is preserved.
            rootDocIsPlainExpressionRenderRoot = false;
            if (calls.size() == 1) {
                return Optional.of(
                    Doc.concat(
                        rootDoc,
                        chainContinuation(methodCallChainSegment(calls.getFirst(), finalSegmentSuffix))
                    )
                );
            }
        }
        if (canKeepSuffixAttachedToPromotedBlockLambdaRoot(chainPlan, root, calls, finalSegmentSuffix)) {
            return Optional.of(Doc.concat(rootDoc, methodCallChainSegment(calls.getFirst(), finalSegmentSuffix)));
        }
        if (
            root instanceof ObjectCreationExpr objectCreation
            && calls.size() == 1
        ) {
            // LDM-3g (#210): rank the compact-with-broken-segment shape against the one-segment-per-line fan-out and let
            // the renderer keep whichever wraps least at the real output column, rather than committing to a shape via the
            // fixed-column firstLineWidth probe inside objectRootSingleSegmentChain below. Only width-driven, comment-free,
            // source-compact-constructor chains reach the ranker; it defers back to the imperative tail otherwise.
            Optional<Doc> rankedObjectRootSegment = rankedObjectRootSingleSegmentChain(
                objectCreation,
                calls.getFirst(),
                finalSegmentSuffix,
                rootDoc,
                chainPlan.rootRendering(),
                analysis,
                lineBudget,
                layout
            );
            if (rankedObjectRootSegment.isPresent()) {
                return rankedObjectRootSegment;
            }
            return Optional.of(objectRootSingleSegmentChain(
                objectCreation,
                rootDoc,
                calls.getFirst(),
                finalSegmentSuffix,
                chainPlan.rootRendering(),
                analysis.sourceMultilineChain(),
                lineBudget,
                firstLineWidth,
                layout
            ));
        }
        if (
            root instanceof MethodCallExpr methodRoot
            && calls.size() == 1
            && !firstSegmentAttachedToRoot
            && methodRootCanKeepSingleSuffixAttached(methodRoot)
            && methodCallSegmentHasNoOwnContainedComments(calls.getFirst())
            && !methodCallSegmentHasComment(calls.getFirst())
        ) {
            if (
                methodCallSegmentHasBlockLambdaArgument(calls.getFirst())
                && blockLambdaSegmentFirstLine(compactSource.compact(methodRoot), calls.getFirst())
                        .filter(firstLine -> layoutWidth.line(LayoutWidth.LineBudget.BLOCK, firstLine) <= options.lineWidth())
                        .isPresent()
            ) {
                return Optional.empty();
            }
            if (
                chainPlan.rootRendering() == MethodCallChainSourcePlanner.ChainRootRendering.INLINE_PROMOTED_METHOD_CALL
                && promotedNoArgRootScopeOverflows(methodRoot, firstLineWidth)
            ) {
                return Optional.of(
                    Doc.concat(rootDoc, chainContinuation(methodCallChainSegment(calls.getFirst(), finalSegmentSuffix)))
                );
            }
            MethodCallExpr probeCall = calls.getFirst();
            Optional<Doc> expressionLambdaRoot = comments.speculatively(
                () -> expressionLambdaRootWithSingleSegment(methodRoot, probeCall, finalSegmentSuffix, lineBudget, layout)
            );
            if (expressionLambdaRoot.isPresent()) {
                return expressionLambdaRoot;
            }
            // LDM-3 (B8/D16): when the final segment carries breakable arguments the compact-with-broken-segment shape and
            // the one-segment-per-line fan-out are both legal broken layouts that differ in rendered line count, so rank
            // them with a single Doc.bestFitting and let the renderer keep whichever wraps least at the real output column,
            // rather than committing to a shape via the fixed-column LayoutWidth probe below. Only width-driven,
            // comment-free, non-source-shaped chains reach the ranker (promotion, source-multiline, and every
            // comment-guarded branch already returned above); it defers back to this imperative tail otherwise.
            Optional<Doc> rankedSingleSegment = rankedSingleSegmentChain(
                methodRoot,
                probeCall,
                finalSegmentSuffix,
                chainPlan.rootRendering(),
                analysis,
                lineBudget,
                layout
            );
            if (rankedSingleSegment.isPresent()) {
                return rankedSingleSegment;
            }
            if (compactRootFinalSegmentLineOverflows(
                    methodRoot,
                    calls.getFirst(),
                    finalSegmentSuffix,
                    lineBudget,
                    layout
                )) {
                Optional<Doc> compactRootWithBrokenSegment = comments.speculatively(
                    () -> compactRootWithBrokenFinalSegment(methodRoot, probeCall, finalSegmentSuffix, lineBudget, layout)
                );
                if (compactRootWithBrokenSegment.isPresent()) {
                    return compactRootWithBrokenSegment;
                }
                // The full chain (compact root plus the attached final segment) overflows at this line position, but the
                // final segment has no arguments to break (e.g. {@code .toRetry()}/{@code .build()}), so the previous
                // helper found nothing to wrap. When the root itself carries breakable arguments, break the root's
                // argument list instead and glue the segment to its close: {@code Type.create(}\n args \n{@code ).toRetry()}.
                // This is the same shape a source-multiline root already produces below; here it is reached for a flat
                // source root that only overflows because it renders at a deep nesting column (a wrapped call argument or
                // nested initializer), the column the caller threads through {@code lineBudget}/{@code firstLineWidth}.
                Optional<Doc> brokenRootWithAttachedSegment = comments.speculatively(
                    () -> brokenRootWithAttachedFinalSegment(methodRoot, probeCall, finalSegmentSuffix, lineBudget, layout)
                );
                if (brokenRootWithAttachedSegment.isPresent()) {
                    return brokenRootWithAttachedSegment;
                }
            }
            Optional<Doc> sourceMultilineRoot =
                comments.speculatively(() -> this.calls.sourceMultilineArguments(methodRoot));
            if (sourceMultilineRoot.isPresent()) {
                return Optional.of(
                    Doc.concat(
                        sourceMultilineRoot.orElseThrow(),
                        methodCallChainSegmentAttachedToRootClose(calls.getFirst(), finalSegmentSuffix, lineBudget)
                    )
                );
            }
            if (
                chainPlan.rootRendering()
                    == MethodCallChainSourcePlanner.ChainRootRendering.GROUPED_PROMOTED_METHOD_CALL
            ) {
                if (methodCallSegmentHasBlockLambdaArgument(methodRoot)) {
                    return Optional.of(
                        Doc.concat(rootDoc, methodCallChainSegment(calls.getFirst(), finalSegmentSuffix))
                    );
                }
                return Optional.of(
                    groupedPromotedRootWithSingleSegment(root, rootDoc, calls.getFirst(), finalSegmentSuffix, layout)
                );
            }
            return Optional.of(Doc.concat(rootDoc, methodCallChainSegment(calls.getFirst(), finalSegmentSuffix)));
        }
        // Record the width break only here, where the printer has committed to the broken one-segment-per-line chain
        // this method's PrinterWrap describes. The earlier deferral branches hand rendering to a different printer that
        // does not lay the chain out one per line, so recording before them could attribute a "N segments, one per line"
        // layout to a path that never produced it.
        chainWidthBreakExplain.record(expression, analysis, layout);
        // The multi-segment fall-through builds the exact one-segment-per-line fan-out
        // {@code chainFanOut} produces — root then each selector on its own dotted continuation line
        // ({@code Doc.concat(root, chainContinuation(root, methodCallChainSegments(calls, tail)))}) — so route it through
        // the shared source-neutral builder rather than reconstructing that shape inline. This is equivalent only when
        // {@code rootDoc} is the plain {@code expressionRenderer.format(root, root())} doc chainFanOut rebuilds; a
        // promoted/grouped/broken-object-creation root, a first-segment-attached root, or a root-trailing-comment-wrapped
        // root produces a different {@code rootDoc}, so those keep the inline construction.
        if (rootDocIsPlainExpressionRenderRoot) {
            return Optional.of(chainFanOut(root, calls, finalSegmentSuffix, layout));
        }
        // Object-creation root cutover seam (End-state A): a comment-free, non-anonymous, non-empty-argument
        // constructor-rooted fan-threshold chain ({@code new EndpointFactory(a, b, c, d).generate(…).blockFirst(…)}) whose
        // planner rendering is {@code BROKEN_OBJECT_CREATION} routes through the shared {@code chainFanOut} builder, whose
        // object-creation-root arm renders the constructor arguments through the source-neutral width-driven
        // {@link #promotedObjectCreationRootDoc}. This converges with the flat-selector pass, which already reaches
        // {@code chainFanOut} through the early canonical-fan route: both passes now render the root through the same
        // width-driven group, so a constructor line that fits stays flat on every pass instead of flipping to the
        // {@code brokenObjectCreationRenderer} force-break shape once a non-final selector's arguments span source lines.
        // Comment-free / block-lambda-free is required because {@code chainFanOut} re-renders the root and every selector a
        // second time (discarding {@code rootDoc}); a comment-bearing chain would re-claim its already-printed comments, so
        // it keeps the inline construction below (rendered once). The selectors render identically either way — the fan's
        // multi-segment tail is byte-for-byte the {@code chainContinuation(root, methodCallChainSegments(...))} the inline
        // construction builds — so only the root doc changes.
        if (
            objectCreationRootIsWidthDrivenFanEligible(root)
            && chainPlan.rootRendering() == MethodCallChainSourcePlanner.ChainRootRendering.BROKEN_OBJECT_CREATION
            && !firstSegmentAttachedToRoot
            && !analysis.hasComments()
            && !analysis.hasBlockLambdaArgument()
            && calls.stream().noneMatch(this::methodCallSegmentHasComment)
        ) {
            return Optional.of(chainFanOut(root, calls, finalSegmentSuffix, layout));
        }
        List<Doc> segments = methodCallChainSegments(calls, finalSegmentSuffix);
        return Optional.of(
            Doc.concat(
                rootDoc,
                chainContinuation(root, segments)
            )
        );
    }

    /**
     * The canonical-fan structural rule (End-state A) — see {@link MethodCallChainSourcePlanner#chainBreaksByRule} for
     * the link-count/root-kind thresholds, which that planner method owns as the single source of truth. This chain
     * printer and the variable-initializer path (via {@code InitializerChainShape.chainBreaksByRule}) both read the
     * identical verdict, so a fan-threshold chain routes onto the same source-neutral fan without the rule drifting
     * between two copies.
     */
    private boolean chainBreaksByRule(MethodCallChainSourcePlanner.MethodCallChainAnalysis analysis) {
        return methodChainPlanner.chainBreaksByRule(analysis);
    }

    private boolean methodRootCanKeepSingleSuffixAttached(MethodCallExpr methodRoot) {
        if (methodRoot.getAllContainedComments().isEmpty()) {
            return true;
        }
        if (
            methodCallSegmentHasLineComments(methodRoot)
            && !methodCallSegmentHasLeadingLineComment(methodRoot)
            && !methodCallSegmentHasNameComment(methodRoot)
        ) {
            return true;
        }
        return methodCallSegmentHasBlockLambdaArgument(methodRoot)
            && !methodCallSegmentHasLeadingLineComment(methodRoot)
            && !methodCallSegmentHasNameComment(methodRoot);
    }

    private boolean finalBlockLambdaSegmentCanStayCompact(
            MethodCallExpr expression,
            LayoutWidth.LineBudget lineBudget
    ) {
        if (!methodCallSegmentHasBlockLambdaArgument(expression) || methodCallSegmentHasComment(expression)) {
            return false;
        }
        String callPrefix = calls.methodCallPrefix(expression);
        return huggableBlockLambdaFirstLine.apply(callPrefix, expression.getArguments())
                .filter(firstLine -> layoutWidth.line(lineBudget, firstLine) <= options.lineWidth())
                .isPresent();
    }

    /**
     * Reports whether a method-call root's compact first line, with the single final segment attached
     * ({@code root.selector(args)…}), overflows — the flat-gate that decides whether the statement/field single-segment
     * chain must break onto the {@link #compactRootWithBrokenFinalSegment} / {@link #brokenRootWithAttachedFinalSegment}
     * broken shapes.
     *
     * <p>{@code layout} is threaded (#190) so the true continuation column ({@link LayoutContext#leftEdgePrefix()}) is
     * available at this flat-gate. It is NOT consulted here: the decision uses the fixed-budget
     * {@code layoutWidth.line(lineBudget, …)} floor. The statement/field callers pass their real {@link LayoutContext}
     * (a {@code STATEMENT}/{@code root()} context whose {@code leftEdgePrefix} is empty), matching the sibling
     * {@link #compactRootLineWidth} gate this parameter mirrors.
     */
    private boolean compactRootFinalSegmentLineOverflows(
            MethodCallExpr methodRoot,
            MethodCallExpr call,
            MethodCallChainTail finalSegmentSuffix,
            LayoutWidth.LineBudget lineBudget,
            LayoutContext layout
    ) {
        if (call.getArguments().stream().anyMatch(argument -> argument instanceof LambdaExpr)) {
            return false;
        }
        String typeArguments = call.getTypeArguments()
                .map(arguments -> "<" + types.compactJoinTypeLike(arguments) + ">")
                .orElse("");
        String compactLine = compactSource.compact(methodRoot)
            + "."
            + typeArguments
            + call.getNameAsString()
            + "("
            + methodCallSegmentArgumentsWidthText(call.getArguments())
            + ")"
            + finalSegmentSuffix;
        return layoutWidth.line(lineBudget, compactLine) > options.lineWidth();
    }

    /**
     * Breaks a method-call root's own argument list one argument per line and glues the single final segment to its
     * closing parenthesis: {@code Type.create(}\n args \n{@code ).toRetry()}.
     *
     * <p>Reached when {@link #compactRootFinalSegmentLineOverflows} reports the whole chain over width at its rendered
     * line position but the final segment has no arguments of its own to wrap ({@code .toRetry()}/{@code .build()}). It
     * mirrors the shape a source-multiline root produces, but is reached for a flat source root that overflows only
     * because it renders at a deep nesting column. Returns empty (leaving the existing flat layout) unless the root
     * carries breakable arguments, is not already source-multiline, has no comments, and its opener {@code Type.create(}
     * itself fits at {@code lineBudget}, so the broken shape is only chosen when it is both needed and valid.
     *
     * <p>{@code layout} is threaded (#190) so the true continuation column ({@link LayoutContext#leftEdgePrefix()}) is
     * available at this statement/field single-segment flat-gate. It is NOT consulted here: the opener-fit decision uses
     * the fixed-budget {@code layoutWidth.line(lineBudget, …)} floor (the statement/field callers pass an empty-prefix
     * context).
     */
    private Optional<Doc> brokenRootWithAttachedFinalSegment(
            MethodCallExpr methodRoot,
            MethodCallExpr call,
            MethodCallChainTail finalSegmentSuffix,
            LayoutWidth.LineBudget lineBudget,
            LayoutContext layout
    ) {
        if (
            methodRoot.getArguments().isEmpty()
            || !methodRoot.getAllContainedComments().isEmpty()
            || methodCallSegmentHasSourceMultilineBlockLambdaArgument(methodRoot)
            || methodRoot.getArguments().stream().anyMatch(argument -> argument instanceof LambdaExpr)
        ) {
            return Optional.empty();
        }
        if (layoutWidth.line(lineBudget, calls.methodCallPrefix(methodRoot) + "(") > options.lineWidth()) {
            return Optional.empty();
        }
        return Optional.of(
            Doc.concat(
                calls.brokenMethodCall(methodRoot),
                methodCallChainSegmentAttachedToRootClose(call, finalSegmentSuffix, lineBudget)
            )
        );
    }

    /**
     * LDM-3 (B8/D16): emits one ranked {@link Doc#bestFitting(java.util.List) bestFitting} for a comment-free,
     * width-driven single-segment method-call chain whose final segment carries breakable arguments, replacing the
     * {@link LayoutWidth}-probe gate that hand-picked the broken shape. The two alternatives are ordered flattest-first —
     * (1) the compact shape that keeps the root and selector on one line and breaks only the final segment's argument list
     * ({@code root.selector(}\n args \n{@code )}), and (2) the one-segment-per-line fan-out ({@code root}\n
     * {@code .selector(...)}) — and the renderer keeps whichever wraps the fewest rendered lines at the real output
     * column. This is reached only inside the broken-chain branch (the stay-flat gate already proved the flat form
     * overflows), so a break is required; between the two broken shapes the compact one keeps the root and selector
     * together and therefore wraps at least as few lines as the fan-out, which is the line-count decision the
     * fixed-indent {@code LayoutWidth} probe could not make and the reason the renderer, not a probe, now owns it.
     *
     * <p><strong>Source-shape gates run before ranking.</strong> Returns {@link Optional#empty()} — deferring to the
     * imperative tail below — unless the chain is chosen purely on width: the root renders through ordinary expression
     * dispatch ({@link MethodCallChainSourcePlanner.ChainRootRendering#EXPRESSION_RENDERER}, so promoted / builder /
     * broken-object-creation roots stay imperative), the chain and root arguments were not split across source lines, and
     * the final segment carries breakable, non-lambda arguments. It also defers when {@link #compactRootWithBrokenFinalSegment}
     * cannot build the compact shape (its opener does not fit), because then only the imperative broken-root fallback
     * applies and there is nothing to rank. A deliberately-multiline chain or a promoted root is a source-preserved shape,
     * never a width-ranked alternative, so ranking can never override it.
     *
     * <p><strong>Comment-bearing chains never reach here.</strong> The {@code !analysis.hasComments()} gate keeps them on
     * the imperative ladder, whose {@code comments.speculatively(...)} rollbacks own the first-builder-wins claim; building
     * both alternatives eagerly (as this does) would double-claim comments and trip the strict-claims guardrail.
     */
    private Optional<Doc> rankedSingleSegmentChain(
            MethodCallExpr methodRoot,
            MethodCallExpr call,
            MethodCallChainTail finalSegmentSuffix,
            MethodCallChainSourcePlanner.ChainRootRendering rootRendering,
            MethodCallChainSourcePlanner.MethodCallChainAnalysis analysis,
            LayoutWidth.LineBudget lineBudget,
            LayoutContext layout
    ) {
        if (
            rootRendering != MethodCallChainSourcePlanner.ChainRootRendering.EXPRESSION_RENDERER
            || analysis.hasComments()
            || analysis.sourceMultilineChain()
            || methodCallSegmentHasSourceMultilineBlockLambdaArgument(methodRoot)
            || call.getArguments().isEmpty()
            || call.getArguments().stream().anyMatch(LambdaExpr.class::isInstance)
            || methodCallSegmentHasBlockLambdaArgument(call)
        ) {
            return Optional.empty();
        }
        // Alternative 1 (flattest): keep the root and the selector on one line, breaking only the final segment's own
        // argument group. compactRootWithBrokenFinalSegment builds the same shape the imperative O5 path does; if its
        // guards reject the chain there is nothing width-driven to rank, so defer to the imperative tail.
        Optional<Doc> compactBrokenSegment =
            compactRootWithBrokenFinalSegment(methodRoot, call, finalSegmentSuffix, lineBudget, layout);
        if (compactBrokenSegment.isEmpty()) {
            return Optional.empty();
        }
        // Alternative 2 (fallback, always legal): the one-segment-per-line fan-out — the root alone, then the selector on
        // its own continuation line. This is the shape Branch P produces for a broken chain; here it competes on line
        // count with the compact shape rather than being reached only after a width probe.
        Doc fanOut = chainFanOut(methodRoot, List.of(call), finalSegmentSuffix, layout);
        return Optional.of(Doc.bestFitting(List.of(compactBrokenSegment.orElseThrow(), fanOut)));
    }

    /**
     * LDM-3g (#210): the object-creation-rooted sibling of {@link #rankedSingleSegmentChain}. Emits one ranked
     * {@link Doc#bestFitting(java.util.List) bestFitting} for a comment-free, width-driven single-segment chain whose root
     * is a source-compact constructor ({@code new Type(args).selector(...)}) and whose final segment carries breakable
     * arguments, replacing the {@link #objectRootSingleSegmentChain} first-line {@code LayoutWidth} probe that hand-picked
     * the broken shape. The two alternatives are ordered flattest-first — (1) the compact shape that keeps the
     * {@code new Type(args)} root and the selector on one line and breaks only the final segment's argument list
     * ({@code new Type(args).selector(}\n args \n{@code )}), reusing the same {@link #compactRootWithBrokenFinalSegment}
     * the method-root ranker uses (it already builds this shape for object-creation roots), and (2) the one-segment-per-line
     * fan-out ({@code new Type(args)}\n{@code .selector(...)}) — and the renderer keeps whichever wraps the fewest rendered
     * lines at the real output column. Each alternative's inner groups still break the constructor's own argument list when
     * the column demands it, so the deeper "constructor args broken too" shape the imperative probe reached at narrow
     * widths remains reachable through alternative (1)/(2)'s own nested breaks; the ranker only owns the compact-versus-
     * fan-out verdict the fixed-column probe could not measure.
     *
     * <p><strong>Same gates as the method-root ranker.</strong> Returns {@link Optional#empty()} — deferring to
     * {@link #objectRootSingleSegmentChain} — unless the chain is chosen purely on width: the root is a compact-source
     * constructor rendered through ordinary expression dispatch
     * ({@link MethodCallChainSourcePlanner.ChainRootRendering#EXPRESSION_RENDERER}, so a multiline constructor promoted to
     * {@link MethodCallChainSourcePlanner.ChainRootRendering#BROKEN_OBJECT_CREATION} stays imperative), the chain and the
     * constructor arguments were not split across source lines, and the final segment carries breakable, non-lambda
     * arguments. A deliberately-multiline chain or a source-broken constructor is a source-preserved shape, never a
     * width-ranked alternative, so ranking can never override it. The {@code !analysis.hasComments()} gate keeps
     * comment-bearing chains on the imperative ladder whose {@code comments.speculatively(...)} rollbacks own the
     * first-builder-wins claim; building both alternatives eagerly (as this does) would double-claim comments.
     */
    private Optional<Doc> rankedObjectRootSingleSegmentChain(
            ObjectCreationExpr objectCreation,
            MethodCallExpr call,
            MethodCallChainTail finalSegmentSuffix,
            Doc rootDoc,
            MethodCallChainSourcePlanner.ChainRootRendering rootRendering,
            MethodCallChainSourcePlanner.MethodCallChainAnalysis analysis,
            LayoutWidth.LineBudget lineBudget,
            LayoutContext layout
    ) {
        if (
            rootRendering != MethodCallChainSourcePlanner.ChainRootRendering.EXPRESSION_RENDERER
            || analysis.hasComments()
            || analysis.sourceMultilineChain()
            // The object-creation-root argument list is width-driven, so a source-multiline constructor root is a
            // ranking candidate like any other; no source-shape bail here.
            || objectCreation.getAnonymousClassBody().isPresent()
            || !finalSegmentSuffix.isEmpty()
            || call.getArguments().isEmpty()
            || call.getArguments().stream().anyMatch(LambdaExpr.class::isInstance)
            || methodCallSegmentHasBlockLambdaArgument(call)
        ) {
            return Optional.empty();
        }
        // Alternative 1 (flattest): keep the constructor and the selector on one line, breaking only the final segment's
        // own argument group. compactRootWithBrokenFinalSegment already handles object-creation roots; if its guards reject
        // the chain (its opener does not fit) there is nothing width-driven to rank, so defer to the imperative tail.
        Optional<Doc> compactBrokenSegment =
            compactRootWithBrokenFinalSegment(objectCreation, call, finalSegmentSuffix, lineBudget, layout);
        if (compactBrokenSegment.isEmpty()) {
            return Optional.empty();
        }
        // Alternative 2 (fallback, always legal): the one-segment-per-line fan-out — the constructor alone, then the
        // selector on its own continuation line. This is the shape objectRootSingleSegmentChain's overflow branch produces;
        // here it competes on line count with the compact shape rather than being reached only after a first-line probe.
        Doc fanOut = chainFanOut(objectCreation, List.of(call), finalSegmentSuffix, layout);
        return Optional.of(Doc.bestFitting(List.of(compactBrokenSegment.orElseThrow(), fanOut)));
    }

    /**
     * Builds the one-segment-per-line fan-out for a chain from the AST alone (root then each selector on its own dotted
     * continuation line, the final one carrying {@code tail}). Delegates to {@link ChainFanLayout}, which owns the
     * fan-shape rules and the source-neutral root builders.
     */
    Doc chainFanOut(
            Expression root,
            List<MethodCallExpr> calls,
            MethodCallChainTail tail,
            LayoutContext layout
    ) {
        return chainFan.chainFanOut(root, calls, tail, layout);
    }

    /**
     * Reports whether {@code expression}'s chain root is a trivial receiver — the case in which {@link #chainFanOut} keeps
     * the first selector on the root's opening line. The lambda-body arrow seam asks this to keep a trivial-receiver body
     * anchored on the {@code ->} line. Delegates to {@link ChainFanLayout}.
     */
    boolean chainRootIsTrivialReceiver(MethodCallExpr expression) {
        return chainFan.chainRootIsTrivialReceiver(expression);
    }

    /**
     * Reports whether {@link #chainFanOut} may render an object-creation root through the source-neutral width-driven
     * promotion. Delegates to {@link ChainFanLayout}.
     */
    private boolean objectCreationRootIsWidthDrivenFanEligible(Expression root) {
        return chainFan.objectCreationRootIsWidthDrivenFanEligible(root);
    }

    private boolean methodCallSegmentHasNoOwnContainedComments(MethodCallExpr expression) {
        List<Comment> containedComments = expression.getAllContainedComments();
        if (containedComments.isEmpty()) {
            return true;
        }
        return expression.getScope()
                .map(scope -> {
                    List<Comment> scopeComments = scope.getAllContainedComments();
                    return containedComments.stream().allMatch(scopeComments::contains);
                })
                .orElse(false);
    }

    private boolean canKeepSuffixAttachedToPromotedBlockLambdaRoot(
            MethodCallChainSourcePlanner.MethodCallChainPlan chainPlan,
            Expression root,
            List<MethodCallExpr> calls,
            MethodCallChainTail finalSegmentSuffix
    ) {
        if (
            chainPlan.rootRendering() != MethodCallChainSourcePlanner.ChainRootRendering.GROUPED_PROMOTED_METHOD_CALL
            || !(root instanceof MethodCallExpr methodRoot)
            || calls.size() != 1
            || !methodCallSegmentHasBlockLambdaArgument(methodRoot)
            || !methodRootCanKeepSingleSuffixAttached(methodRoot)
            || methodCallSegmentHasComment(calls.getFirst())
        ) {
            return false;
        }
        return true;
    }

    private Optional<MethodCallChainSourcePlanner.MethodCallChainPlan> promoteFirstBlockLambdaCallWithLambdaBodyComments(
            MethodCallChainSourcePlanner.MethodCallChainAnalysis analysis,
            MethodCallChainSourcePlanner.MethodCallChainPlan chainPlan
    ) {
        List<MethodCallExpr> calls = analysis.calls();
        if (
            chainPlan.root() != analysis.root()
            || calls.size() < 2
            || !methodChainPlanner.promotesFirstCall(analysis.root())
        ) {
            return Optional.empty();
        }
        MethodCallExpr firstCall = calls.getFirst();
        if (
            !methodCallSegmentHasBlockLambdaArgument(firstCall)
            || methodCallSegmentHasLeadingLineComment(firstCall)
            || methodCallSegmentHasNameComment(firstCall)
        ) {
            return Optional.empty();
        }
        return Optional.of(
            new MethodCallChainSourcePlanner.MethodCallChainPlan(
                firstCall,
                new ArrayList<>(calls.subList(1, calls.size())),
                MethodCallChainSourcePlanner.ChainRootRendering.GROUPED_PROMOTED_METHOD_CALL
            )
        );
    }

    private boolean canAttachFirstSegmentToSimpleRoot(
            MethodCallExpr expression,
            MethodCallChainSourcePlanner.MethodCallChainPlan chainPlan,
            List<MethodCallExpr> calls,
            MethodCallChainSourcePlanner.MethodCallChainAnalysis analysis
    ) {
        if (
            chainPlan.rootRendering() != MethodCallChainSourcePlanner.ChainRootRendering.EXPRESSION_RENDERER
            || calls.size() < 2
            || analysis.hasComments()
            || !analysis.sourceMultilineChain()
            || chainPlan.root() instanceof MethodCallExpr
            || chainPlan.root() instanceof ObjectCreationExpr
            || rootIsEnclosedFanningChain(chainPlan.root())
            || sourceFirstLineIsOnlyChainRoot(chainPlan.root(), expression)
        ) {
            return false;
        }
        MethodCallExpr firstCall = calls.getFirst();
        return (
            !methodCallSegmentHasBlockLambdaArgument(firstCall)
            && (sourceShapePolicy.fitsOnOneLine(firstCall, lineWidth(LayoutWidth.LineBudget.CURRENT))
                || layoutWidth.line(LayoutWidth.LineBudget.CURRENT, this.calls.methodCallPrefix(firstCall) + "(") <= options.lineWidth())
        );
    }

    /**
     * Reports whether a chain root is a parenthesized (or parenthesized-cast) expression wrapping a fan-threshold
     * method-call chain (its inner chain fans by the canonical rule). Delegates to {@link ChainFanLayout}.
     */
    private boolean rootIsEnclosedFanningChain(Expression root) {
        return chainFan.rootIsEnclosedFanningChain(root);
    }

    private Doc firstSegmentAttachedToSimpleRootDoc(
            Expression root,
            MethodCallExpr firstCall,
            Optional<SourceMultilineLambdaCallLayout.AttachedFirstSegment> sourceMultilineLambdaPlan
    ) {
        if (sourceMultilineLambdaPlan.isPresent()) {
            SourceMultilineLambdaCallLayout.AttachedFirstSegment plan = sourceMultilineLambdaPlan.orElseThrow();
            Optional<Doc> sourceMultilineLambdaBody = comments.speculatively(
                () -> sourceMultilineLambdaCalls.attachedFirstSegment(root, firstCall)
            );
            if (sourceMultilineLambdaBody.isPresent()) {
                return sourceMultilineLambdaBody.orElseThrow();
            }
            Optional<Doc> huggableExpressionLambda = comments.speculatively(
                () -> huggableExpressionLambdaArguments.render(
                    plan.chainSegmentPrefix(),
                    firstCall.getArguments(),
                    expressionLambdaColumnWidthFallback()
                )
            );
            if (huggableExpressionLambda.isPresent()) {
                return Doc.concat(expressionRenderer.format(root, LayoutContext.root()), huggableExpressionLambda.orElseThrow());
            }
            return Doc.concat(expressionRenderer.format(root, LayoutContext.root()), methodCallChainSegment(firstCall));
        }
        if (sourceShapePolicy.fitsOnOneLine(firstCall, lineWidth(LayoutWidth.LineBudget.CURRENT))) {
            return inlineMethodCall(firstCall);
        }
        return brokenFirstSegmentAttachedToSimpleRoot(root, firstCall);
    }

    private Optional<Doc> sourceMultilineFirstExpressionLambdaChain(
            MethodCallExpr expression,
            Expression root,
            List<MethodCallExpr> calls,
            MethodCallChainTail finalSegmentSuffix,
            SourceMultilineLambdaChainPlan sourceMultilineLambdaPlan
    ) {
        if (
            calls.size() != 2
            || root instanceof MethodCallExpr
            || root instanceof ObjectCreationExpr
            || !hasSingleExpressionLambdaArgumentAnyShape(calls.getFirst())
            || sourceMultilineLambdaPlan.firstCall().isEmpty()
            || sourceFirstLineIsOnlyChainRoot(root, expression)
        ) {
            return Optional.empty();
        }
        return Optional.of(
            Doc.concat(
                firstSegmentAttachedToSimpleRootDoc(root, calls.getFirst(), sourceMultilineLambdaPlan.firstCall()),
                methodCallChainSegment(calls.get(1), finalSegmentSuffix)
            )
        );
    }

    private String firstSegmentAttachedToSimpleRootFirstLine(Expression root, MethodCallExpr firstCall) {
        if (sourceShapePolicy.fitsOnOneLine(firstCall, lineWidth(LayoutWidth.LineBudget.CURRENT))) {
            return compactSource.compact(firstCall);
        }
        String typeArguments = firstCall.getTypeArguments()
                .map(arguments -> "<" + types.compactJoinTypeLike(arguments) + ">")
                .orElse("");
        return compactSource.compact(root) + "." + typeArguments + firstCall.getNameAsString() + "(";
    }

    private Doc brokenFirstSegmentAttachedToSimpleRoot(Expression root, MethodCallExpr expression) {
        String typeArguments = expression.getTypeArguments()
                .map(arguments -> "<" + types.compactJoinTypeLike(arguments) + ">")
                .orElse("");
        String prefix = "." + typeArguments + expression.getNameAsString();
        return Doc.concat(
            expressionRenderer.format(root, LayoutContext.root()),
            Doc.text(prefix + "("),
            Doc.indent(
                Doc.indent(
                    Doc.indent(
                        Doc.concat(
                            Doc.HARD_LINE,
                            calls.methodCallArgumentList(prefix, expression.getArguments(), Doc.HARD_LINE)
                        )
                    )
                )
            ),
            Doc.indent(Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.text(")"))))
        );
    }

    private String methodCallSegmentPrefixText(MethodCallExpr expression) {
        return "."
            + expression.getTypeArguments()
                    .map(arguments -> "<" + types.compactJoinTypeLike(arguments) + ">")
                    .orElse("")
            + expression.getNameAsString();
    }

    private boolean forcedSingleCallPrefixOverflows(
            MethodCallBreakMode breakMode,
            MethodCallExpr expression,
            LayoutWidth.LineBudget lineBudget
    ) {
        return breakMode.isForced()
            && expression.getScope().isPresent()
            && methodCallSegmentHasBlockLambdaArgument(expression)
            && layoutWidth.line(lineBudget, calls.methodCallPrefix(expression) + "(") > options.lineWidth();
    }

    private boolean chainHasSourceMultilineArguments(
            MethodCallChainSourcePlanner.MethodCallChainAnalysis analysis,
            SourceMultilineLambdaChainPlan sourceMultilineLambdaPlan
    ) {
        if (
            analysis.root() instanceof MethodCallExpr methodRoot
            && !analysis.calls().isEmpty()
            && sourceMultilineLambdaPlan.rootCanAttachExpressionLambdaBody()
        ) {
            return true;
        }
        for (int index = 0; index < Math.max(0, analysis.calls().size() - 1); index++) {
            MethodCallExpr call = analysis.calls().get(index);
            if (
                methodCallSegmentHasSourceMultilineBlockLambdaArgument(call)
                || sourceMultilineLambdaPlan.callCanAttachExpressionLambdaBody(index)
            ) {
                return true;
            }
        }
        return false;
    }

    private SourceMultilineLambdaChainPlan sourceMultilineLambdaChainPlan(
            MethodCallChainSourcePlanner.MethodCallChainAnalysis analysis
    ) {
        return sourceMultilineLambdaChainPlan(analysis.root(), analysis.calls());
    }

    private SourceMultilineLambdaChainPlan sourceMultilineLambdaChainPlan(
            Expression root,
            List<MethodCallExpr> calls
    ) {
        Optional<SourceMultilineLambdaCallLayout.AttachedFirstSegment> firstCall = calls.isEmpty()
            ? Optional.empty()
            : sourceMultilineLambdaCalls.attachedFirstSegmentPlan(calls.getFirst());
        boolean rootCanAttachExpressionLambdaBody = root instanceof MethodCallExpr methodRoot
            && sourceMultilineLambdaCalls.canAttachExpressionLambdaBody(methodRoot);
        List<Boolean> callCanAttachExpressionLambdaBody = new ArrayList<>(calls.size());
        for (int index = 0; index < calls.size(); index++) {
            callCanAttachExpressionLambdaBody.add(
                index == 0
                    ? firstCall.isPresent()
                    : sourceMultilineLambdaCalls.canAttachExpressionLambdaBody(calls.get(index))
            );
        }
        return new SourceMultilineLambdaChainPlan(
            rootCanAttachExpressionLambdaBody,
            callCanAttachExpressionLambdaBody,
            firstCall
        );
    }

    private Doc methodCallChainRootDoc(
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
     * Whether {@link #expressionRenderedChainRoot} renders an {@link MethodCallChainSourcePlanner.ChainRootRendering#EXPRESSION_RENDERER}
     * root through {@link MethodCallPrinter#brokenMethodCall} — a multi-argument root that overflows its first line (or is
     * a source-multiline type-like root that does not fit) — rather than through ordinary expression dispatch. The negation
     * is the "plain expression-renderer root" case: {@code expressionRenderer.format(root, LayoutContext.root())}, which is
     * exactly the root {@link #chainFanOut} builds, so the multi-segment fall-through can route through the shared fan-out
     * builder byte-identically only when this returns {@code false}. Side-effect-free (no comment claim), so evaluating it
     * to steer the fall-through never double-claims a comment.
     */
    private boolean expressionRenderedChainRootBreaksMethodCall(
            Expression root,
            ToIntFunction<String> firstLineWidth
    ) {
        return root instanceof MethodCallExpr methodCall
            && methodCall.getArguments().size() > 1
            && firstLineWidth.applyAsInt(compactSourceWidthText(methodCall)) > options.lineWidth();
    }

    private Doc singleSegmentMethodRootDoc(MethodCallExpr methodRoot) {
        Optional<Doc> sourceMultilineArguments =
            comments.speculatively(() -> calls.sourceMultilineArguments(methodRoot));
        if (sourceMultilineArguments.isPresent()) {
            return sourceMultilineArguments.orElseThrow();
        }
        Optional<Doc> brokenScopedMethodRoot =
            comments.speculatively(() -> brokenTypeLikeScopedMethodRoot(methodRoot));
        if (brokenScopedMethodRoot.isPresent()) {
            return brokenScopedMethodRoot.orElseThrow();
        }
        if (
            layoutWidth.line(LayoutWidth.LineBudget.CURRENT, compactSourceWidthText(methodRoot)) > options.lineWidth()
            || methodCallRootScopeOverflows(methodRoot)
        ) {
            return methodCallChain(methodRoot, MethodCallBreakMode.FORCED, LayoutContext.root())
                    .orElseGet(() -> expressionRenderer.format(methodRoot, LayoutContext.root()));
        }
        return expressionRenderer.format(methodRoot, LayoutContext.root());
    }

    private Optional<Doc> brokenTypeLikeScopedMethodRoot(MethodCallExpr methodRoot) {
        Optional<MethodCallExpr> scopedCall = methodRoot.getScope()
                .filter(MethodCallExpr.class::isInstance)
                .map(MethodCallExpr.class::cast)
                .filter(call -> call.getArguments().size() > 1)
                .filter(call -> call.getScope().filter(methodChainPlanner::promotesFirstCall).isPresent())
                .filter(call -> layoutWidth.line(LayoutWidth.LineBudget.CURRENT, compactSourceWidthText(call)) > options.lineWidth());
        if (scopedCall.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(
            Doc.concat(
                calls.brokenMethodCall(scopedCall.orElseThrow()),
                chainContinuation(methodCallChainSegment(methodRoot))
            )
        );
    }

    private boolean methodCallRootScopeOverflows(MethodCallExpr methodRoot) {
        return methodRoot.getScope()
                .filter(MethodCallExpr.class::isInstance)
                .map(MethodCallExpr.class::cast)
                .map(scopedCall -> layoutWidth.line(LayoutWidth.LineBudget.CURRENT, 
                        compactSourceWidthText(scopedCall)
                    ) > options.lineWidth()
                )
                .orElse(false);
    }

    private String compactSourceWidthText(Expression expression) {
        return rawSource.normalizeWhitespace(rawSource.rawWithoutOwnComment(expression));
    }

    private Doc groupedPromotedMethodCall(MethodCallExpr expression) {
        Optional<Doc> sourceMultilineArguments =
            comments.speculatively(() -> calls.sourceMultilineArguments(expression));
        if (sourceMultilineArguments.isPresent()) {
            return sourceMultilineArguments.orElseThrow();
        }
        if (
            expression.getArguments().size() > 1
            && !sourceShapePolicy.fitsOnOneLine(expression, lineWidth(LayoutWidth.LineBudget.CURRENT))
        ) {
            return calls.brokenMethodCall(expression);
        }
        Optional<Doc> huggableExpressionLambda =
            comments.speculatively(() -> groupedPromotedExpressionLambda(expression));
        if (huggableExpressionLambda.isPresent()) {
            return huggableExpressionLambda.orElseThrow();
        }
        if (methodCallSegmentHasBlockLambdaArgument(expression)) {
            return blockLambdaSegmentFirstLine(compactSource.compact(expression.getScope().orElseThrow()), expression)
                    .filter(firstLine -> layoutWidth.line(LayoutWidth.LineBudget.BLOCK, firstLine) <= options.lineWidth())
                    .map(ignored -> expressionRenderer.format(expression, LayoutContext.root()))
                    .orElseGet(() -> Doc.concat(
                            expressionRenderer.format(expression.getScope().orElseThrow(), LayoutContext.root()),
                            chainContinuation(methodCallChainSegment(expression))
                    ));
        }
        return expression.getScope()
                .map(scope -> Doc.group(
                        Doc.concat(
                            expressionRenderer.format(scope, LayoutContext.root()),
                            softChainContinuation(methodCallChainSegment(expression))
                        )
                ))
                .orElseGet(() -> expressionRenderer.format(expression, LayoutContext.root()));
    }

    /**
     * A no-op stub: a promoted segment's trailing expression lambda is hugged or exploded by width upstream, so this
     * always returns empty. Retained so the speculative dispatch in {@link #groupedPromotedMethodCall} stays wired.
     */
    private Optional<Doc> groupedPromotedExpressionLambda(MethodCallExpr expression) {
        return Optional.empty();
    }

    private Doc groupedPromotedRootWithSingleSegment(
            Expression root,
            Doc rootDoc,
            MethodCallExpr expression,
            MethodCallChainTail finalSegmentSuffix,
            LayoutContext layout
    ) {
        if (methodCallSegmentHasBlockLambdaArgument(expression)) {
            return blockLambdaSegmentFirstLine(compactSource.compact(root), expression)
                    .filter(firstLine -> layoutWidth.line(LayoutWidth.LineBudget.BLOCK, firstLine) <= options.lineWidth())
                    .map(ignored -> Doc.concat(rootDoc, methodCallChainSegment(expression, finalSegmentSuffix)))
                    .orElseGet(() -> Doc.concat(
                            rootDoc,
                            chainContinuation(methodCallChainSegment(expression, finalSegmentSuffix))
                    ));
        }
        return Doc.group(
            Doc.concat(
                rootDoc,
                softChainContinuation(methodCallChainSegment(expression, finalSegmentSuffix))
            )
        );
    }

    private Optional<String> blockLambdaSegmentFirstLine(String root, MethodCallExpr expression) {
        String prefix = root
            + "."
            + expression
                    .getTypeArguments()
                    .map(arguments -> "<" + types.compactJoinTypeLike(arguments) + ">")
                    .orElse("")
            + expression.getNameAsString();
        return huggableBlockLambdaFirstLine.apply(prefix, expression.getArguments());
    }

    /**
     * Refuses the compact-root-with-broken-final-segment shape for an object-creation-rooted chain whose final segment is
     * a call with exactly one <em>simple</em> argument, so the chain fans the selector onto its own dotted continuation
     * line with that argument kept inline ({@code new X(...)}\n{@code .selector(arg)}) rather than opening the single
     * argument ({@code new X(...).selector(}\n{@code arg}\n{@code )}).
     *
     * <p>Declining the arg-opening shape here makes both broken-chain entry points converge on the fan-out: the direct
     * {@code compactRootWithBrokenFinalSegment} call in the forced single-segment branch and the compact alternative of
     * {@link #rankedObjectRootSingleSegmentChain} both see {@link Optional#empty()} and fall through to
     * {@link #objectRootSingleSegmentChain}, whose fan-out branch renders the single-simple-argument tail compact on its
     * dotted line (see the {@code singleSimpleMethodCallSegmentArgument} case there).
     *
     * <p>LDM-2f (#190), revising #236, first activated this only for the return chain (behind a non-empty
     * {@link LayoutContext#leftEdgePrefix()}). PR #279 review (#1) generalizes it to <strong>every</strong> caller: a
     * statement chain ({@code new ProfileRequest(...).submit(10);}) wants the same {@code new ProfileRequest(...)}\n
     * {@code .submit(10)} shape rather than the arg-opened {@code .submit(}\n{@code 10}\n{@code )} — "break on the dot,
     * not inside a single-arg call". The verdict is a pure function of the AST (an {@link ObjectCreationExpr} root and a
     * single simple selector argument), so it is a fixpoint regardless of any leading prefix; the enclosing width probe in
     * {@link #objectRootSingleSegmentChain} still decides flat-versus-fan. Restricted to {@link ObjectCreationExpr} roots;
     * "simple" mirrors {@link ControlConditionMethodCallLayout#hasComplexArgument}'s inverse via
     * {@link #singleSimpleMethodCallSegmentArgument} ({@code NameExpr | FieldAccessExpr | ThisExpr | SuperExpr |
     * LiteralExpr}); a lambda, method-call, multi-argument, or already-multiline tail is not simple and still opens
     * exactly as before.
     */
    private boolean refuseOpeningSingleSimpleObjectRootChainTail(
            Expression root,
            MethodCallExpr call
    ) {
        return root instanceof ObjectCreationExpr
            && singleSimpleMethodCallSegmentArgument(call);
    }

    private Optional<Doc> compactRootWithBrokenFinalSegment(Expression root, MethodCallExpr call) {
        return compactRootWithBrokenFinalSegment(
            root,
            call,
            MethodCallChainTail.EMPTY,
            LayoutWidth.LineBudget.CURRENT,
            LayoutContext.root()
        );
    }

    private Optional<Doc> compactRootWithBrokenFinalSegment(
            Expression root,
            MethodCallExpr call,
            LayoutWidth.LineBudget lineBudget,
            LayoutContext layout
    ) {
        return compactRootWithBrokenFinalSegment(root, call, MethodCallChainTail.EMPTY, lineBudget, layout);
    }

    private Optional<Doc> compactRootWithBrokenFinalSegment(
            Expression root,
            MethodCallExpr call,
            MethodCallChainTail finalSegmentSuffix,
            LayoutContext layout
    ) {
        return compactRootWithBrokenFinalSegment(
            root,
            call,
            finalSegmentSuffix,
            LayoutWidth.LineBudget.CURRENT,
            layout
        );
    }

    private Optional<Doc> compactRootWithBrokenFinalSegment(
            Expression root,
            MethodCallExpr call,
            MethodCallChainTail finalSegmentSuffix,
            LayoutWidth.LineBudget lineBudget,
            LayoutContext layout
    ) {
        if (call.getArguments().isEmpty()) {
            return Optional.empty();
        }
        if (refuseOpeningSingleSimpleObjectRootChainTail(root, call)) {
            return Optional.empty();
        }
        if (
            root instanceof MethodCallExpr methodRoot
            && methodCallSegmentHasSourceMultilineBlockLambdaArgument(methodRoot)
        ) {
            return Optional.empty();
        }
        // The object-creation-root compact-root path has no source-shape bail: whether the constructor root stays compact
        // is decided by the width gate below (`compactRootFirstLineFits`), so a source-multiline root converges on the
        // same verdict.
        if (
            !(root instanceof MethodCallExpr)
            && !(root instanceof ObjectCreationExpr)
            && !(root instanceof FieldAccessExpr)
        ) {
            return Optional.empty();
        }
        String typeArguments = call.getTypeArguments()
                .map(arguments -> "<" + types.compactJoinTypeLike(arguments) + ">")
                .orElse("");
        String callPrefix = compactSource.compact(root) + "." + typeArguments + call.getNameAsString();
        // An object-creation root renders here as its whole compact form ({@code compactSource.compact(root)}) on the
        // first line, so a wide constructor ({@code new LogValidator(12 args)}) would keep 256 columns flat and only break
        // the final selector's arguments. Gate the compact-root shape on WIDTH: when the constructor-plus-selector opener
        // ({@code new Type(args).selector(}) already overflows, defer so the chain falls through to the broken
        // object-creation fan, which breaks the constructor's own argument list by width. Scoped to object-creation
        // roots; method-call/field roots keep their existing routing.
        if (
            root instanceof ObjectCreationExpr
            && compactRootLineWidth(root, callPrefix + "(", lineBudget, layout) > options.lineWidth()
        ) {
            return Optional.empty();
        }
        if (!compactRootFirstLineFits(root, callPrefix, call.getArguments(), lineBudget, layout)) {
            return Optional.empty();
        }
        Optional<Doc> huggableLambda =
            comments.speculatively(() -> huggableBlockLambdaArguments.apply(callPrefix, call.getArguments()));
        if (huggableLambda.isPresent()) {
            return Optional.of(Doc.concat(huggableLambda.orElseThrow(), finalSegmentSuffix.doc()));
        }
        String prefix = callPrefix + "(";
        if (
            root instanceof ObjectCreationExpr
            && compactRootLineWidth(root, prefix, lineBudget, layout) > options.lineWidth()
        ) {
            return Optional.empty();
        }
        if (layoutWidth.line(lineBudget, prefix + ")") > options.lineWidth()) {
            return Optional.empty();
        }
        return Optional.of(
            Doc.concat(
                Doc.text(prefix),
                Doc.indent(
                    Doc.concat(
                        Doc.HARD_LINE,
                        calls.methodCallArgumentList(callPrefix, call.getArguments(), Doc.HARD_LINE)
                    )
                ),
                Doc.HARD_LINE,
                Doc.text(")" + finalSegmentSuffix)
            )
        );
    }

    private boolean compactRootFirstLineFits(
            Expression root,
            String callPrefix,
            NodeList<Expression> arguments,
            LayoutWidth.LineBudget lineBudget,
            LayoutContext layout
    ) {
        Optional<String> blockLambdaFirstLine = huggableBlockLambdaFirstLine.apply(callPrefix, arguments);
        if (
            blockLambdaFirstLine
                    .filter(
                        firstLine -> compactRootLineWidth(
                            root,
                            firstLine,
                            lineBudget,
                            layout
                        ) > options.lineWidth()
                    )
                    .isPresent()
        ) {
            return false;
        }
        Optional<ExpressionLambdaArgumentLayout.Plan> expressionLambdaPlan = expressionLambdaArgumentPlan.plan(
            callPrefix,
            arguments,
            layout
        );
        return expressionLambdaPlan
                .map(plan -> plan.firstLineFits(
                        line -> compactRootLineWidth(root, line, lineBudget, layout),
                        options.lineWidth()
                ))
                .orElse(true);
    }

    /**
     * Measures a compact chain root's first line ({@code root.selector(args…}) at the column where the root renders.
     *
     * <p>The root's start column reconstructed from {@code range.begin.column} is a source-column read that understates
     * the rendered column once the root is reindented shallower than its true block/type depth. This gate also considers
     * the root's rendered indentation ({@link LayoutWidth#nodeIndentWidth}, which counts every enclosing type and block)
     * and takes the <em>wider</em> of the two (#217), so a root reindented flush-left inside deep nesting is not measured
     * as fitting at its stale shallow column and hugged over width. This mirrors the sibling
     * {@link ExpressionLambdaArgumentLayout} first-line gate (#226) and the depth-aware chain probes (#162).
     *
     * <p>Two measurement modes, keyed on whether a caller has threaded the same-line leading prefix through
     * {@link LayoutContext#leftEdgePrefix()}:
     *
     * <ul>
     *   <li><strong>Prefix threaded (#190).</strong> When a caller supplies its fixed leading prefix — the
     *   {@code return } chain threads {@code "return "} — the rendered column is known exactly:
     *   {@code nodeIndentWidth(root) + leftEdgePrefix.length() + firstLine.length()}. The source-column floor is
     *   <em>dropped</em>, because it is only a stand-in for the prefix this arm measures directly and could over- or
     *   under-count when the root is reindented away from its source column. A reindented-flat return chain whose compact
     *   first line is under budget by the stale source column but over budget once {@code return } is added (the
     *   {@code return } is worth exactly the missing width) is then correctly measured over width and fanned out.</li>
     *   <li><strong>No prefix threaded.</strong> Every other caller passes {@code root()} (empty prefix), so the wider-of
     *   rule applies: {@code max(source-column, nodeIndentWidth) + firstLine.length()}. The {@code nodeIndentWidth} arm
     *   keeps a root reindented flush-left inside deep nesting from being measured as fitting at its stale shallow column
     *   and hugged over width; the source column is kept as the <em>floor</em> because it is where these callers'
     *   unmodelled leading prefix (a {@code NAME … = }, a continuation indent) lives. Dropping the floor for these callers
     *   under-measures and regresses {@code source-multiline-method-root-chain-initializer}, so the floor stays for them.</li>
     * </ul>
     *
     * <p>This mirrors the sibling {@link ExpressionLambdaArgumentLayout} first-line gate (#226) and the depth-aware chain
     * probes (#162).
     */
    private int compactRootLineWidth(
            Expression root,
            String firstLine,
            LayoutWidth.LineBudget lineBudget,
            LayoutContext layout
    ) {
        // With the same-line prefix threaded, measure at the exact rendered column and drop the source-column floor,
        // which is only a stand-in for this prefix.
        if (!layout.leftEdgePrefix().isEmpty()) {
            return layoutWidth.nodeIndentWidth(root) + layout.leftEdgePrefix().length() + firstLine.length();
        }
        return root.getRange()
                .map(range -> Math.max(
                    Math.max(0, range.begin.column + 1) + firstLine.length(),
                    layoutWidth.nodeIndentWidth(root) + firstLine.length()))
                .orElseGet(() -> layoutWidth.line(lineBudget, firstLine));
    }

    private boolean expressionLambdaBodyOpenerOverflows(
            Expression root,
            String callPrefix,
            NodeList<Expression> arguments,
            LayoutContext layout
    ) {
        return expressionLambdaArgumentPlan.plan(callPrefix, arguments, layout)
                .filter(plan -> plan.bodyOpenerFitsOnContinuation(lineWidth(LayoutWidth.LineBudget.CONTINUATION), options.lineWidth()))
                .filter(plan -> plan.bodyOpenerOverflows(
                        line -> compactRootLineWidth(root, line, LayoutWidth.LineBudget.CURRENT, layout),
                        options.lineWidth()
                ))
                .isPresent();
    }

    private String compactRootCallPrefix(Expression root, MethodCallExpr expression) {
        return compactSource.compact(root)
            + "."
            + expression.getTypeArguments()
                    .map(typeArguments -> "<" + types.compactJoinTypeLike(typeArguments) + ">")
                    .orElse("")
            + expression.getNameAsString();
    }

    /**
     * Breaks chains that alternate method calls and field accesses as one structural chain. Delegates to
     * {@link MixedFieldMethodCallChainLayout}, which owns the mixed method/field chain walk.
     */
    Optional<Doc> mixedFieldMethodCallChain(MethodCallExpr expression) {
        return mixedFieldChains.mixedFieldMethodCallChain(expression);
    }

    private Doc chainContinuation(Doc doc) {
        return Doc.indent(Doc.indent(Doc.concat(Doc.HARD_LINE, doc)));
    }

    private Doc chainContinuation(Expression root, List<Doc> segments) {
        Optional<String> compactRoot = compactSingleLineRoot(root);
        if (compactRoot.filter(rootText -> rootText.length() < options.indentUnit().length()).isPresent()) {
            String padding = " ".repeat(Math.max(0, compactRoot.orElseThrow().length() - 1));
            return Doc.indent(
                Doc.concat(
                    Doc.HARD_LINE,
                    Doc.join(
                        Doc.HARD_LINE,
                        segments.stream().map(segment -> linePadded(segment, padding)).toList()
                    )
                )
            );
        }
        return chainContinuation(Doc.join(Doc.HARD_LINE, segments));
    }

    private Doc linePadded(Doc doc, String padding) {
        if (padding.isEmpty()) {
            return doc;
        }
        return linePadded(doc, padding, true).doc();
    }

    private PaddedDoc linePadded(Doc doc, String padding, boolean lineStart) {
        return switch (doc) {
            case Doc.Text ignored -> new PaddedDoc(lineStart ? Doc.concat(Doc.text(padding), doc) : doc, false);
            case Doc.Concat concat -> {
                List<Doc> children = new ArrayList<>();
                boolean nextLineStart = lineStart;
                for (Doc child : concat.docs()) {
                    PaddedDoc padded = linePadded(child, padding, nextLineStart);
                    children.add(padded.doc());
                    nextLineStart = padded.lineStart();
                }
                yield new PaddedDoc(Doc.concat(children), nextLineStart);
            }
            // A fill threads continuation padding through its parts exactly like a concat, preserving the alternating
            // content/separator structure so its own greedy packing still applies after re-padding.
            case Doc.Fill fill -> {
                List<Doc> parts = new ArrayList<>();
                boolean nextLineStart = lineStart;
                for (Doc part : fill.parts()) {
                    PaddedDoc padded = linePadded(part, padding, nextLineStart);
                    parts.add(padded.doc());
                    nextLineStart = padded.lineStart();
                }
                yield new PaddedDoc(Doc.fill(parts), nextLineStart);
            }
            // A conditional group's alternatives are mutually exclusive layouts; only one renders, so each is padded from
            // the same incoming line-start rather than threaded in sequence. Like IfBreak, the choice is deferred to the
            // renderer, so the result conservatively reports lineStart=false for the token that follows the group.
            case Doc.ConditionalGroup conditionalGroup -> {
                List<Doc> alternatives = new ArrayList<>();
                for (Doc alternative : conditionalGroup.alternatives()) {
                    alternatives.add(linePadded(alternative, padding, lineStart).doc());
                }
                yield new PaddedDoc(Doc.conditionalGroup(alternatives), false);
            }
            // A best-fitting node's alternatives are mutually exclusive layouts too; only the rank-winner renders, so
            // each is padded from the same incoming line-start rather than threaded in sequence, and the token that
            // follows conservatively reports lineStart=false because which alternative rendered is a renderer decision.
            case Doc.BestFitting bestFitting -> {
                List<Doc> alternatives = new ArrayList<>();
                for (Doc alternative : bestFitting.alternatives()) {
                    alternatives.add(linePadded(alternative, padding, lineStart).doc());
                }
                yield new PaddedDoc(Doc.bestFitting(alternatives), false);
            }
            case Doc.Line ignored -> new PaddedDoc(
                Doc.concat(Doc.LINE, Doc.ifBreak(Doc.text(padding), Doc.EMPTY)),
                false
            );
            case Doc.SoftLine ignored -> new PaddedDoc(
                Doc.concat(Doc.SOFT_LINE, Doc.breakOnly(Doc.text(padding))),
                false
            );
            case Doc.HardLine ignored -> new PaddedDoc(Doc.concat(Doc.HARD_LINE, Doc.text(padding)), false);
            case Doc.Indent indented -> {
                PaddedDoc padded = linePadded(indented.doc(), padding, lineStart);
                yield new PaddedDoc(Doc.indent(padded.doc()), padded.lineStart());
            }
            case Doc.Group group -> {
                PaddedDoc padded = linePadded(group.doc(), padding, lineStart);
                // Preserve any group identity through re-padding so a dependent IfBreak still resolves this group.
                yield new PaddedDoc(Doc.group(padded.doc(), group.groupId()), padded.lineStart());
            }
            case Doc.IfBreak conditional -> new PaddedDoc(
                Doc.ifBreak(
                    linePadded(conditional.breakDoc(), padding, lineStart).doc(),
                    linePadded(conditional.flatDoc(), padding, lineStart).doc(),
                    conditional.groupId()
                ),
                false
            );
            case Doc.Label label -> {
                PaddedDoc padded = linePadded(label.doc(), padding, lineStart);
                yield new PaddedDoc(Doc.label(label.label(), padded.doc()), padded.lineStart());
            }
            // A line suffix renders nothing at its position and flushes at the line break, so it neither consumes the
            // line-start padding slot nor needs continuation padding inside its deferred content.
            case Doc.LineSuffix lineSuffix -> new PaddedDoc(lineSuffix, lineStart);
            // A break-parent marker renders nothing and only influences the enclosing group's fit, so it passes
            // through untouched and leaves the line-start padding slot for the next visible token.
            case Doc.BreakParent ignored -> new PaddedDoc(doc, lineStart);
        };
    }

    private Optional<String> compactSingleLineRoot(Expression root) {
        if (!root.getAllContainedComments().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(compactSource.compact(root));
    }

    private record PaddedDoc(Doc doc, boolean lineStart) {}

    private Doc softChainContinuation(Doc doc) {
        return Doc.indent(Doc.indent(Doc.concat(Doc.SOFT_LINE, doc)));
    }

    Optional<Expression> mixedFieldMethodCallRoot(MethodCallExpr expression) {
        return mixedFieldChains.mixedFieldMethodCallRoot(expression);
    }

    boolean methodCallChainHasComments(MethodCallExpr expression) {
        return methodCallChainAnalysis(expression).hasComments();
    }

    boolean methodCallChainHasFinalTrailingLineComment(MethodCallExpr expression) {
        MethodCallChainSourcePlanner.MethodCallChainAnalysis analysis = methodCallChainAnalysis(expression);
        MethodCallExpr finalCall = analysis.calls().isEmpty() ? expression : analysis.calls().getLast();
        return !finalTrailingLineComments(finalCall).isEmpty();
    }

    /**
     * Exposes the planner's source-line decision to statement routing so field-root fluent chains that were already
     * multiline are not compacted into an ordinary broken final call.
     */
    boolean methodCallChainIsSourceMultiline(MethodCallExpr expression) {
        MethodCallChainSourcePlanner.MethodCallChainAnalysis analysis = methodCallChainAnalysis(expression);
        SourceMultilineLambdaChainPlan sourceMultilineLambdaPlan = sourceMultilineLambdaChainPlan(analysis);
        return (
            analysis.sourceMultilineChain()
            // A chain carrying an inter-segment `//` line comment is reported source-multiline here so the caller stays
            // on its comment-aware chain-routing (MethodCallPrinter's source-multiline branch) rather than the plain
            // method-call render that would drop the comment (the MirrorMaker `encode(x) // note`⏎`.replaceAll(...)`
            // shape). Keyed on comment presence, not the author's line breaks (see {@link #chainHasInterSegmentLineComment}).
            // This covers the leading/trailing/gap inter-segment `//` positions; an ORPHAN `//` floated between blank lines
            // inside the chain (JavaParser parks it on an inner-selector MethodCallExpr, not as a segment comment) is NOT
            // covered here and remains a known comment-placement gap — the `method-chain-member-access @ expanded`
            // perturbation.
            || analysis.hasInterSegmentLineComment()
            || chainHasSourceMultilineArguments(analysis, sourceMultilineLambdaPlan)
            // An object-creation-rooted chain is not reported source-multiline on account of its constructor argument
            // shape: the constructor root's argument list breaks by width (`widthDrivenObjectCreation` /
            // `promotedObjectCreationRootDoc`), so this router keys on the method-call-root read and the chain-arg /
            // comment signals.
        );
    }

    MethodCallChainSourcePlanner.InitializerChainShape methodCallChainInitializerShape(MethodCallExpr expression) {
        return methodChainPlanner.initializerShape(methodCallChainAnalysis(expression));
    }

    private MethodCallChainSourcePlanner.MethodCallChainAnalysis methodCallChainAnalysis(MethodCallExpr expression) {
        return methodChainPlanner.analyze(
            expression,
            this::methodCallSegmentHasComment,
            this::methodCallSegmentHasNameComment,
            this::methodCallSegmentHasArgumentGapComment,
            this::methodCallSegmentHasBlockLambdaArgument,
            this::methodCallChainHasTrailingLineComments,
            this::rootHasTrailingLineCommentBeforeFirstSegment,
            this::chainHasInterSegmentLineComment
        );
    }

    boolean methodCallChainRootIsObjectCreation(MethodCallExpr expression) {
        return methodChainPlanner.rootIsObjectCreation(expression);
    }

    boolean methodCallChainRootIsFieldAccess(MethodCallExpr expression) {
        return methodChainPlanner.rootIsFieldAccess(expression);
    }

    String methodCallChainFirstLine(MethodCallExpr expression) {
        MethodCallChainSourcePlanner.MethodCallChainAnalysis analysis = methodCallChainAnalysis(expression);
        if (analysis.root() instanceof MethodCallExpr methodRoot && analysis.calls().size() == 1) {
            Optional<String> rootFirstLine = methodCallRootFirstLine(methodRoot);
            if (rootFirstLine.isPresent()) {
                return rootFirstLine.orElseThrow();
            }
        }
        if (analysis.root() instanceof MethodCallExpr && analysis.calls().size() == 1) {
            return compactSource.compact(expression);
        }
        MethodCallChainSourcePlanner.MethodCallChainPlan plan = methodChainPlanner.plan(analysis, true);
        if (canAttachFirstSegmentToSimpleRoot(expression, plan, plan.calls(), analysis)) {
            return firstSegmentAttachedToSimpleRootFirstLine(plan.root(), plan.calls().getFirst());
        }
        if (plan.root() instanceof MethodCallExpr methodRoot) {
            Optional<String> rootFirstLine = methodCallRootFirstLine(methodRoot);
            if (rootFirstLine.isPresent()) {
                return rootFirstLine.orElseThrow();
            }
        }
        if (plan.rootRendering() == MethodCallChainSourcePlanner.ChainRootRendering.BROKEN_OBJECT_CREATION) {
            return objectCreationPrefix.apply((ObjectCreationExpr) plan.root()) + "(";
        }
        return compactSource.compact(plan.root());
    }

    private Optional<String> methodCallRootFirstLine(MethodCallExpr methodRoot) {
        String prefix = calls.methodCallPrefix(methodRoot);
        if (hasSingleExpressionLambdaArgument(methodRoot)) {
            return Optional.of(prefix + "(");
        }
        if (promotedRootArgumentsShouldBreak(methodRoot, lineWidth(LayoutWidth.LineBudget.CURRENT), LayoutContext.root())) {
            return Optional.of(prefix + "(");
        }
        if (!sourceShapePolicy.fitsOnOneLine(methodRoot, lineWidth(LayoutWidth.LineBudget.CURRENT))) {
            return Optional.of(prefix + "(");
        }
        if (methodCallSegmentHasBlockLambdaArgument(methodRoot)) {
            return huggableBlockLambdaFirstLine.apply(prefix, methodRoot.getArguments());
        }
        return Optional.empty();
    }

    /**
     * Breaks an expression-lambda-argument method-call root's argument list and glues the single final segment to its
     * close, the expression-lambda sibling of {@link #brokenRootWithAttachedFinalSegment} on the statement/field
     * single-segment chain path.
     *
     * <p>{@code layout} is threaded (#190) so the true continuation column ({@link LayoutContext#leftEdgePrefix()}) is
     * available at this flat-gate. It is NOT consulted here: the opener-fit decision uses the fixed-budget
     * {@code layoutWidth.line(lineBudget, …)} floor (the statement/field callers pass an empty-prefix context).
     */
    private Optional<Doc> expressionLambdaRootWithSingleSegment(
            MethodCallExpr methodRoot,
            MethodCallExpr call,
            MethodCallChainTail finalSegmentSuffix,
            LayoutWidth.LineBudget lineBudget,
            LayoutContext layout
    ) {
        if (
            !hasSingleExpressionLambdaArgument(methodRoot)
            || !methodRoot.getAllContainedComments().isEmpty()
            || !methodCallSegmentHasNoOwnContainedComments(call)
            || methodCallSegmentHasComment(call)
            || methodCallSegmentHasBlockLambdaArgument(call)
        ) {
            return Optional.empty();
        }
        String prefix = calls.methodCallPrefix(methodRoot);
        if (layoutWidth.line(lineBudget, prefix + "(") > options.lineWidth()) {
            return Optional.empty();
        }
        return Optional.of(
            Doc.concat(
                Doc.text(prefix + "("),
                Doc.indent(Doc.concat(Doc.HARD_LINE, expressionRenderer.format(methodRoot.getArgument(0), LayoutContext.root()))),
                Doc.HARD_LINE,
                Doc.text(")"),
                methodCallChainSegmentAttachedToRootClose(call, finalSegmentSuffix, lineBudget)
            )
        );
    }

    private boolean hasSingleExpressionLambdaArgument(MethodCallExpr expression) {
        return hasSingleExpressionLambdaArgumentAnyShape(expression);
    }

    private boolean hasSingleExpressionLambdaArgumentAnyShape(MethodCallExpr expression) {
        return expression.getArguments().size() == 1
            && expression.getArgument(0) instanceof LambdaExpr lambdaExpr
            && lambdaExpr.getExpressionBody().isPresent()
            && lambdaExpr.getAllContainedComments().isEmpty();
    }

    private boolean methodCallSegmentHasComment(MethodCallExpr expression) {
        return methodCallSegmentHasNameComment(expression)
            || methodCallSegmentHasLeadingLineComment(expression)
            || methodCallSegmentHasArgumentGapComment(expression);
    }

    private boolean methodCallSegmentHasLeadingLineComment(MethodCallExpr expression) {
        return !leadingLineCommentsBeforeSegment(expression).isEmpty();
    }

    private boolean methodCallSegmentHasArgumentGapComment(MethodCallExpr expression) {
        return commentedExpressionLists.hasUnprintedLineComments(expression, expression.getArguments());
    }

    private boolean methodCallSegmentHasLineComments(MethodCallExpr expression) {
        return commentedExpressionLists.hasLineComments(expression, expression.getArguments());
    }

    /**
     * Reports whether the only selector of a method-call-rooted chain carries a block comment parked in the gap between
     * the root and the selector, for example {@code create() /* doc *}{@code / .seal()}.
     *
     * <p>JavaParser attaches such a gap block comment to the selector's name (see {@code methodCallSegmentPrefix}), so the
     * stay-flat gate's contained-comment scan on the root misses it and the chain reaches the single-segment branch. This
     * predicate lets that branch break the segment onto its own continuation line, where the segment prefix re-emits the
     * comment with its source space, instead of gluing it flat and dropping the space. It deliberately accepts only a
     * block (or Javadoc) comment that starts after the root ends and before the selector name so an ordinary leading
     * comment already handled elsewhere, or a comment that belongs to the root, is not re-claimed here.
     */
    private boolean methodCallSegmentHasLeadingGapBlockComment(Expression root, MethodCallExpr segment) {
        return segment.getName()
                .getComment()
                .filter(comment -> comment instanceof BlockComment || comment instanceof JavadocComment)
                .filter(comment -> CommentIndex.startsBefore(comment, segment.getName()))
                .filter(comment -> root.getRange()
                            .flatMap(rootRange -> comment.getRange()
                                        .map(commentRange -> commentRange.begin.isAfter(rootRange.end))
                            )
                            .orElse(false)
                )
                .isPresent();
    }

    private boolean methodCallSegmentHasNameComment(MethodCallExpr expression) {
        return expression.getName()
                .getComment()
                .filter(comment -> CommentIndex.startsBefore(comment, expression.getName()))
                .isPresent();
    }

    private boolean methodCallChainHasTrailingLineComments(List<MethodCallExpr> calls) {
        for (int index = 0; index + 1 < calls.size(); index++) {
            if (!trailingLineCommentsBeforeNextSegment(calls.get(index), calls.get(index + 1)).isEmpty()) {
                return true;
            }
        }
        return !calls.isEmpty() && !finalTrailingLineComments(calls.getLast()).isEmpty();
    }

    /**
     * Reports whether a chain carries an inter-segment {@code //} <em>line</em> comment — the comment class whose only
     * safe render keeps the chain fanned one selector per line. Callers use this to route such a chain off the stay-flat
     * path and onto the comment-preserving fan, so the comment is not dropped.
     *
     * <p>It covers the three inter-segment positions a {@code //} comment can occupy:
     * <ul>
     *   <li><b>root → first selector</b> — a line comment the author parked after the root and before the first selector,
     *       whether owned by the root as its trailing comment / root-to-first-selector-gap
     *       ({@link #rootHasTrailingLineCommentBeforeFirstSegment}) or attached as the first selector's leading comment
     *       ({@link #leadingLineCommentsBeforeSegment});</li>
     *   <li><b>dot-gap</b> — a line comment leading a later selector on its own continuation line, e.g. {@code .a()}⏎
     *       {@code // note}⏎{@code .b()} ({@link #leadingLineCommentsBeforeSegment} on each call);</li>
     *   <li><b>between selectors</b> — a trailing line comment in the gap after one selector and before the next, e.g.
     *       {@code .a() // note}⏎{@code .b()} ({@link #trailingLineCommentsBeforeNextSegment}).</li>
     * </ul>
     *
     * <p><strong>Line comments only.</strong> A {@code //} comment runs to end-of-line, so it forces the next selector
     * onto a later line and the chain cannot stay flat. Block comments ({@code create() /* doc *}{@code / .seal()}) are
     * deliberately excluded because they can sit inline without a line break — the chain can stay flat — so folding them
     * in would fan a chain that need not fan. This predicate consults only the same line-comment candidate sets the
     * imperative comment-preserving render consumes; it claims no comment, so placement stays owned by the render.
     */
    private boolean chainHasInterSegmentLineComment(Expression root, List<MethodCallExpr> calls) {
        if (calls.isEmpty()) {
            return false;
        }
        // root -> first selector: root-owned trailing / gap line comment, or the first selector's own leading comment.
        if (rootHasTrailingLineCommentBeforeFirstSegment(root, calls)
            || methodCallSegmentHasLeadingLineComment(calls.getFirst())) {
            return true;
        }
        for (int index = 0; index < calls.size(); index++) {
            // dot-gap: a line comment leading a later selector on its own continuation line.
            if (index > 0 && methodCallSegmentHasLeadingLineComment(calls.get(index))) {
                return true;
            }
            // between selectors: a trailing line comment after this selector and before the next.
            if (index + 1 < calls.size()
                && !trailingLineCommentsBeforeNextSegment(calls.get(index), calls.get(index + 1)).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private boolean methodCallSegmentHasBlockLambdaArgument(MethodCallExpr expression) {
        return expression.getArguments()
                .stream()
                .anyMatch(argument -> argument instanceof LambdaExpr lambdaExpr
                        && lambdaExpr.getBody().isBlockStmt()
                );
    }

    // A block-lambda argument body is never kept broken by its source shape, so this is constant false. Retained so its
    // several callers (the chain-fan guards) stay wired.
    private boolean methodCallSegmentHasSourceMultilineBlockLambdaArgument(MethodCallExpr expression) {
        return false;
    }

    private boolean canBreakAfterCompactExpressionLambdaRoot(
            MethodCallBreakMode breakMode,
            Expression root,
            List<MethodCallExpr> calls,
            SourceMultilineLambdaChainPlan sourceMultilineLambdaPlan,
            LayoutContext layout
    ) {
        if (
            !breakMode.isForced()
            || calls.size() != 1
            || !(root instanceof MethodCallExpr methodRoot)
            || !methodCallSegmentHasExpressionLambdaArgument(methodRoot)
            || sourceMultilineLambdaPlan.rootCanAttachExpressionLambdaBody()
            || sourceMultilineLambdaPlan.anyNonFinalCallCanAttachExpressionLambdaBody()
            || !methodCallSegmentHasNoOwnContainedComments(calls.getFirst())
            || methodCallSegmentHasComment(calls.getFirst())
        ) {
            return false;
        }
        return rootLineWidth(root, compactSource.compact(root), layout) <= options.lineWidth();
    }

    private boolean methodCallSegmentHasExpressionLambdaArgument(MethodCallExpr expression) {
        return expression.getArguments()
                .stream()
                .anyMatch(argument -> argument instanceof LambdaExpr lambdaExpr
                        && lambdaExpr.getExpressionBody().isPresent()
                );
    }

    /**
     * Measures a chain root's compact form at the column where the root renders, feeding the compact-root break
     * decisions ({@link #canBreakAfterCompactExpressionLambdaRoot}, {@link #promotedRootArgumentsShouldBreak}).
     *
     * <p>Like {@link #compactRootLineWidth} it takes the wider of the source-column reconstruction and the root's
     * rendered indentation ({@link LayoutWidth#nodeIndentWidth}) (#217), so a root reindented shallower than its true
     * depth is not under-measured, while the source-column floor keeps the {@code = }/{@code return }/continuation
     * leading prefix accounted for (dropping it under-measures the initializer/return chains). The wider-of rule can only
     * measure wider, never relax a break.
     *
     * <p>{@code layout} is read here (chain-unify U3, #190): when a caller threads a same-line prefix through
     * {@link LayoutContext#leftEdgePrefix()} the rendered column is known exactly
     * ({@code nodeIndentWidth(root) + leftEdgePrefix.length() + text.length()}) and the source-column floor is dropped,
     * exactly as {@link #compactRootLineWidth} does. Its consumer {@link #promotedRootArgumentsShouldBreak} is reached by
     * the <em>initializer</em> chain carrying a real {@code "NAME = "} prefix, so the arg-break verdict is now measured at
     * that chain's true rendered column rather than the value's stale source column — byte-identical on already-formatted
     * input, a determinism hardening for reindented input. Callers with no prefix ({@code root()}) keep the wider-of
     * source-column floor, which still stands in for their unmodelled leading prefix.
     */
    private int rootLineWidth(Expression root, String text, LayoutContext layout) {
        // With the same-line prefix threaded, measure at the exact rendered column and drop the source-column floor,
        // which is only ever a stand-in for this prefix (mirrors compactRootLineWidth's prefix-set arm).
        if (!layout.leftEdgePrefix().isEmpty()) {
            return layoutWidth.nodeIndentWidth(root) + layout.leftEdgePrefix().length() + text.length();
        }
        return root.getRange()
                .map(range -> Math.max(
                    Math.max(0, range.begin.column - 1) + text.length(),
                    layoutWidth.nodeIndentWidth(root) + text.length()))
                .orElseGet(() -> layoutWidth.line(LayoutWidth.LineBudget.CURRENT, text));
    }

    private Doc inlineMethodCall(MethodCallExpr expression) {
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
            comments.speculatively(() -> calls.sourceMultilineArguments(expression));
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
                                chainContinuation(methodCallChainSegment(expression))
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
            chainContinuation(
                Doc.text("." + scope.getNameAsString() + "." + typeArguments + expression.getNameAsString() + "()")
            )
        );
    }

    private boolean promotedNoArgRootScopeOverflows(
            MethodCallExpr expression,
            ToIntFunction<String> firstLineWidth
    ) {
        return expression.getArguments().isEmpty()
            && expression.getScope().filter(FieldAccessExpr.class::isInstance).isPresent()
            && !sourceShapePolicy.fitsOnOneLine(expression, firstLineWidth);
    }

    private boolean promotedRootArgumentsShouldBreak(
            MethodCallExpr expression,
            ToIntFunction<String> firstLineWidth,
            LayoutContext layout
    ) {
        if (expression.getArguments().size() <= 1) {
            return false;
        }
        String compact = compactSource.compact(expression);
        return layoutWidth.line(LayoutWidth.LineBudget.CURRENT, compact) > options.lineWidth()
            || rootLineWidth(expression, compact, layout) > options.lineWidth();
    }

    private ToIntFunction<String> lineWidth(LayoutWidth.LineBudget lineBudget) {
        return text -> layoutWidth.line(lineBudget, text);
    }

    /**
     * The fixed-budget column oracle handed to the expression-lambda hug/opener seams
     * ({@link ExpressionLambdaArgumentLayout.HuggableExpressionLambdaArguments},
     * {@link ExpressionLambdaArgumentLayout.ExpressionLambdaMethodCallBodyOpener},
     * {@link ExpressionLambdaArgumentLayout.ExpressionLambdaLogicalBinaryBodyOpenerHug}) at every call-site that is NOT a
     * fanned chain selector.
     *
     * <p>This returns the same fixed budget the seams measure at when the call-site is not a fanned selector:
     * {@code layoutWidth.line(BLOCK, indentUnit + text) == layoutWidth.line(CONTINUATION, text)}. A fanned chain selector
     * instead threads its own {@code compactSegmentWidth} (the true segment column) at the segment call-site.
     */
    private ToIntFunction<String> expressionLambdaColumnWidthFallback() {
        return lineWidth(LayoutWidth.LineBudget.CONTINUATION);
    }

    /**
     * The true-column width oracle for a fanned chain selector's expression-lambda hug: the selector's rendered
     * continuation column ({@link LayoutWidth#nodeIndentWidth} — the enclosing type/block indentation — plus the two
     * continuation units the fan applies, the same {@code nodeIndentWidth(chain) + indentUnit * 2} column
     * {@link ExpressionLambdaArgumentLayout} measures the hugged body at), widened with {@code Math.max} against the
     * fixed budget the caller already threads so it is monotone (it can only ever measure the hug WIDER, never relax a
     * break, so it cannot introduce a new over-width and stays a pure function of the AST). This corrects the fixed
     * {@link LayoutWidth.LineBudget#CONTINUATION} budget's one-level under-count for a chain nested below a top-level
     * statement, so the lambda-hug admission gate sees the selector's real overflow. It still under-counts a selector
     * nested several argument levels deep — the general case needs the {@code leftEdgePrefix} column threaded through the
     * fan — but is never worse than the budget it replaces.
     */
    private ToIntFunction<String> fannedSelectorColumnWidth(MethodCallExpr expression, ToIntFunction<String> fallback) {
        int continuationColumn = layoutWidth.nodeIndentWidth(expression) + options.indentUnit().length() * 2;
        return text -> Math.max(fallback.applyAsInt(text), continuationColumn + text.length());
    }


    private Doc brokenPromotedMethodCallRoot(MethodCallExpr expression) {
        String prefix = calls.methodCallPrefix(expression);
        // When the promoted root's argument list carries unclaimed gap comments (e.g. trailing notes on each argument of
        // Stream.concat(...) whose receiver sits on its own line under expand), route through the comment-aware
        // argument-list renderer so those comments survive. parenthesized() returns empty when there are no such
        // comments, so the comment-free path below stays byte-identical.
        Optional<Doc> commentedArguments = comments.speculatively(
            () -> commentedExpressionLists.parenthesized(prefix, expression, expression.getArguments())
        );
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

    private Doc methodCallChainSegment(MethodCallExpr expression) {
        return methodCallChainSegment(expression, false);
    }

    private Doc methodCallChainSegment(MethodCallExpr expression, MethodCallChainTail finalSegmentSuffix) {
        return methodCallChainSegment(expression, Optional.empty(), finalSegmentSuffix);
    }

    private Doc methodCallChainSegment(MethodCallExpr expression, boolean reserveStatementTerminator) {
        return methodCallChainSegment(expression, reserveStatementTerminator, lineWidth(LayoutWidth.LineBudget.CONTINUATION));
    }

    private Doc methodCallChainSegmentAttachedToRootClose(
            MethodCallExpr expression,
            MethodCallChainTail finalSegmentSuffix
    ) {
        return methodCallChainSegmentAttachedToRootClose(
            expression,
            finalSegmentSuffix,
            LayoutWidth.LineBudget.CURRENT
        );
    }

    private Doc methodCallChainSegmentAttachedToRootClose(
            MethodCallExpr expression,
            MethodCallChainTail finalSegmentSuffix,
            LayoutWidth.LineBudget lineBudget
    ) {
        return methodCallChainSegment(
            expression,
            Optional.empty(),
            finalSegmentSuffix,
            segment -> layoutWidth.line(lineBudget, ")" + segment)
        );
    }

    private ToIntFunction<String> objectRootSegmentWidth(
            ObjectCreationExpr root,
            MethodCallChainSourcePlanner.ChainRootRendering rootRendering,
            LayoutWidth.LineBudget lineBudget,
            ToIntFunction<String> firstLineWidth
    ) {
        if (!objectRootUsesCompactLine(root, rootRendering)) {
            return segment -> layoutWidth.line(lineBudget, ")" + segment);
        }
        String rootText = compactSource.compact(root);
        return segment -> firstLineWidth.applyAsInt(rootText + segment);
    }

    private boolean objectRootUsesCompactLine(
            ObjectCreationExpr root,
            MethodCallChainSourcePlanner.ChainRootRendering rootRendering
    ) {
        // Whether the constructor root keeps its close on the compact segment line is decided entirely by the planner's
        // width-driven `rootRendering` (via `canKeepCompactChainRoot` — a wide root becomes `BROKEN_OBJECT_CREATION`), so
        // a source-multiline-but-fitting constructor root uses the same compact line on every pass.
        return rootRendering != MethodCallChainSourcePlanner.ChainRootRendering.BROKEN_OBJECT_CREATION;
    }

    private Optional<Doc> compactAttachedObjectRootSingleSegment(
            Doc rootDoc,
            MethodCallExpr expression,
            MethodCallChainTail finalSegmentSuffix,
            ToIntFunction<String> compactSegmentWidth,
            boolean sourceMultilineChain
    ) {
        if (
            !expression.getAllContainedComments().isEmpty()
            || (sourceMultilineChain && !finalSegmentSuffix.isEmpty())
            || methodCallSegmentHasBlockLambdaArgument(expression)
            || expression.getArguments().stream().anyMatch(LambdaExpr.class::isInstance)
        ) {
            return Optional.empty();
        }
        String typeArguments = expression.getTypeArguments()
                .map(arguments -> "<" + types.compactJoinTypeLike(arguments) + ">")
                .orElse("");
        String segment = "."
            + typeArguments
            + expression.getNameAsString()
            + "("
            + compactSource.compactJoin(expression.getArguments())
            + ")"
            + finalSegmentSuffix;
        if (compactSegmentWidth.applyAsInt(segment) > options.lineWidth()) {
            return Optional.empty();
        }
        return Optional.of(Doc.concat(rootDoc, Doc.text(segment)));
    }

    private Doc objectRootSingleSegmentChain(
            ObjectCreationExpr objectCreation,
            Doc rootDoc,
            MethodCallExpr call,
            MethodCallChainTail finalSegmentSuffix,
            MethodCallChainSourcePlanner.ChainRootRendering rootRendering,
            boolean sourceMultilineChain,
            LayoutWidth.LineBudget lineBudget,
            ToIntFunction<String> firstLineWidth,
            LayoutContext layout
    ) {
        if (sourceMultilineChain && methodCallSegmentHasLeadingLineComment(call)) {
            return Doc.concat(
                brokenObjectCreationRenderer.apply(objectCreation),
                chainContinuation(methodCallChainSegment(call, Optional.empty(), finalSegmentSuffix))
            );
        }
        if (sourceMultilineChain && !finalTrailingLineComments(call).isEmpty()) {
            return Doc.concat(
                rootDoc,
                chainContinuation(methodCallChainSegment(call, Optional.empty(), finalSegmentSuffix))
            );
        }
        ToIntFunction<String> compactSegmentWidth = objectRootSegmentWidth(
            objectCreation,
            rootRendering,
            lineBudget,
            firstLineWidth
        );
        if (
            compactSegmentWidth.applyAsInt(compactMethodCallChainSegment(call) + finalSegmentSuffix.text())
                > options.lineWidth()
        ) {
            // The compact chain (constructor plus the attached selector on one line) overflows, so the selector fans onto
            // its own continuation line. When that selector is a call whose argument list is exactly one simple argument
            // (a name, field access, this/super, or literal — no lambda, no nested call, no multiple arguments), opening
            // that single argument (constructor \n .selector( \n arg \n )) is gratuitous: on its own continuation line the
            // whole {@code .selector(arg)} routinely fits well within budget. Render such a tail compact on its dotted line
            // through the ordinary segment renderer, whose group keeps {@code .selector(arg)} flat when it fits at the
            // continuation column and still breaks the argument only if it genuinely overruns. Multi-argument, lambda, and
            // already-broken selectors are excluded by singleSimpleMethodCallSegmentArgument, so they keep the existing
            // argument-opening fan-out.
            //
            // PR #279 review (#1): this dot-break — introduced for the return chain (LDM-2f #190, revising #236) behind a
            // non-empty leftEdgePrefix gate — is now applied to every caller. A statement chain
            // ({@code new ProfileRequest(...).submit(10);}) reaches this branch once
            // {@link #refuseOpeningSingleSimpleObjectRootChainTail} declines the arg-opening compact shape, and wants the
            // same {@code new ProfileRequest(...)}⏎{@code .submit(10)} shape, not the arg-opened
            // {@code .submit(}⏎{@code 10}⏎{@code )}. The choice is a pure function of the AST (single simple argument) and
            // the width probe above, so it is a fixpoint regardless of the leading prefix.
            if (singleSimpleMethodCallSegmentArgument(call)) {
                return Doc.concat(
                    rootDoc,
                    objectRootContinuation(methodCallChainSegment(call, Optional.empty(), finalSegmentSuffix))
                );
            }
            // PR #279 review (#7): the tail selector fans onto its own continuation line, so measure it THERE
            // ({@code segmentOnOwnLine == true}, continuation budget) and let its own argument group open only when
            // {@code .selector(args)} genuinely overruns that column — instead of force-breaking it one-argument-per-line
            // via {@code brokenMethodCallChainSegment}. The compact-overflow probe above measures the whole compact chain
            // (constructor plus attached selector), which overflows whenever the constructor root will itself break onto
            // its own lines; but once it does, the selector lands at the shallow post-{@code )} column where
            // {@code .findSessions(principal.groupId(), Source.REMOTE, principal, null)} fits flat. Measuring the fanned
            // segment at its continuation column keeps that fitting tail on one line, matching the multi-selector fan
            // ({@link #methodCallChainSegments}) and the assignment-position attached tail.
            return Doc.concat(
                rootDoc,
                objectRootContinuation(methodCallChainSegment(
                    call,
                    Optional.empty(),
                    finalSegmentSuffix,
                    lineWidth(LayoutWidth.LineBudget.CONTINUATION),
                    true
                ))
            );
        }
        Optional<Doc> compactAttachedSegment = comments.speculatively(
            () -> compactAttachedObjectRootSingleSegment(
                rootDoc,
                call,
                finalSegmentSuffix,
                compactSegmentWidth,
                sourceMultilineChain
            )
        );
        if (compactAttachedSegment.isPresent()) {
            return compactAttachedSegment.orElseThrow();
        }
        Doc attachedSegment = methodCallChainSegment(
            call,
            Optional.empty(),
            finalSegmentSuffix,
            compactSegmentWidth
        );
        if (
            objectRootUsesCompactLine(objectCreation, rootRendering)
            && call.getArguments().isEmpty()
            && compactSegmentWidth.applyAsInt(
                compactMethodCallChainSegment(call) + finalSegmentSuffix.text()
            ) > options.lineWidth()
        ) {
            return Doc.concat(rootDoc, chainContinuation(attachedSegment));
        }
        return Doc.concat(rootDoc, attachedSegment);
    }

    private Doc objectRootContinuation(Doc doc) {
        return Doc.indent(Doc.concat(Doc.HARD_LINE, doc));
    }

    private Doc brokenMethodCallChainSegment(
            MethodCallExpr expression,
            MethodCallChainTail finalSegmentSuffix
    ) {
        String typeArguments = expression.getTypeArguments()
                .map(arguments -> "<" + types.compactJoinTypeLike(arguments) + ">")
                .orElse("");
        return brokenMethodCallSegment(
            expression,
            "." + typeArguments + expression.getNameAsString(),
            Doc.EMPTY,
            finalSegmentSuffix
        );
    }

    private Doc methodCallChainSegment(
            MethodCallExpr expression,
            boolean reserveStatementTerminator,
            ToIntFunction<String> compactSegmentWidth
    ) {
        return methodCallChainSegment(
            expression,
            reserveStatementTerminator,
            compactSegmentWidth,
            MethodCallChainTail.EMPTY,
            false
        );
    }

    private Doc methodCallChainSegment(
            MethodCallExpr expression,
            boolean reserveStatementTerminator,
            ToIntFunction<String> compactSegmentWidth,
            MethodCallChainTail finalSegmentSuffix,
            boolean segmentOnOwnLine
    ) {
        String typeArguments = expression.getTypeArguments()
                .map(arguments -> "<" + types.compactJoinTypeLike(arguments) + ">")
                .orElse("");
        String prefix = "." + typeArguments + expression.getNameAsString();
        Doc segmentPrefix = methodCallSegmentPrefix(expression);
        if (expression.getArguments().isEmpty()) {
            Optional<Doc> commentedArguments =
                comments.speculatively(() -> calls.emptyMethodCallArguments(prefix, expression));
            if (commentedArguments.isPresent()) {
                return Doc.concat(segmentPrefix, commentedArguments.orElseThrow(), finalSegmentSuffix.doc());
            }
            return Doc.concat(segmentPrefix, Doc.text(prefix + "()" + finalSegmentSuffix));
        }
        Optional<Doc> sourceMultilineArguments = comments.speculatively(
            () -> sourceMultilineMethodCallSegmentArguments(prefix, expression, finalSegmentSuffix)
        );
        if (sourceMultilineArguments.isPresent()) {
            return Doc.concat(segmentPrefix, sourceMultilineArguments.orElseThrow());
        }
        Optional<Doc> huggableLambda =
            comments.speculatively(() -> huggableBlockLambdaArguments.apply(prefix, expression.getArguments()));
        if (huggableLambda.isPresent()) {
            return Doc.concat(segmentPrefix, huggableLambda.orElseThrow(), finalSegmentSuffix.doc());
        }
        Optional<Doc> commentedExpressionLambda =
            comments.speculatively(() -> commentedExpressionLambdaArgument.apply(prefix, expression));
        if (commentedExpressionLambda.isPresent()) {
            return Doc.concat(segmentPrefix, commentedExpressionLambda.orElseThrow(), finalSegmentSuffix.doc());
        }
        Optional<Doc> huggedCommentedExpressionLambda = comments.speculatively(
            () -> chainSelectorLambda.huggedCommentCarryingExpressionLambdaSegment(prefix, expression, finalSegmentSuffix)
        );
        if (huggedCommentedExpressionLambda.isPresent()) {
            return Doc.concat(segmentPrefix, huggedCommentedExpressionLambda.orElseThrow());
        }
        // The chain-SELECTOR expression-lambda position. A chain selector whose sole trailing argument is an expression
        // lambda ({@code .map(entry -> body)}) renders SOURCE-NEUTRALLY here. Reading source shape at this position would
        // re-render the SAME selector two different ways across passes — the generic {@code Doc.group} argument shape when
        // its arguments fit flat, a hug when they span lines — so the segment's rendered width would flip and any enclosing
        // {@code bestFitting}/attach decision with it. Rendering AST-purely instead is what lets expr-lambda-selector
        // chains fan without a withhold. {@link #sourceNeutralExpressionLambdaSegment} chooses between two pure-AST arms
        // (flat selector vs. hugged/fanned body) with a {@link Doc#conditionalGroup}, so the DocRenderer picks hug-vs-break
        // at the true live column. Block-lambda and comment-carrying lambdas are handled by the earlier branches (they
        // never reach here), so this only ever sees a clean expression lambda.
        Optional<Doc> sourceNeutralExpressionLambda = comments.speculatively(
            () -> chainSelectorLambda.sourceNeutralExpressionLambdaSegment(
                prefix,
                expression,
                segmentPrefix,
                finalSegmentSuffix,
                segmentOnOwnLine,
                compactSegmentWidth
            )
        );
        if (sourceNeutralExpressionLambda.isPresent()) {
            return sourceNeutralExpressionLambda.orElseThrow();
        }
        Optional<Doc> commentedArguments = comments.speculatively(
            () -> commentedExpressionLists.parenthesized(prefix, expression, expression.getArguments())
        );
        if (commentedArguments.isPresent()) {
            return Doc.concat(segmentPrefix, commentedArguments.orElseThrow(), finalSegmentSuffix.doc());
        }
        String compactSegment = prefix
            + "("
            + methodCallSegmentArgumentsWidthText(expression.getArguments())
            + ")"
            + finalSegmentSuffix;
        if (methodCallSegmentArgumentsShouldBreak(
                expression,
                reserveStatementTerminator,
                compactSegment,
                compactSegmentWidth,
                segmentOnOwnLine
            )) {
            return brokenMethodCallSegment(expression, prefix, segmentPrefix, finalSegmentSuffix);
        }
        return Doc.concat(
            segmentPrefix,
            Doc.group(
                Doc.concat(
                    Doc.text(prefix + "("),
                    Doc.indent(
                        Doc.concat(
                            Doc.SOFT_LINE,
                            calls.methodCallArgumentList(prefix, expression.getArguments(), Doc.LINE)
                        )
                    ),
                    Doc.SOFT_LINE,
                    Doc.text(")" + finalSegmentSuffix)
                )
            )
        );
    }

    private boolean methodCallSegmentArgumentsShouldBreak(
            MethodCallExpr expression,
            boolean reserveStatementTerminator,
            String compactSegment,
            ToIntFunction<String> compactSegmentWidth,
            boolean segmentOnOwnLine
    ) {
        if (
            reserveStatementTerminator
            && !singleSimpleMethodCallSegmentArgument(expression)
            && finalSegmentRenderedWidth(expression, compactSegment, compactSegmentWidth, segmentOnOwnLine)
                > options.lineWidth()
        ) {
            return true;
        }
        return overwideTypeLikeScopeSegment(expression)
            && compactSegmentWidth.applyAsInt(compactSegment) > options.lineWidth();
    }

    /**
     * Measures where the final chain segment's compact form will actually land.
     *
     * <p>A segment that the chain places on its own continuation line is measured purely at that continuation
     * indent ({@code compactSegmentWidth}), because nothing precedes it on the line. The source-column estimate in
     * {@link #methodCallSegmentWidth} only describes a segment kept beside a preceding token on the same line, so
     * applying it to a one-per-line segment overstates the width by the segment's stale source indentation. That
     * over-measurement is what made an already-flat-fitting trailing call (such as {@code .collect(Collectors.toSet())})
     * break apart on the first pass and then collapse on the second, so a standalone segment must ignore the source
     * column to converge in one pass.
     */
    private int finalSegmentRenderedWidth(
            MethodCallExpr expression,
            String compactSegment,
            ToIntFunction<String> compactSegmentWidth,
            boolean segmentOnOwnLine
    ) {
        if (segmentOnOwnLine) {
            return compactSegmentWidth.applyAsInt(compactSegment);
        }
        return methodCallSegmentWidth(expression, compactSegment, compactSegmentWidth);
    }

    private boolean singleSimpleMethodCallSegmentArgument(MethodCallExpr expression) {
        if (expression.getArguments().size() != 1) {
            return false;
        }
        Expression argument = expression.getArgument(0);
        return argument.isNameExpr()
            || argument.isFieldAccessExpr()
            || argument.isThisExpr()
            || argument.isSuperExpr()
            || argument.isLiteralExpr();
    }

    private boolean overwideTypeLikeScopeSegment(MethodCallExpr expression) {
        return expression.getArguments().size() > 1
            && expression.getScope().filter(methodChainPlanner::promotesFirstCall).isPresent();
    }

    private Doc brokenMethodCallSegment(
            MethodCallExpr expression,
            String prefix,
            Doc segmentPrefix,
            MethodCallChainTail finalSegmentSuffix
    ) {
        return Doc.concat(
            segmentPrefix,
            Doc.text(prefix + "("),
            Doc.indent(
                Doc.concat(
                    Doc.HARD_LINE,
                    calls.methodCallArgumentList(prefix, expression.getArguments(), Doc.HARD_LINE)
                )
            ),
            Doc.HARD_LINE,
            Doc.text(")" + finalSegmentSuffix)
        );
    }

    private boolean sourceFirstLineIsOnlyChainRoot(MethodCallExpr expression) {
        List<MethodCallExpr> calls = new ArrayList<>();
        Expression root = methodChainPlanner.methodCallChainRoot(expression, calls);
        if (calls.size() < 2) {
            return false;
        }
        return sourceFirstLineIsOnlyChainRoot(root, expression);
    }

    private boolean sourceFirstLineIsOnlyChainRoot(Expression root, MethodCallExpr expression) {
        return rawSource.rawWithoutOwnComment(expression)
                .strip()
                .lines()
                .findFirst()
                .filter(line -> line.equals(compactSource.compact(root)))
                .isPresent();
    }

    /**
     * A no-op stub: a chain segment's argument list breaks by width rather than being preserved in its authored
     * multi-line shape, so this always returns empty. Retained so the speculative dispatch in
     * {@link #methodCallChainSegment} stays wired.
     */
    private Optional<Doc> sourceMultilineMethodCallSegmentArguments(
            String prefix,
            MethodCallExpr expression,
            MethodCallChainTail finalSegmentSuffix
    ) {
        return Optional.empty();
    }

    private String methodCallSegmentArgumentsWidthText(NodeList<Expression> arguments) {
        return arguments.stream()
                .map(argument -> rawSource.normalizeWhitespace(rawSource.rawWithoutOwnComment(argument)))
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
    }

    /**
     * Estimates a chain segment's width when it is kept beside a preceding token on the same line.
     *
     * <p>C10 (#217): deliberately left source-relative. The reconstruction — the name token's source column minus its
     * offset within the segment — recovers where the whole segment starts <em>beside its preceding token</em> (see
     * {@link #finalSegmentRenderedWidth}), a source-shaped position that depends on what shares the line, not on the
     * segment's own block/type nesting depth. {@link LayoutWidth#nodeIndentWidth} measures only that nesting depth and
     * so cannot express the beside-a-token column, which is why the one-per-line caller already routes around this via
     * {@code segmentOnOwnLine}. The source column remains the faithful estimate for the beside-a-token case; a correct
     * rendered-column migration would need the same leading-offset machinery the root gates await (#190).
     */
    private int methodCallSegmentWidth(
            MethodCallExpr expression,
            String segment,
            ToIntFunction<String> fallbackWidth
    ) {
        return expression.getName()
                .getRange()
                .map(range -> {
                    int nameOffset = segment.indexOf(expression.getNameAsString());
                    if (nameOffset < 0) {
                        return fallbackWidth.applyAsInt(segment);
                    }
                    int leadingColumns = Math.max(0, range.begin.column - 1 - nameOffset);
                    return leadingColumns + segment.length();
                })
                .orElseGet(() -> fallbackWidth.applyAsInt(segment));
    }

    private Doc methodCallSegmentPrefix(MethodCallExpr expression) {
        List<JavaCommentTrivia> leadingComments = leadingLineCommentsBeforeSegment(expression);
        Doc leading = Doc.concat(
            leadingComments
                    .stream()
                    .map(comments::comment)
                    .filter(comment -> comment != Doc.EMPTY)
                    .map(comment -> Doc.concat(comment, Doc.HARD_LINE))
                    .toList()
        );
        // A block or Javadoc comment interspersed between two chain links — e.g. `.define(A)` then `/** doc */` then
        // `.define(B)` — is parked on the B selector depending on layout. Recover it from the orphan pool first (the
        // expanded shape) so a single source-position query owns the slot for every whitespace shape, then fall through
        // to the selector's own comment (the canonical/collapsed shape). Both are claimed under the same anchor by
        // identity, so whichever shape applies, the comment renders exactly once.
        Doc interspersedOrphans = interspersedOrphanCommentsBeforeSelector(expression);
        // JavaParser attaches a line comment that sits between the scope and the selector to the selector name as its own
        // comment, so the same comment can also be offered by a neighboring slot: the leading-line slot above (same prefix
        // call) or the previous segment's between-segments trailing slot. The name comment is offered here under its own
        // (expression, OWN) ownership key — distinct from the bare (comment, INTERLEAVED) key those neighbors use — so the
        // dry-run records the true first-traversal claimant and {@code ownsHere} suppresses whichever offer lost. Output
        // is unchanged because the suppressed offer already lost the first-claim race and rendered empty. The
        // same-prefix leading offer is also excluded by identity here so the name slot never re-claims this segment's own
        // leading comment. A Javadoc selector comment is accepted alongside line and block comments because JavaParser
        // parses a `/** ... */` between chain links as a Javadoc attached to the next selector, and dropping it on
        // kind alone lost it in every shape.
        Optional<Comment> rawNameComment = expression.getName()
                .getComment()
                .filter(comment -> comment instanceof LineComment
                        || comment instanceof BlockComment
                        || comment instanceof JavadocComment)
                .filter(comment -> CommentIndex.startsBefore(comment, expression.getName()))
                .filter(comment -> leadingComments.stream().noneMatch(leadingTrivia -> leadingTrivia.comment() == comment));
        Doc nameComment = rawNameComment
                .map(comment -> comments.comment(comment, expression, OwnerSlot.OWN))
                .orElse(Doc.EMPTY);
        if (nameComment == Doc.EMPTY) {
            return Doc.concat(leading, interspersedOrphans);
        }
        Doc namePrefix = rawNameComment
                .filter(comment -> comment instanceof BlockComment
                        && CommentIndex.startsOnSameLine(comment, expression.getName())
                )
                .map(ignored -> Doc.concat(nameComment, Doc.text(" ")))
                .orElseGet(() -> Doc.concat(nameComment, Doc.HARD_LINE));
        return Doc.concat(leading, interspersedOrphans, namePrefix);
    }

    /**
     * Recovers a block or Javadoc comment that sits between this segment's scope and its selector but that JavaParser
     * parked as an orphan of the call rather than as the selector's own trivia.
     *
     * <p>This is the orphan-bucket sibling of the selector's own-comment slot in {@link #methodCallSegmentPrefix}. At the
     * canonical and collapsed shapes JavaParser attaches a {@code .define(A) /** doc *}{@code / .define(B)} comment to the
     * {@code B} selector, so the own-comment slot renders it; an expanded whitespace shape re-buckets the identical
     * comment onto the enclosing call's orphan pool even though the AST is otherwise unchanged, so the own slot does not
     * hold it and it would be dropped without this recovery. Selecting by source position from the orphan pool — strictly after the scope ends and
     * strictly before the selector begins — keeps the comment owned by this between-links slot whatever the layout.
     *
     * <p>The orphan pool is read directly from the node ({@link Node#getOrphanComments()}) rather than through the
     * comment-placement map, because the assignment/initializer renderers hand the chain printer a {@link Node#clone()
     * clone} of the chain expression (see {@code ExpressionRuleEnvelope.expressionWithoutOwnComment}). A clone carries its
     * orphan comments forward, but the identity-keyed placement map only knows the original parse node, so the map answers
     * empty for the clone; the node's own orphan list is the one association that survives the clone. Line comments are
     * deliberately excluded: they are already recovered by {@link #leadingLineCommentsBeforeSegment} and the
     * between-segments trailing slot. Each comment is offered under {@link OwnerSlot#ORPHAN} and claimed once, so the
     * canonical/collapsed shape — where the comment is the selector's own trivia and not in the orphan pool — is left
     * byte-identical.
     */
    private Doc interspersedOrphanCommentsBeforeSelector(MethodCallExpr expression) {
        Optional<Expression> scope = expression.getScope();
        if (scope.isEmpty() || expression.getOrphanComments().isEmpty()) {
            return Doc.EMPTY;
        }
        // Empty-argument selectors ({@code .util()}, {@code .build()}) are recovered here too. They route their inside-
        // the-parens orphans through {@code MethodCallPrinter.emptyMethodCallArguments}, but that owner now excludes the
        // between-links orphan (the one this slot selects: strictly after the scope ends and before the selector begins),
        // so the two slots partition the call's orphan pool by source position and each orphan is claimed exactly once.
        // Without this recovery the between-links comment before an empty-argument selector is dropped whenever the call
        // reaches the printer as a clone (the assignment/initializer value path), because the clone's orphans survive on
        // the node but not in the placement map the empty-argument owner reads.
        Expression scoped = scope.orElseThrow();
        return Doc.concat(
            expression.getOrphanComments()
                    .stream()
                    .map(JavaCommentTrivia::from)
                    .filter(trivia -> trivia.isBlock() || trivia.isJavadoc())
                    .filter(trivia -> trivia.liesBetween(scoped, expression.getName()))
                    .sorted((left, right) ->
                        CommentIndex.sourceOrderComparator().compare(left.comment(), right.comment()))
                    .map(trivia -> comments.comment(trivia, expression, OwnerSlot.ORPHAN))
                    .filter(comment -> comment != Doc.EMPTY)
                    .map(comment -> Doc.concat(comment, Doc.HARD_LINE))
                    .toList()
        );
    }

    private List<JavaCommentTrivia> leadingLineCommentsBeforeSegment(MethodCallExpr expression) {
        Optional<Expression> scope = expression.getScope();
        if (scope.isEmpty()) {
            return List.of();
        }
        int scopeEndLine = CommentIndex.endLine(scope.orElseThrow(), Integer.MIN_VALUE);
        int nameBeginLine = CommentIndex.beginLine(expression.getName(), Integer.MAX_VALUE);
        return commentPlacement.containedComments(expression)
                .stream()
                .filter(JavaCommentTrivia::isLine)
                .filter(comment -> comment.beginLine(Integer.MIN_VALUE) > scopeEndLine)
                .filter(comment -> comment.beginLine(Integer.MAX_VALUE) < nameBeginLine)
                .sorted((left, right) -> CommentIndex.sourceOrderComparator().compare(left.comment(), right.comment()))
                .toList();
    }

    private List<Doc> methodCallChainSegments(List<MethodCallExpr> calls, MethodCallChainTail finalSegmentSuffix) {
        List<Doc> segments = new ArrayList<>();
        for (int i = 0; i < calls.size(); i++) {
            Optional<MethodCallExpr> next = i + 1 < calls.size() ? Optional.of(calls.get(i + 1)) : Optional.empty();
            // Every segment in this one-per-line layout renders alone on its own continuation line, so the final
            // segment must be measured at the continuation indent rather than its stale source column.
            segments.add(
                methodCallChainSegment(
                    calls.get(i),
                    next,
                    next.isEmpty() ? finalSegmentSuffix : MethodCallChainTail.EMPTY,
                    lineWidth(LayoutWidth.LineBudget.CONTINUATION),
                    true
                )
            );
        }
        return segments;
    }

    private Doc methodCallChainSegment(MethodCallExpr expression, Optional<MethodCallExpr> nextCall) {
        return methodCallChainSegment(expression, nextCall, MethodCallChainTail.EMPTY);
    }

    private Doc methodCallChainSegment(
            MethodCallExpr expression,
            Optional<MethodCallExpr> nextCall,
            MethodCallChainTail finalSegmentSuffix
    ) {
        return methodCallChainSegment(expression, nextCall, finalSegmentSuffix, lineWidth(LayoutWidth.LineBudget.CONTINUATION));
    }

    private Doc methodCallChainSegment(
            MethodCallExpr expression,
            Optional<MethodCallExpr> nextCall,
            MethodCallChainTail finalSegmentSuffix,
            ToIntFunction<String> compactSegmentWidth
    ) {
        return methodCallChainSegment(expression, nextCall, finalSegmentSuffix, compactSegmentWidth, false);
    }

    private Doc methodCallChainSegment(
            MethodCallExpr expression,
            Optional<MethodCallExpr> nextCall,
            MethodCallChainTail finalSegmentSuffix,
            ToIntFunction<String> compactSegmentWidth,
            boolean segmentOnOwnLine
    ) {
        MethodCallChainTail segmentSuffix = nextCall.isEmpty() ? finalSegmentSuffix : MethodCallChainTail.EMPTY;
        Doc segment = methodCallChainSegment(
            expression,
            nextCall.isEmpty(),
            compactSegmentWidth,
            segmentSuffix,
            segmentOnOwnLine
        );
        Doc trailingComment = nextCall
                .map(next -> trailingLineCommentBeforeNextSegment(expression, Optional.of(next)))
                .orElseGet(() -> finalTrailingLineComment(expression));
        if (trailingComment == Doc.EMPTY) {
            return segment;
        }
        return Doc.concat(segment, Doc.lineSuffix(Doc.concat(Doc.text(" "), trailingComment)));
    }

    private Doc appendFinalSegmentSuffix(Doc doc, MethodCallChainTail finalSegmentSuffix) {
        return finalSegmentSuffix.appendTo(doc);
    }

    private Doc trailingLineCommentBeforeNextSegment(Node expression, Optional<MethodCallExpr> nextCall) {
        if (nextCall.isEmpty()) {
            return Doc.EMPTY;
        }
        MethodCallExpr next = nextCall.orElseThrow();
        // A comment that sits on the same physical line as this segment's close can also be the same-line final-trailing
        // comment of an inner chain nested in this segment's lambda argument (the collapsed {@code .orElseThrow(...)) //
        // note .orElseGet(...)} shape, where the inner chain's last call and this outer link share a line). That inner
        // render runs first and already claimed it, so skip already-printed comments here to keep a single claim; output is
        // unchanged because a re-offer of a printed comment only ever rendered empty.
        List<Doc> sourceComments = trailingLineCommentsBeforeNextSegment(expression, next)
                .stream()
                .filter(trivia -> !comments.isPrinted(trivia))
                .map(comments::comment)
                .filter(comment -> comment != Doc.EMPTY)
                .toList();
        return sourceComments.isEmpty() ? Doc.EMPTY : Doc.join(Doc.text(" "), sourceComments);
    }

    private Doc rootTrailingLineCommentBeforeFirstSegment(Expression root, List<MethodCallExpr> calls) {
        if (calls.isEmpty()) {
            return Doc.EMPTY;
        }
        return trailingLineCommentBeforeNextSegment(root, Optional.of(calls.getFirst()));
    }

    /**
     * Reports whether the chain root carries a trailing / root-to-first-selector-gap line comment that the imperative
     * chain renderer would re-emit through {@link #rootTrailingLineCommentBeforeFirstSegment}, for example
     * {@code new Zone(api, auth, "name") // restart note}⏎{@code .withProperty(...)}.
     *
     * <p><strong>Why the fan's other comment gates miss it.</strong> JavaParser attaches such a comment as the root
     * expression's <em>own</em> comment (the {@code ObjectCreationExpr} / root {@code MethodCallExpr} it trails), not as a
     * child or contained comment. {@link MethodCallChainAnalysis#rootHasComments()} is built from
     * {@link SourceShapePolicy#hasContainedComments(Node)} — which lists a node's orphans and its children's comments but
     * <em>not</em> the node's own comment — plus {@code rootToFirstSelectorGapHasBlockComment}, which matches only block
     * {@code /* *}{@code /} markers. The per-selector comment scans key on the selectors' own trivia, and the
     * trailing-line-comment scan only inspects the gaps <em>between</em> and <em>after</em> selectors. So a line comment
     * owned by the root in the gap before the first selector is invisible to every existing comment gate, the chain reads
     * comment-free, and the source-neutral fan ({@code chainFanOut}) re-renders the root through ordinary expression
     * dispatch — which does not carry the root's own comment — silently dropping it.
     *
     * <p>Detecting it here off the same {@link #trailingLineCommentsBeforeNextSegment} candidate set the renderer consumes
     * keeps the withhold verdict and the render in lockstep: any comment this predicate sees is one the imperative path
     * will actually place, so folding it into {@code hasComments} routes the chain off the fan and onto that
     * comment-preserving path without over- or under-withholding. This reads the candidate set only; it does not claim or
     * mark any comment printed, so the real render still owns placement.
     */
    private boolean rootHasTrailingLineCommentBeforeFirstSegment(Expression root, List<MethodCallExpr> calls) {
        if (calls.isEmpty()) {
            return false;
        }
        return !trailingLineCommentsBeforeNextSegment(root, calls.getFirst()).isEmpty();
    }

    private List<JavaCommentTrivia> trailingLineCommentsBeforeNextSegment(Node previous, MethodCallExpr next) {
        List<JavaCommentTrivia> candidates = new ArrayList<>();
        commentPlacement.trailingLineComment(previous).ifPresent(candidates::add);
        candidates.addAll(commentPlacement.containedComments(previous));
        candidates.addAll(lineCommentCandidatesBeforeNextSegment(next));
        // The three candidate sources overlap, so the same comment node can be offered more than once. Dedupe on
        // JavaParser comment identity rather than the record's value equality: structurally equal but distinct comment
        // nodes (e.g. two chain links carrying the same `// text`, or several empty `//` continuation markers) must each
        // survive, while a genuine reference-equal repeat from the overlapping sources is still collapsed.
        Set<Comment> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        return candidates
                .stream()
                .filter(comment -> seen.add(comment.comment()))
                .filter(comment -> comment.startsAfterNodeOnSameLine(previous))
                .filter(comment -> comment.startsBeforeBeginLine(next.getName()))
                .toList();
    }

    private List<JavaCommentTrivia> lineCommentCandidatesBeforeNextSegment(MethodCallExpr next) {
        if (!next.getArguments().isEmpty()) {
            return commentPlacement.lineCommentsBeforeFirst(next, next.getArguments().get(0));
        }
        return commentPlacement.containedComments(next)
                .stream()
                .filter(JavaCommentTrivia::isLine)
                .toList();
    }

    /**
     * Keeps a final segment's same-line comment after the rendered call, even when the call arguments break.
     */
    private Doc finalTrailingLineComment(MethodCallExpr expression) {
        // The same final trailing line comment can be reached from neighboring chain renders (e.g. an outer segment's
        // argument render and the chain's final-segment slot). Skip comments already printed by an earlier traversal path
        // so this slot does not duplicate-claim them; output is unchanged because the first claimant placed the comment
        // and a re-offer only ever rendered empty.
        List<Doc> sourceComments = finalTrailingLineComments(expression)
                .stream()
                .filter(trivia -> !comments.isPrinted(trivia))
                .map(comments::comment)
                .filter(comment -> comment != Doc.EMPTY)
                .toList();
        return sourceComments.isEmpty() ? Doc.EMPTY : Doc.join(Doc.text(" "), sourceComments);
    }

    private List<JavaCommentTrivia> finalTrailingLineComments(MethodCallExpr expression) {
        List<JavaCommentTrivia> sourceComments = new ArrayList<>();
        commentPlacement.trailingLineComment(expression).ifPresent(sourceComments::add);
        commentPlacement.containedComments(expression)
                .stream()
                .filter(JavaCommentTrivia::isLine)
                .filter(comment -> comment.startsAfterNodeOnSameLine(expression))
                .filter(
                    comment -> sourceComments.stream().noneMatch(existing -> existing.comment() == comment.comment())
                )
                .forEach(sourceComments::add);
        return sourceComments;
    }

    private Doc fieldAccessMethodCallSegment(FieldAccessExpr fieldAccess, MethodCallExpr methodCall) {
        String typeArguments = methodCall.getTypeArguments()
                .map(arguments -> "<" + types.compactJoinTypeLike(arguments) + ">")
                .orElse("");
        return Doc.text(
            fieldAccessSuffixAfterMethodRoot(fieldAccess)
                + "."
                + typeArguments
                + methodCall.getNameAsString()
                + "("
                + compactSource.compactJoin(methodCall.getArguments())
                + ")"
        );
    }

    private String fieldAccessSuffixAfterMethodRoot(FieldAccessExpr fieldAccess) {
        Expression scope = fieldAccess.getScope();
        if (scope instanceof MethodCallExpr) {
            return "." + fieldAccess.getNameAsString();
        }
        if (scope instanceof FieldAccessExpr innerFieldAccess) {
            return fieldAccessSuffixAfterMethodRoot(innerFieldAccess) + "." + fieldAccess.getNameAsString();
        }
        return "." + fieldAccess.getNameAsString();
    }

    record MethodCallChainTail(String text) {
        private static final MethodCallChainTail EMPTY = new MethodCallChainTail("");

        static MethodCallChainTail of(String text) {
            return text.isEmpty() ? EMPTY : new MethodCallChainTail(text);
        }

        boolean isEmpty() {
            return text.isEmpty();
        }

        Doc doc() {
            return Doc.text(text);
        }

        Doc appendTo(Doc doc) {
            return isEmpty() ? doc : Doc.concat(doc, doc());
        }

        @Override
        public String toString() {
            return text;
        }
    }
}
