package dev.lanwen.frmtr.java;

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
import java.util.List;
import java.util.Optional;
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


    private final ChainWidthBreakExplain chainWidthBreakExplain;

    private final MixedFieldMethodCallChainLayout mixedFieldChains;

    private final PackedMethodCallChainLayout packedChains;

    private final Function<LambdaExpr, String> lambdaParameters;

    private final Function<LambdaExpr, Optional<Doc>> huggedGapCommentedLambdaBody;

    private final ExpressionLambdaArgumentLayout.ExpressionLambdaMethodCallBodyOpener expressionLambdaMethodCallBodyOpener;

    private final ExpressionLambdaArgumentLayout.ExpressionLambdaObjectCreationBodyOpener expressionLambdaObjectCreationBodyOpener;

    private final ExpressionLambdaArgumentLayout.ExpressionLambdaLogicalBinaryBodyOpenerHug expressionLambdaLogicalBinaryBodyOpenerHug;

    private final ChainSelectorLambdaLayout chainSelectorLambda;

    private final ChainSegmentWidthLayout segmentWidth;

    private final ChainCommentLayout chainComments;

    private final ChainFanLayout chainFan;

    private final ChainSegmentPaddingLayout chainSegmentPadding;

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
            ExpressionLambdaArgumentLayout.ExpressionLambdaObjectCreationBodyOpener expressionLambdaObjectCreationBodyOpener,
            ExpressionLambdaArgumentLayout.ExpressionLambdaLogicalBinaryBodyOpenerHug expressionLambdaLogicalBinaryBodyOpenerHug
    ) {
        this.comments = context.comments;
        this.commentPlacement = context.commentPlacementPolicy;
        this.sourceShapePolicy = context.sourceShapePolicy;
        this.options = context.options;
        this.compactSource = context.compactSource;
        this.layoutWidth = context.layoutWidth;
        this.methodChainPlanner = new MethodCallChainSourcePlanner(context, layoutWidth::currentIndented);
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
        this.expressionLambdaObjectCreationBodyOpener = expressionLambdaObjectCreationBodyOpener;
        this.expressionLambdaLogicalBinaryBodyOpenerHug = expressionLambdaLogicalBinaryBodyOpenerHug;
        this.chainWidthBreakExplain = new ChainWidthBreakExplain(
            context.compactSource,
            context.layoutWidth,
            context.options,
            context.layoutDecisions
        );
        this.mixedFieldChains = new MixedFieldMethodCallChainLayout(
            context.sourceShapePolicy,
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
            this::compactMethodCallChainRoot,
            this::compactMethodCallChainSegmentCanStayFlat,
            (objectCreation, rootDoc, call, rootRendering, lineWidth, firstLineWidth, layout) ->
                objectRootSingleSegmentChain(
                    objectCreation,
                    rootDoc,
                    call,
                    MethodCallChainTail.EMPTY,
                    rootRendering,
                    lineWidth,
                    firstLineWidth,
                    layout
                ),
            this::forcedMethodCallChain
        );
        this.chainSelectorLambda = new ChainSelectorLambdaLayout(
            context.comments,
            context.sourceShapePolicy,
            context.compactSource,
            context.layoutWidth,
            context.options,
            expressionRenderer,
            lambdaParameters,
            huggedGapCommentedLambdaBody,
            huggableExpressionLambdaArguments,
            expressionLambdaMethodCallBodyOpener,
            expressionLambdaObjectCreationBodyOpener,
            expressionLambdaLogicalBinaryBodyOpenerHug,
            this::methodCallSegmentPrefixText,
            this::methodCallChainRootIsObjectCreation,
            this::compactMethodCallChainSegmentCanStayFlat,
            this::appendFinalSegmentSuffix,
            this::fannedSelectorColumnWidth,
            this::brokenMethodCallSegment
        );
        this.segmentWidth = new ChainSegmentWidthLayout(
            options,
            compactSource::compactWithoutOwnComment,
            methodChainPlanner::promotesFirstCall
        );
        this.chainComments = new ChainCommentLayout(comments, commentPlacement, commentedExpressionLists);
        this.chainFan = new ChainFanLayout(
            context.options,
            context.sourceShapePolicy,
            context.compactSource,
            expressionRenderer,
            chainWidthBreakExplain,
            widthDrivenObjectCreationRenderer,
            this::methodCallChainAnalysis,
            this::chainBreaksByRule,
            methodChainPlanner::promotesFirstCall,
            chainComments::methodCallSegmentHasComment,
            this::methodCallSegmentHasBlockLambdaArgument,
            this::methodCallSegmentHasExpressionLambdaArgument,
            this::methodCallChainHasFinalTrailingLineComment,
            chainComments::finalTrailingLineComments,
            chainComments::trailingLineCommentsBeforeNextSegment,
            chainComments::rootHasTrailingLineCommentBeforeFirstSegment,
            this::groupedPromotedMethodCall,
            calls::methodCallPrefix,
            calls::methodCallArgumentList,
            this::chainContinuation,
            this::chainContinuation,
            this::methodCallChainSegments,
            this::rootLineWidth,
            this::compactSingleLineRoot
        );
        this.chainSegmentPadding = new ChainSegmentPaddingLayout();
    }

    Optional<Doc> methodCallChain(MethodCallExpr expression) {
        return methodCallChain(expression, LayoutContext.root());
    }

    Optional<Doc> methodCallChain(MethodCallExpr expression, LayoutContext layout) {
        return methodCallChain(expression, MethodCallBreakMode.AUTO, layout);
    }

    /**
     * Routes a fan-threshold, comment/lambda-free chain to the source-neutral
     * fan builder, independent of the author's source shape. Delegates to {@link ChainFanLayout}, which owns the
     * fan-position rules, the fan-shape rules, and the source-neutral root builders.
     */
    Optional<Doc> canonicalFanChain(MethodCallExpr expression, String finalSegmentSuffix, LayoutContext layout) {
        return chainFan.canonicalFanChain(expression, finalSegmentSuffix, layout);
    }

    /**
     * Reports whether a chain is one {@link #canonicalFanChain} would fan (the structural fan rule fires and no
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
     * The lambda-body position of the canonical fan: whether an expression-lambda-body chain should fan by
     * the canonical rule and its root is one the lambda-body fan renders idempotently. Delegates to
     * {@link ChainFanLayout}.
     */
    boolean lambdaBodyChainFansByCanonicalRule(MethodCallExpr expression) {
        return chainFan.lambdaBodyChainFansByCanonicalRule(expression);
    }

    Optional<Doc> forcedMethodCallChain(MethodCallExpr expression) {
        // A prefix-less forced chain measures the fixed-baseline lineWidth and the first-line width at the same
        // current-member indentation, which is exactly what the firstLineWidth overload below computes when handed
        // {@code currentIndented} for both slots.
        return forcedMethodCallChain(expression, layoutWidth::currentIndented);
    }

    Optional<Doc> forcedMethodCallChain(
            MethodCallExpr expression,
            ToIntFunction<String> firstLineWidth
    ) {
        return forcedMethodCallChain(expression, firstLineWidth, LayoutContext.root());
    }

    // The layout-carrying entry seam. A caller that shares its first line with a fixed prefix (the return
    // chain threads {@code layout.withLeftEdgePrefix("return ")}) hands that context through here so the chain width gates
    // can attribute the prefix at the rendered column. The no-{@code layout} overload above passes {@code root()} (empty
    // prefix), so a forced-chain caller that threads no prefix measures with none. The residual fixed-baseline probes
    // measure at the current-member indentation ({@code currentIndented}); a caller that needs a deeper baseline threads
    // it explicitly through the four-argument overload below.
    Optional<Doc> forcedMethodCallChain(
            MethodCallExpr expression,
            ToIntFunction<String> firstLineWidth,
            LayoutContext layout
    ) {
        return forcedMethodCallChain(expression, layoutWidth::currentIndented, firstLineWidth, layout);
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

    // The lineWidth/firstLineWidth pair lets a caller measure the residual fixed-baseline probes and the first-line
    // width at independent depths. Package-visible so {@link MethodCallPrinter} can thread a caller's fixed baseline into
    // both slots (its former budget-family entry) without a colliding two-argument overload.
    Optional<Doc> forcedMethodCallChain(
            MethodCallExpr expression,
            ToIntFunction<String> lineWidth,
            ToIntFunction<String> firstLineWidth,
            LayoutContext layout
    ) {
        return methodCallChain(
            expression,
            MethodCallBreakMode.FORCED,
            MethodCallChainTail.EMPTY,
            lineWidth,
            firstLineWidth,
            layout
        );
    }

    Optional<Doc> compactRootWithBrokenFinalChainSegment(MethodCallExpr expression) {
        return compactRootWithBrokenFinalChainSegment(expression, layoutWidth::currentIndented);
    }

    Optional<Doc> compactRootWithBrokenFinalChainSegment(
            MethodCallExpr expression,
            ToIntFunction<String> lineWidth
    ) {
        return compactRootWithBrokenFinalChainSegment(expression, lineWidth, LayoutContext.root());
    }

    // The layout-carrying entry seam for the compact-root-with-broken-final-segment shape. The return chain
    // threads {@code layout.withLeftEdgePrefix("return ")} through here so {@code compactRootLineWidth} can attribute the
    // {@code return } prefix at the rendered column. The no-{@code layout} overload above passes {@code root()} (empty
    // prefix), so a caller that threads no prefix measures with none.
    Optional<Doc> compactRootWithBrokenFinalChainSegment(
            MethodCallExpr expression,
            ToIntFunction<String> lineWidth,
            LayoutContext layout
    ) {
        List<MethodCallExpr> calls = new ArrayList<>();
        Expression root = methodChainPlanner.methodCallChainRoot(expression, calls);
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
            return compactRootWithBrokenFinalSegment(methodRoot, calls.getFirst(), lineWidth, layout);
        }
        if (methodChainPlanner.promotesFirstCall(root) && calls.size() == 2) {
            return compactRootWithBrokenFinalSegment(calls.getFirst(), calls.get(1), lineWidth, layout);
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
        if (sourceShapePolicy.hasContainedComments(scoped)) {
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
                        || sourceShapePolicy.hasContainedComments(argument)
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
        return methodCallChain(expression, breakMode, "", layoutWidth::currentIndented, layout);
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
            layoutWidth::currentIndented,
            layout
        );
    }

    Optional<Doc> methodCallChain(
            MethodCallExpr expression,
            MethodCallBreakMode breakMode,
            String finalSegmentSuffix,
            ToIntFunction<String> lineWidth,
            LayoutContext layout
    ) {
        return methodCallChain(expression, breakMode, MethodCallChainTail.of(finalSegmentSuffix), lineWidth, layout);
    }

    Optional<Doc> methodCallChain(
            MethodCallExpr expression,
            MethodCallBreakMode breakMode,
            String finalSegmentSuffix,
            ToIntFunction<String> lineWidth,
            ToIntFunction<String> firstLineWidth,
            LayoutContext layout
    ) {
        return methodCallChain(
            expression,
            breakMode,
            MethodCallChainTail.of(finalSegmentSuffix),
            lineWidth,
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
        return methodCallChain(expression, breakMode, finalSegmentSuffix, layoutWidth::currentIndented, layout);
    }

    private Optional<Doc> methodCallChain(
            MethodCallExpr expression,
            MethodCallBreakMode breakMode,
            MethodCallChainTail finalSegmentSuffix,
            ToIntFunction<String> lineWidth,
            LayoutContext layout
    ) {
        return methodCallChain(expression, breakMode, finalSegmentSuffix, lineWidth, lineWidth, layout);
    }

    private Optional<Doc> methodCallChain(
            MethodCallExpr expression,
            MethodCallBreakMode breakMode,
            MethodCallChainTail finalSegmentSuffix,
            ToIntFunction<String> lineWidth,
            ToIntFunction<String> firstLineWidth,
            LayoutContext layout
    ) {
        MethodCallChainSourcePlanner.MethodCallChainAnalysis analysis = methodCallChainAnalysis(expression);
        boolean rootObjectCreationNeedsBreak = methodChainPlanner.rootObjectCreationNeedsBreak(analysis);
        if (
            !breakMode.isForced()
            && finalBlockLambdaSegmentCanStayCompact(expression, lineWidth)
        ) {
            return Optional.empty();
        }
        if (
            (!breakMode.isForced()
                && !analysis.hasComments()
                && !analysis.hasBlockLambdaArgument()
                // A chain carrying an inter-segment `//` line comment must not stay flat, so its fan-only
                // comment-preserving render is used. See {@link ChainCommentLayout#chainHasInterSegmentLineComment}.
                && !analysis.hasInterSegmentLineComment()
                // A chain that reaches its link-count/root-kind threshold ({@code chainBreaksByRule}) MUST fan one
                // selector per line even when the flat form fits, so it does not stay flat here; the break is routed to
                // the source-neutral `chainFanOut` builder (the early canonical-fan route below), not the imperative
                // ladder downstream.
                && !chainBreaksByRule(analysis)
                && !rootObjectCreationNeedsBreak
                // The stay-flat probe must measure the chain at the same line position it will actually occupy. When the
                // chain shares its line with a prefix (an assignment target plus operator, an initializer name, etc.) the
                // caller threads that prefix through {@code firstLineWidth}; measuring with a prefix-blind width here would
                // keep a chain flat whose real line overflows. {@code firstLineWidth} defaults to {@code lineWidth},
                // so a prefix-less caller measures with a plain {@code lineWidth} probe.
                //
                // The same channel now also carries NESTING DEPTH: a chain rendered as a wrapped call argument or a
                // nested initializer (e.g. {@code RetryPlan.create(...).toRetry()} as the argument of {@code .retryWhen(...)})
                // sits at its enclosing argument list's continuation indentation, deeper than the {@code CURRENT} budget
                // the AUTO entry assumes. The argument-list caller threads that deeper budget ({@code CONTINUATION}) as
                // {@code lineWidth}, so {@code firstLineWidth} here measures the chain at its real column and breaks a
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
                && !forcedSingleCallPrefixOverflows(breakMode, expression, lineWidth)
                && !(breakMode.isForced() && root instanceof ObjectCreationExpr)
                && !rootObjectCreationNeedsBreak
                && !analysis.rootHasComments()
                // A root-to-first-selector `//` line comment (e.g. `new X(...) // note`⏎`.with(...)`) rides in
                // hasInterSegmentLineComment but NOT rootHasComments, so without this a single-selector chain would
                // bail to the flat render and drop it; keep it on the breaking path so the comment gets a home.
                && !analysis.hasInterSegmentLineComment()
                && !analysis.singleCommentedSegment())
        ) {
            return Optional.empty();
        }
        // Route a fan-threshold chain straight to the source-neutral `chainFanOut` builder rather than the
        // source-shape-sensitive imperative ladder below. `chainFanOut` is a pure function of the AST (root + each
        // selector on its own dotted line, root rendered through ordinary expression dispatch), so both passes see the
        // identical AST and rebuild the identical fan — idempotent by construction. `chainFanOut` re-renders the root and
        // each selector once. Admitted comment-free, OR when the chain's only comment is a last-selector trailing line
        // comment ({@code chainCommentsAreOnlyTrailingLine}) — the one shape the fan provably preserves (its
        // `methodCallChainSegments` re-emits that slot), the same relaxation `chainFansByCanonicalRuleAdmittingTrailingComment`
        // admits at the fan position. Every other comment family (root / segment / between-selector) would be dropped or
        // destabilized by the plain-dispatch root re-render, so those and block-lambda chains fall through to the
        // comment-preserving imperative ladder.
        if (
            chainBreaksByRule(analysis)
            && (!analysis.hasComments() || chainCommentsAreOnlyTrailingLine(analysis))
            && !analysis.hasBlockLambdaArgument()
            && calls.stream().noneMatch(chainComments::methodCallSegmentHasComment)
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
        // SAME comment/lambda carve-out as the early canonical route above, with the same last-selector-trailing-line
        // relaxation ({@code chainCommentsAreOnlyTrailingLine}): such a chain is admitted but FORCED to fan below, never
        // ranked against the {@code flat} arm — that arm is {@code compactSource.compact(expression)}, whose
        // {@code compactTokenText} would de-indent / merge its {@code //} comment. Every other comment family stays on the
        // caller's comment-aware routing (kept engaged for an inter-segment line comment by
        // {@link #methodCallChainIsSourceMultiline}) and never reaches this arm.
        if (
            chainIsWidthDrivenTwoSelectorFan(analysis)
            && (!analysis.hasComments() || chainCommentsAreOnlyTrailingLine(analysis))
            && !analysis.hasBlockLambdaArgument()
            && calls.stream().noneMatch(chainComments::methodCallSegmentHasComment)
        ) {
            chainWidthBreakExplain.record(expression, analysis, layout);
            Doc fanOut = chainFanOut(root, calls, finalSegmentSuffix, layout);
            if (rootIsEnclosedFanningChain(root)) {
                return Optional.of(fanOut);
            }
            // A chain admitted only via the trailing-line-comment relaxation ({@code chainCommentsAreOnlyTrailingLine})
            // must fan: the {@code flat} arm built below is {@code compactSource.compact(expression)}, whose
            // {@code compactTokenText} de-indents / merges the last selector's {@code //} comment. {@code chainFanOut}
            // preserves it — {@code methodCallChainSegments} re-emits the last selector's trailing-comment slot.
            if (analysis.hasComments()) {
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
            // lambda-arrow source-shape read, which oscillates once the receiver is un-collapsed. (Only the
            // block/nested-lambda arrow reads here still do that deferred source-shape read; the sibling
            // method-call-body arrow read does not.) Such a chain keeps the {@link Doc#bestFitting} arm
            // (rendered collapsed), so it does not introduce a new oscillation.
            List<Doc> arms = List.of(flat, fanOut);
            boolean bodyForcesMultiline = calls.getLast().getArguments().stream()
                    .anyMatch(this::lambdaArgumentForcesMultilineBody);
            // A body-forces-multiline chain whose lambda body carries a contained comment cannot use the {@code flat}
            // compact arm at all: {@code compactSource.compact} routes a comment-bearing subtree through
            // {@code compactTokenText}, which only collapses whitespace RUNS, so every {@code //} line comment de-indents
            // to column one and merges the following token into itself (the nested {@code forEach(… -> // note … body)}
            // shape). {@link Doc#bestFitting} would still rank that malformed one-line-ish arm fewest-lines
            // and emit it. Fan unconditionally instead — {@link #chainFanOut} dispatches the lambda body through its own
            // printer, which keeps each comment on its own line at the body indent. The enclosing {@code !analysis.hasComments()}
            // guard already kept CHAIN-level comments out of this branch, so the only comments here live inside the lambda
            // body argument and are claimed exactly once by that body's renderer, never double-claimed by the fan root.
            if (bodyForcesMultiline && sourceShapePolicy.hasContainedComments(expression)) {
                return Optional.of(fanOut);
            }
            return Optional.of(bodyForcesMultiline ? Doc.bestFitting(arms) : Doc.conditionalGroup(arms));
        }
        // A chain that must fan ONLY to preserve an inter-segment {@code //} line comment ({@link ChainCommentLayout#chainHasInterSegmentLineComment})
        // would otherwise collapse in the source-shape fall-through below and DROP the comment (the
        // {@code encode(x) // note}⏎{@code .replaceAll(...)} MirrorMaker shape, and the leading-{@code //}-before-a-selector
        // dot-gap shape). Route those chains to the comment-preserving one-segment-per-line fan here — the root rendered
        // with its trailing line comment, then {@link #methodCallChainSegments} re-emitting each selector's leading /
        // trailing line comment on its own continuation line. This claims the same comment candidate sets the imperative
        // render consumes (each rendered exactly once), so it is comment-safe and structural: the fan verdict keys on
        // comment presence, not on the author's line breaks, so both passes fan identically. Multi-selector-comment /
        // block-lambda / expression-lambda-selector chains are left to the existing comment-carrying imperative paths.
        Optional<Doc> flatHeadHuggedFinalLambda = chainSelectorLambda.flatHeadHuggedCommentLambdaChain(expression, analysis, finalSegmentSuffix);
        if (flatHeadHuggedFinalLambda.isPresent()) {
            return flatHeadHuggedFinalLambda;
        }
        if (canBreakAfterCompactExpressionLambdaRoot(breakMode, root, calls, layout)) {
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
            && !sourceShapePolicy.hasContainedComments(root)
            && !sourceShapePolicy.hasContainedComments(calls.getFirst())
            && !chainComments.methodCallSegmentHasComment(calls.getFirst())
            && !analysis.rootHasBlockLambdaArgument()
        ) {
            Expression probeRoot = root;
            List<MethodCallExpr> probeCalls = calls;
            Optional<Doc> compactRootWithBrokenSegment = compactRootWithBrokenFinalSegment(
                probeRoot,
                probeCalls.getFirst(),
                finalSegmentSuffix,
                lineWidth,
                layout
            );
            if (compactRootWithBrokenSegment.isPresent()) {
                return compactRootWithBrokenSegment;
            }
        }
        if (calls.size() == 1 && root instanceof MethodCallExpr methodRoot) {
            Doc rootDoc = singleSegmentMethodRootDoc(methodRoot);
            Doc rootTrailingComment = chainComments.rootTrailingLineCommentBeforeFirstSegment(methodRoot, calls);
            if (rootTrailingComment != Doc.EMPTY) {
                rootDoc = Doc.concat(rootDoc, Doc.lineSuffix(Doc.concat(Doc.text(" "), rootTrailingComment)));
            }
            // A leading line comment on the only segment ({@code lookup(a)} then {@code // c1} on its own line then
            // {@code .orElseThrow(x)}) must own its own continuation line so the comment stays above the segment selector.
            // Attaching such a segment to the root close glued the comment onto the root's closing parenthesis
            // ({@code lookup(a)// c1}); a scope-rooted chain already avoids this because its segments go one-per-line, so
            // route the single-segment case the same way once the segment carries a leading comment.
            if (chainComments.methodCallSegmentHasLeadingLineComment(calls.getFirst())) {
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
            if (chainComments.methodCallSegmentHasLeadingGapBlockComment(methodRoot, calls.getFirst())) {
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
                    methodCallChainSegmentAttachedToRootClose(calls.getFirst(), finalSegmentSuffix, lineWidth)
                )
            );
        }
        if (
            root instanceof FieldAccessExpr fieldAccess
            && fieldAccess.getScope() instanceof MethodCallExpr methodRoot
            && calls.size() == 1
        ) {
            // A `//` line comment before the inner method-call selector (the `.util()` in `x.util().java.java()`) is
            // dropped by the flat expressionRenderer root when an expanded shape parks it as the call's orphan rather
            // than the name's own comment; fan the inner call onto its own continuation line so its segment prefix
            // re-emits the comment, matching the canonical shape.
            Doc methodRootDoc =
                chainComments.methodCallSegmentHasLeadingLineComment(methodRoot) && methodRoot.getScope().isPresent()
                    ? Doc.concat(
                        expressionRenderer.format(methodRoot.getScope().orElseThrow(), LayoutContext.root()),
                        chainContinuation(methodCallChainSegment(methodRoot))
                    )
                    : expressionRenderer.format(methodRoot, LayoutContext.root());
            return Optional.of(
                Doc.concat(
                    methodRootDoc,
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
        Doc rootDoc = methodCallChainRootDoc(chainPlan, firstLineWidth, layout);
        // Track whether {@code rootDoc} is still the plain {@code expressionRenderer.format(root, root())} doc — the exact
        // root {@link #chainFanOut} rebuilds — so the multi-segment fall-through below can route through the shared fan-out
        // builder byte-identically only in that case. It holds only for an EXPRESSION_RENDERER root that did not fall to the
        // broken-method-call shape; a promoted/grouped/broken-object-creation root, a first-segment-attached root, or a
        // root-trailing-comment-wrapped root produces a different {@code rootDoc} and stays on the inline construction.
        //
        // The comment gate is load-bearing: the fall-through routing through {@code chainFanOut} re-renders the root a
        // second time (the {@code rootDoc} built here is discarded in that path). Admitted comment-free, OR when the chain's
        // only comment is a last-selector trailing line comment ({@code chainCommentsAreOnlyTrailingLine}) — that relaxation
        // requires {@code !rootHasComments}, so the root re-renders to a byte-identical {@code Doc} and {@code chainFanOut}'s
        // {@code methodCallChainSegments} re-emits the last selector's trailing slot. Every other comment family keeps the
        // unchanged inline construction (rendered once); re-rendering the root through the fan would drop or destabilize a
        // root / segment / between-selector comment.
        boolean rootDocIsPlainExpressionRenderRoot =
            chainPlan.rootRendering() == MethodCallChainSourcePlanner.ChainRootRendering.EXPRESSION_RENDERER
            && (!analysis.hasComments() || chainCommentsAreOnlyTrailingLine(analysis))
            && !expressionRenderedChainRootBreaksMethodCall(chainPlan.root(), firstLineWidth);
        boolean firstSegmentAttachedToRoot = false;
        if (canAttachFirstSegmentToSimpleRoot(expression, chainPlan, calls, analysis)) {
            MethodCallExpr firstCall = calls.getFirst();
            root = firstCall;
            calls = new ArrayList<>(calls.subList(1, calls.size()));
            rootDoc = firstSegmentAttachedToSimpleRootDoc(
                chainPlan.root(),
                firstCall
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
            root instanceof MethodCallExpr methodRoot
            && calls.size() == 1
            && !sourceShapePolicy.hasContainedComments(root)
            && !chainComments.methodCallSegmentHasComment(calls.getFirst())
            && methodCallSegmentHasBlockLambdaArgument(calls.getFirst())
            && blockLambdaSegmentFirstLine(compactSource.compact(methodRoot), calls.getFirst())
                    // Measure the block-lambda root first line at the root's true rendered block/type depth
                    // ({@link LayoutWidth#nodeLine}) instead of the fixed BLOCK baseline.
                    .filter(firstLine -> layoutWidth.nodeLine(methodRoot, firstLine) <= options.lineWidth())
                    .isPresent()
        ) {
            return Optional.empty();
        }
        Doc rootTrailingComment = chainComments.rootTrailingLineCommentBeforeFirstSegment(root, calls);
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
            // Rank the compact-with-broken-segment shape against the one-segment-per-line fan-out and let
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
                lineWidth,
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
                lineWidth,
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
            && !chainComments.methodCallSegmentHasComment(calls.getFirst())
        ) {
            if (
                methodCallSegmentHasBlockLambdaArgument(calls.getFirst())
                && blockLambdaSegmentFirstLine(compactSource.compact(methodRoot), calls.getFirst())
                        // Measure the block-lambda root first line at the root's true rendered block/type depth
                        // ({@link LayoutWidth#nodeLine}) instead of the fixed BLOCK baseline.
                        .filter(firstLine -> layoutWidth.nodeLine(methodRoot, firstLine) <= options.lineWidth())
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
            Optional<Doc> expressionLambdaRoot = expressionLambdaRootWithSingleSegment(methodRoot, probeCall, finalSegmentSuffix, lineWidth, layout);
            if (expressionLambdaRoot.isPresent()) {
                return expressionLambdaRoot;
            }
            // When the final segment carries breakable arguments the compact-with-broken-segment shape and
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
                lineWidth,
                layout
            );
            if (rankedSingleSegment.isPresent()) {
                return rankedSingleSegment;
            }
            if (compactRootFinalSegmentLineOverflows(
                    methodRoot,
                    calls.getFirst(),
                    finalSegmentSuffix,
                    lineWidth,
                    layout
                )) {
                Optional<Doc> compactRootWithBrokenSegment = compactRootWithBrokenFinalSegment(methodRoot, probeCall, finalSegmentSuffix, lineWidth, layout);
                if (compactRootWithBrokenSegment.isPresent()) {
                    return compactRootWithBrokenSegment;
                }
                // The full chain (compact root plus the attached final segment) overflows at this line position, but the
                // final segment has no arguments to break (e.g. {@code .toRetry()}/{@code .build()}), so the previous
                // helper found nothing to wrap. When the root itself carries breakable arguments, break the root's
                // argument list instead and glue the segment to its close: {@code Type.create(}\n args \n{@code ).toRetry()}.
                // This is the same shape a source-multiline root already produces below; here it is reached for a flat
                // source root that only overflows because it renders at a deep nesting column (a wrapped call argument or
                // nested initializer), the column the caller threads through {@code lineWidth}/{@code firstLineWidth}.
                Optional<Doc> brokenRootWithAttachedSegment = brokenRootWithAttachedFinalSegment(methodRoot, probeCall, finalSegmentSuffix, lineWidth, layout);
                if (brokenRootWithAttachedSegment.isPresent()) {
                    return brokenRootWithAttachedSegment;
                }
            }
            Optional<Doc> sourceMultilineRoot =
                this.calls.sourceMultilineArguments(methodRoot);
            if (sourceMultilineRoot.isPresent()) {
                return Optional.of(
                    Doc.concat(
                        sourceMultilineRoot.orElseThrow(),
                        methodCallChainSegmentAttachedToRootClose(calls.getFirst(), finalSegmentSuffix, lineWidth)
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
            // Attach the single trailing segment at the root's closing CONTINUATION column, matching the
            // source-multiline-root path above (`:1010`). Measuring it beside its stale source column instead flips the
            // segment (esp. an expression-lambda selector like `.mapToObj(v -> …)`) between broken and collapsed across
            // passes: the flat-source pass reaches here while the re-parsed broken-source pass takes the
            // source-multiline route, so the two must share the same continuation-column measurement (#137, family E).
            // Attach the single trailing segment at the column it actually renders at. When the expression-renderer root
            // BREAKS its method call ({@code IntStream.range(}⏎ args ⏎{@code )}), the segment sits on the root's closing
            // CONTINUATION line, so measure it there ({@code ")" + segment}) — matching the source-multiline-root path
            // above (`:1010`) so the flat-source and re-parsed-broken-source passes share one measurement and the segment
            // (esp. an expression-lambda selector like {@code .mapToObj(v -> …)}) stops flipping broken⇄collapsed (#137,
            // family E). When the root stays FLAT ({@code Stream.of(a, b, c).forEach(…)}), the segment sits BESIDE the
            // compact root, not after a bare {@code )}; the continuation measurement would under-count its column by the
            // whole root width and hug an over-wide lambda opener, so keep the beside-the-root measurement instead.
            Doc singleSegment = expressionRenderedChainRootBreaksMethodCall(methodRoot, firstLineWidth)
                ? methodCallChainSegmentAttachedToRootClose(calls.getFirst(), finalSegmentSuffix, lineWidth)
                : methodCallChainSegment(calls.getFirst(), finalSegmentSuffix);
            return Optional.of(Doc.concat(rootDoc, singleSegment));
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
        // Object-creation root seam: a comment-free, non-anonymous, non-empty-argument
        // constructor-rooted fan-threshold chain ({@code new EndpointFactory(a, b, c, d).generate(…).blockFirst(…)}) whose
        // planner rendering is {@code BROKEN_OBJECT_CREATION} routes through the shared {@code chainFanOut} builder, whose
        // object-creation-root arm renders the constructor arguments through the source-neutral width-driven
        // {@link #promotedObjectCreationRootDoc}. This converges with the flat-selector pass, which already reaches
        // {@code chainFanOut} through the early canonical-fan route: both passes now render the root through the same
        // width-driven group, so a constructor line that fits stays flat on every pass instead of flipping to the
        // {@code brokenObjectCreationRenderer} force-break shape once a non-final selector's arguments span source lines.
        // Admitted comment-free, OR when the chain's only comment is a last-selector trailing line comment
        // ({@code chainCommentsAreOnlyTrailingLine}, which requires {@code !rootHasComments}): {@code chainFanOut} re-renders
        // the comment-free object-creation root through its width-driven promotion and {@code methodCallChainSegments}
        // re-emits the last selector's trailing slot, so no comment is dropped. Every other comment family (and block-lambda
        // chains) keep the inline construction below (rendered once), where re-rendering the root through the fan would drop
        // its comment. The selectors render identically either way — the fan's multi-segment tail is byte-for-byte the
        // {@code chainContinuation(root, methodCallChainSegments(...))} the inline construction builds — so only the root doc changes.
        if (
            objectCreationRootIsWidthDrivenFanEligible(root)
            && chainPlan.rootRendering() == MethodCallChainSourcePlanner.ChainRootRendering.BROKEN_OBJECT_CREATION
            && !firstSegmentAttachedToRoot
            && (!analysis.hasComments() || chainCommentsAreOnlyTrailingLine(analysis))
            && !analysis.hasBlockLambdaArgument()
            && calls.stream().noneMatch(chainComments::methodCallSegmentHasComment)
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
     * The canonical-fan structural rule — see {@link MethodCallChainSourcePlanner#chainBreaksByRule} for
     * the link-count/root-kind thresholds, which that planner method owns as the single source of truth. This chain
     * printer and the variable-initializer path (via {@code InitializerChainShape.chainBreaksByRule}) both read the
     * identical verdict, so a fan-threshold chain routes onto the same source-neutral fan without the rule drifting
     * between two copies.
     */
    private boolean chainBreaksByRule(MethodCallChainSourcePlanner.MethodCallChainAnalysis analysis) {
        return methodChainPlanner.chainBreaksByRule(analysis);
    }

    private boolean methodRootCanKeepSingleSuffixAttached(MethodCallExpr methodRoot) {
        if (!sourceShapePolicy.hasContainedComments(methodRoot)) {
            return true;
        }
        if (
            chainComments.methodCallSegmentHasLineComments(methodRoot)
            && !chainComments.methodCallSegmentHasLeadingLineComment(methodRoot)
            && !chainComments.methodCallSegmentHasNameComment(methodRoot)
        ) {
            return true;
        }
        return methodCallSegmentHasBlockLambdaArgument(methodRoot)
            && !chainComments.methodCallSegmentHasLeadingLineComment(methodRoot)
            && !chainComments.methodCallSegmentHasNameComment(methodRoot);
    }

    private boolean finalBlockLambdaSegmentCanStayCompact(
            MethodCallExpr expression,
            ToIntFunction<String> lineWidth
    ) {
        if (!methodCallSegmentHasBlockLambdaArgument(expression) || chainComments.methodCallSegmentHasComment(expression)) {
            return false;
        }
        String callPrefix = calls.methodCallPrefix(expression);
        return huggableBlockLambdaFirstLine.apply(callPrefix, expression.getArguments())
                .filter(firstLine -> lineWidth.applyAsInt(firstLine) <= options.lineWidth())
                .isPresent();
    }

    /**
     * Reports whether a method-call root's compact first line, with the single final segment attached
     * ({@code root.selector(args)…}), overflows — the flat-gate that decides whether the statement/field single-segment
     * chain must break onto the {@link #compactRootWithBrokenFinalSegment} / {@link #brokenRootWithAttachedFinalSegment}
     * broken shapes.
     *
     * <p>{@code layout} is threaded so the true continuation column ({@link LayoutContext#leftEdgePrefix()}) is
     * available at this flat-gate. It is NOT consulted here: the decision uses the fixed-budget
     * {@code lineWidth.applyAsInt(…)} floor. The statement/field callers pass their real {@link LayoutContext}
     * (a {@code STATEMENT}/{@code root()} context whose {@code leftEdgePrefix} is empty), matching the sibling
     * {@link #compactRootLineWidth} gate this parameter mirrors.
     */
    private boolean compactRootFinalSegmentLineOverflows(
            MethodCallExpr methodRoot,
            MethodCallExpr call,
            MethodCallChainTail finalSegmentSuffix,
            ToIntFunction<String> lineWidth,
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
            + segmentWidth.methodCallSegmentArgumentsWidthText(call.getArguments())
            + ")"
            + finalSegmentSuffix;
        return lineWidth.applyAsInt(compactLine) > options.lineWidth();
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
     * itself fits at {@code lineWidth}, so the broken shape is only chosen when it is both needed and valid.
     *
     * <p>{@code layout} is threaded so the true continuation column ({@link LayoutContext#leftEdgePrefix()}) is
     * available at this statement/field single-segment flat-gate. It is NOT consulted here: the opener-fit decision uses
     * the fixed-budget {@code lineWidth.applyAsInt(…)} floor (the statement/field callers pass an empty-prefix
     * context).
     */
    private Optional<Doc> brokenRootWithAttachedFinalSegment(
            MethodCallExpr methodRoot,
            MethodCallExpr call,
            MethodCallChainTail finalSegmentSuffix,
            ToIntFunction<String> lineWidth,
            LayoutContext layout
    ) {
        if (
            methodRoot.getArguments().isEmpty()
            || sourceShapePolicy.hasContainedComments(methodRoot)
            || methodRoot.getArguments().stream().anyMatch(argument -> argument instanceof LambdaExpr)
        ) {
            return Optional.empty();
        }
        if (lineWidth.applyAsInt(calls.methodCallPrefix(methodRoot) + "(") > options.lineWidth()) {
            return Optional.empty();
        }
        return Optional.of(
            Doc.concat(
                calls.brokenMethodCall(methodRoot),
                methodCallChainSegmentAttachedToRootClose(call, finalSegmentSuffix, lineWidth)
            )
        );
    }

    /**
     * Emits one ranked {@link Doc#bestFitting(java.util.List) bestFitting} for a comment-free,
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
     * <p><strong>Comment handling.</strong> The {@code !analysis.hasComments()} bail was removed: the caller
     * already withholds any chain whose final selector carries its own comment, and every comment renders
     * through the claim-neutral {@code ownedComment} rail, so building both ranked arms eagerly can no longer drop or
     * double-claim a comment. Verified byte-identical across the full suite (CommentPresence / Idempotence /
     * AstEquivalence, zero golden moves).
     */
    private Optional<Doc> rankedSingleSegmentChain(
            MethodCallExpr methodRoot,
            MethodCallExpr call,
            MethodCallChainTail finalSegmentSuffix,
            MethodCallChainSourcePlanner.ChainRootRendering rootRendering,
            MethodCallChainSourcePlanner.MethodCallChainAnalysis analysis,
            ToIntFunction<String> lineWidth,
            LayoutContext layout
    ) {
        if (
            rootRendering != MethodCallChainSourcePlanner.ChainRootRendering.EXPRESSION_RENDERER
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
            compactRootWithBrokenFinalSegment(methodRoot, call, finalSegmentSuffix, lineWidth, layout);
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
     * The object-creation-rooted sibling of {@link #rankedSingleSegmentChain}. Emits one ranked
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
     * width-ranked alternative, so ranking can never override it.
     *
     * <p><strong>Comment handling.</strong> The {@code !analysis.hasComments()} bail was removed. Unlike the
     * method-root ranker's caller, this caller does not pre-withhold comment-bearing chains, so they now reach the ranker;
     * every comment renders through the claim-neutral {@code ownedComment} rail, so building both ranked arms
     * eagerly (and deferring to {@link #objectRootSingleSegmentChain} otherwise) can no longer drop or double-claim a
     * comment. Verified byte-identical across the full suite (CommentPresence / Idempotence / AstEquivalence, zero golden
     * moves).
     */
    private Optional<Doc> rankedObjectRootSingleSegmentChain(
            ObjectCreationExpr objectCreation,
            MethodCallExpr call,
            MethodCallChainTail finalSegmentSuffix,
            Doc rootDoc,
            MethodCallChainSourcePlanner.ChainRootRendering rootRendering,
            MethodCallChainSourcePlanner.MethodCallChainAnalysis analysis,
            ToIntFunction<String> lineWidth,
            LayoutContext layout
    ) {
        if (
            rootRendering != MethodCallChainSourcePlanner.ChainRootRendering.EXPRESSION_RENDERER
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
            compactRootWithBrokenFinalSegment(objectCreation, call, finalSegmentSuffix, lineWidth, layout);
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

    /**
     * Reports whether a chain's only comment is a single trailing line comment on its last selector — the one shape the
     * source-neutral {@link #chainFanOut} provably preserves (see {@link ChainFanLayout#chainCommentsAreOnlyTrailingLine}).
     * The comment-bearing fan-route gates use this to admit exactly that shape to the fan while still withholding every
     * other comment family. Delegates to {@link ChainFanLayout}.
     */
    private boolean chainCommentsAreOnlyTrailingLine(MethodCallChainSourcePlanner.MethodCallChainAnalysis analysis) {
        return chainFan.chainCommentsAreOnlyTrailingLine(analysis);
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
            || chainComments.methodCallSegmentHasComment(calls.getFirst())
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
            || chainComments.methodCallSegmentHasLeadingLineComment(firstCall)
            || chainComments.methodCallSegmentHasNameComment(firstCall)
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
        // This gate is a pure structural no-op: every path returns false, so first-segment attachment never engages
        // and both passes take the imperative chain path identically. The guards are kept as an inert structural
        // placeholder.
        if (
            chainPlan.rootRendering() != MethodCallChainSourcePlanner.ChainRootRendering.EXPRESSION_RENDERER
            || calls.size() < 2
            || analysis.hasComments()
            || chainPlan.root() instanceof MethodCallExpr
            || chainPlan.root() instanceof ObjectCreationExpr
            || rootIsEnclosedFanningChain(chainPlan.root())
        ) {
            return false;
        }
        return false;
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
            MethodCallExpr firstCall
    ) {
        // Measure the first segment's fit at its true rendered block/type depth (nodeLine) instead of CURRENT.
        if (sourceShapePolicy.fitsOnOneLine(firstCall, text -> layoutWidth.nodeLine(firstCall, text))) {
            return inlineMethodCall(firstCall);
        }
        return brokenFirstSegmentAttachedToSimpleRoot(root, firstCall);
    }

    private String firstSegmentAttachedToSimpleRootFirstLine(Expression root, MethodCallExpr firstCall) {
        // Measure the first segment's fit at its true rendered block/type depth (nodeLine) instead of CURRENT.
        if (sourceShapePolicy.fitsOnOneLine(firstCall, text -> layoutWidth.nodeLine(firstCall, text))) {
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
            ToIntFunction<String> lineWidth
    ) {
        return breakMode.isForced()
            && expression.getScope().isPresent()
            && methodCallSegmentHasBlockLambdaArgument(expression)
            && lineWidth.applyAsInt(calls.methodCallPrefix(expression) + "(") > options.lineWidth();
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
            calls.sourceMultilineArguments(methodRoot);
        if (sourceMultilineArguments.isPresent()) {
            return sourceMultilineArguments.orElseThrow();
        }
        Optional<Doc> brokenScopedMethodRoot =
            brokenTypeLikeScopedMethodRoot(methodRoot);
        if (brokenScopedMethodRoot.isPresent()) {
            return brokenScopedMethodRoot.orElseThrow();
        }
        if (
            // Measure the method root at its true rendered block/type depth (nodeLine) instead of CURRENT.
            layoutWidth.nodeLine(methodRoot, compactSourceWidthText(methodRoot)) > options.lineWidth()
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
                // Measure the scoped call at its true rendered block/type depth (nodeLine) instead of CURRENT.
                .filter(call -> layoutWidth.nodeLine(call, compactSourceWidthText(call)) > options.lineWidth());
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
                // Measure the scoped call at its true rendered block/type depth (nodeLine) instead of CURRENT.
                .map(scopedCall -> layoutWidth.nodeLine(scopedCall,
                        compactSourceWidthText(scopedCall)
                    ) > options.lineWidth()
                )
                .orElse(false);
    }

    private String compactSourceWidthText(Expression expression) {
        // Source-neutral compact form, not normalizeWhitespace(rawWithoutOwnComment): the latter turns each source
        // newline into a space, so an expression the author already wrapped measures wider than its flat form and the
        // root/scope-overflow gates that consume this width flip their verdict between passes.
        return compactSource.compactWithoutOwnComment(expression);
    }

    private Doc groupedPromotedMethodCall(MethodCallExpr expression) {
        Optional<Doc> sourceMultilineArguments =
            calls.sourceMultilineArguments(expression);
        if (sourceMultilineArguments.isPresent()) {
            return sourceMultilineArguments.orElseThrow();
        }
        if (
            expression.getArguments().size() > 1
            // Measure the promoted method call at its true rendered block/type depth (nodeLine) instead of CURRENT.
            && !sourceShapePolicy.fitsOnOneLine(expression, text -> layoutWidth.nodeLine(expression, text))
        ) {
            return calls.brokenMethodCall(expression);
        }
        Optional<Doc> huggableExpressionLambda =
            groupedPromotedExpressionLambda(expression);
        if (huggableExpressionLambda.isPresent()) {
            return huggableExpressionLambda.orElseThrow();
        }
        if (methodCallSegmentHasBlockLambdaArgument(expression)) {
            return blockLambdaSegmentFirstLine(compactSource.compact(expression.getScope().orElseThrow()), expression)
                    // Measure the promoted block-lambda first line at its true rendered block/type depth
                    // ({@link LayoutWidth#nodeLine}) instead of the fixed BLOCK baseline.
                    .filter(firstLine -> layoutWidth.nodeLine(expression, firstLine) <= options.lineWidth())
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
     * always returns empty. Retained so the candidate-ladder dispatch in {@link #groupedPromotedMethodCall} stays wired.
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
                    // Measure the promoted-root block-lambda first line at the root's true rendered block/type
                    // depth ({@link LayoutWidth#nodeLine}) instead of the fixed BLOCK baseline.
                    .filter(firstLine -> layoutWidth.nodeLine(root, firstLine) <= options.lineWidth())
                    .map(ignored -> Doc.concat(rootDoc, methodCallChainSegment(expression, finalSegmentSuffix)))
                    .orElseGet(() -> Doc.concat(
                            rootDoc,
                            chainContinuation(methodCallChainSegment(expression, finalSegmentSuffix))
                    ));
        }
        return Doc.group(
            Doc.concat(
                rootDoc,
                // Measure the segment as on its own continuation line: the softChainContinuation group drops it onto its
                // own line when it breaks, so its argument-break gate must measure at the continuation column, not the
                // source-column beside-a-token estimate — the latter reads the author's shape and flips the segment's
                // argument list between broken and collapsed across passes.
                softChainContinuation(
                    methodCallChainSegment(
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
     * <p>This applies to <strong>every</strong> caller: a statement chain ({@code new ProfileRequest(...).submit(10);})
     * wants the same {@code new ProfileRequest(...)}\n{@code .submit(10)} shape rather than the arg-opened
     * {@code .submit(}\n{@code 10}\n{@code )}. The verdict is a pure function of the AST (an {@link ObjectCreationExpr}
     * root and a single simple selector argument), so it is a fixpoint regardless of any leading prefix; the enclosing
     * width probe in {@link #objectRootSingleSegmentChain} still decides flat-versus-fan. Restricted to
     * {@link ObjectCreationExpr} roots;
     * "simple" mirrors {@link ControlConditionMethodCallLayout#hasComplexArgument}'s inverse via
     * {@link ChainSegmentWidthLayout#singleSimpleMethodCallSegmentArgument} ({@code NameExpr | FieldAccessExpr | ThisExpr | SuperExpr |
     * LiteralExpr}); a lambda, method-call, multi-argument, or already-multiline tail is not simple and still opens
     * exactly as before.
     */
    private boolean refuseOpeningSingleSimpleObjectRootChainTail(
            Expression root,
            MethodCallExpr call
    ) {
        return root instanceof ObjectCreationExpr
            && segmentWidth.singleSimpleMethodCallSegmentArgument(call);
    }

    private Optional<Doc> compactRootWithBrokenFinalSegment(Expression root, MethodCallExpr call) {
        return compactRootWithBrokenFinalSegment(
            root,
            call,
            MethodCallChainTail.EMPTY,
            layoutWidth::currentIndented,
            LayoutContext.root()
        );
    }

    private Optional<Doc> compactRootWithBrokenFinalSegment(
            Expression root,
            MethodCallExpr call,
            ToIntFunction<String> lineWidth,
            LayoutContext layout
    ) {
        return compactRootWithBrokenFinalSegment(root, call, MethodCallChainTail.EMPTY, lineWidth, layout);
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
            layoutWidth::currentIndented,
            layout
        );
    }

    private Optional<Doc> compactRootWithBrokenFinalSegment(
            Expression root,
            MethodCallExpr call,
            MethodCallChainTail finalSegmentSuffix,
            ToIntFunction<String> lineWidth,
            LayoutContext layout
    ) {
        if (call.getArguments().isEmpty()) {
            return Optional.empty();
        }
        if (refuseOpeningSingleSimpleObjectRootChainTail(root, call)) {
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
            && compactRootLineWidth(root, callPrefix + "(", layout) > options.lineWidth()
        ) {
            return Optional.empty();
        }
        if (!compactRootFirstLineFits(root, callPrefix, call.getArguments(), layout)) {
            return Optional.empty();
        }
        Optional<Doc> huggableLambda =
            huggableBlockLambdaArguments.apply(callPrefix, call.getArguments());
        if (huggableLambda.isPresent()) {
            return Optional.of(Doc.concat(huggableLambda.orElseThrow(), finalSegmentSuffix.doc()));
        }
        String prefix = callPrefix + "(";
        if (
            root instanceof ObjectCreationExpr
            && compactRootLineWidth(root, prefix, layout) > options.lineWidth()
        ) {
            return Optional.empty();
        }
        if (lineWidth.applyAsInt(prefix + ")") > options.lineWidth()) {
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
            LayoutContext layout
    ) {
        Optional<String> blockLambdaFirstLine = huggableBlockLambdaFirstLine.apply(callPrefix, arguments);
        if (
            blockLambdaFirstLine
                    .filter(
                        firstLine -> compactRootLineWidth(
                            root,
                            firstLine,
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
                        line -> compactRootLineWidth(root, line, layout),
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
     *   <li><strong>Prefix threaded.</strong> When a caller supplies its fixed leading prefix — the
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
     */
    private int compactRootLineWidth(
            Expression root,
            String firstLine,
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
                // Rangeless (synthetic) fallback measures at the rendered column, mirroring the prefix-set arm's
                // nodeIndentWidth term, instead of a fixed indentation baseline.
                .orElseGet(() -> layoutWidth.nodeIndentWidth(root) + firstLine.length());
    }

    private boolean expressionLambdaBodyOpenerOverflows(
            Expression root,
            String callPrefix,
            NodeList<Expression> arguments,
            LayoutContext layout
    ) {
        return expressionLambdaArgumentPlan.plan(callPrefix, arguments, layout)
                .filter(plan -> plan.bodyOpenerFitsOnContinuation(layoutWidth::continuationStatement, options.lineWidth()))
                .filter(plan -> plan.bodyOpenerOverflows(
                        line -> compactRootLineWidth(root, line, layout),
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
                        segments.stream().map(segment -> chainSegmentPadding.linePadded(segment, padding)).toList()
                    )
                )
            );
        }
        return chainContinuation(Doc.join(Doc.HARD_LINE, segments));
    }

    private Optional<String> compactSingleLineRoot(Expression root) {
        if (sourceShapePolicy.hasContainedComments(root)) {
            return Optional.empty();
        }
        return Optional.of(compactSource.compact(root));
    }

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
        return !chainComments.finalTrailingLineComments(finalCall).isEmpty();
    }

    /**
     * Routes a chain carrying an inter-segment {@code //} line comment onto its caller's comment-aware chain path.
     *
     * <p>This is a pure comment-presence router: a chain fans by width / structural rule, never by the author's line
     * breaks, so a chain with an inter-segment line comment is reported "source-multiline" here so the caller (e.g.
     * {@code MethodCallPrinter}'s comment-aware
     * branch) keeps it off the plain method-call render that would drop the comment — the MirrorMaker
     * {@code encode(x) // note}⏎{@code .replaceAll(...)} shape. Keyed on comment presence, not line breaks (see
     * {@link ChainCommentLayout#chainHasInterSegmentLineComment}). This covers the leading/trailing/gap inter-segment {@code //} positions;
     * an ORPHAN {@code //} floated between blank lines inside the chain (JavaParser parks it on an inner-selector
     * MethodCallExpr, not as a segment comment) is NOT covered here and remains a known comment-placement gap — the
     * {@code method-chain-member-access @ expanded} perturbation.
     */
    boolean methodCallChainIsSourceMultiline(MethodCallExpr expression) {
        return methodCallChainAnalysis(expression).hasInterSegmentLineComment();
    }

    MethodCallChainSourcePlanner.InitializerChainShape methodCallChainInitializerShape(MethodCallExpr expression) {
        return methodChainPlanner.initializerShape(methodCallChainAnalysis(expression));
    }

    private MethodCallChainSourcePlanner.MethodCallChainAnalysis methodCallChainAnalysis(MethodCallExpr expression) {
        return methodChainPlanner.analyze(
            expression,
            chainComments::methodCallSegmentHasComment,
            chainComments::methodCallSegmentHasNameComment,
            chainComments::methodCallSegmentHasArgumentGapComment,
            this::methodCallSegmentHasBlockLambdaArgument,
            chainComments::methodCallChainHasTrailingLineComments,
            chainComments::rootHasTrailingLineCommentBeforeFirstSegment,
            chainComments::chainHasInterSegmentLineComment
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
        // Measure the promoted method root at its true rendered block/type depth (nodeLine) instead of CURRENT.
        // (promotedRootArgumentsShouldBreak ignores the oracle; the fitsOnOneLine gate reads it.)
        ToIntFunction<String> methodRootWidth = text -> layoutWidth.nodeLine(methodRoot, text);
        if (promotedRootArgumentsShouldBreak(methodRoot, methodRootWidth, LayoutContext.root())) {
            return Optional.of(prefix + "(");
        }
        if (!sourceShapePolicy.fitsOnOneLine(methodRoot, methodRootWidth)) {
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
     * <p>{@code layout} is threaded so the true continuation column ({@link LayoutContext#leftEdgePrefix()}) is
     * available at this flat-gate. It is NOT consulted here: the opener-fit decision uses the fixed-budget
     * {@code lineWidth.applyAsInt(…)} floor (the statement/field callers pass an empty-prefix context).
     */
    private Optional<Doc> expressionLambdaRootWithSingleSegment(
            MethodCallExpr methodRoot,
            MethodCallExpr call,
            MethodCallChainTail finalSegmentSuffix,
            ToIntFunction<String> lineWidth,
            LayoutContext layout
    ) {
        if (
            !hasSingleExpressionLambdaArgument(methodRoot)
            || sourceShapePolicy.hasContainedComments(methodRoot)
            || !methodCallSegmentHasNoOwnContainedComments(call)
            || chainComments.methodCallSegmentHasComment(call)
            || methodCallSegmentHasBlockLambdaArgument(call)
        ) {
            return Optional.empty();
        }
        String prefix = calls.methodCallPrefix(methodRoot);
        if (lineWidth.applyAsInt(prefix + "(") > options.lineWidth()) {
            return Optional.empty();
        }
        return Optional.of(
            Doc.concat(
                Doc.text(prefix + "("),
                Doc.indent(Doc.concat(Doc.HARD_LINE, expressionRenderer.format(methodRoot.getArgument(0), LayoutContext.root()))),
                Doc.HARD_LINE,
                Doc.text(")"),
                methodCallChainSegmentAttachedToRootClose(call, finalSegmentSuffix, lineWidth)
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
            && !sourceShapePolicy.hasContainedComments(lambdaExpr);
    }

    private boolean methodCallSegmentHasBlockLambdaArgument(MethodCallExpr expression) {
        return expression.getArguments()
                .stream()
                .anyMatch(argument -> argument instanceof LambdaExpr lambdaExpr
                        && lambdaExpr.getBody().isBlockStmt()
                );
    }

    private boolean canBreakAfterCompactExpressionLambdaRoot(
            MethodCallBreakMode breakMode,
            Expression root,
            List<MethodCallExpr> calls,
            LayoutContext layout
    ) {
        if (
            !breakMode.isForced()
            || calls.size() != 1
            || !(root instanceof MethodCallExpr methodRoot)
            || !methodCallSegmentHasExpressionLambdaArgument(methodRoot)
            || !methodCallSegmentHasNoOwnContainedComments(calls.getFirst())
            || chainComments.methodCallSegmentHasComment(calls.getFirst())
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
     * <p>{@code layout} is read here: when a caller threads a same-line prefix through
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
                // Rangeless (synthetic) fallback measures at the rendered column, mirroring the wider-of arm's
                // nodeIndentWidth term, instead of the fixed one-indent baseline.
                .orElseGet(() -> layoutWidth.nodeIndentWidth(root) + text.length());
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
        // The fixed one-indent term was OR-dominated — rootLineWidth is never smaller than
        // nodeIndentWidth(root) + compact.length(), which already dominates the one-indent baseline — so the
        // rendered-column comparison alone yields the identical verdict.
        return rootLineWidth(expression, compact, layout) > options.lineWidth();
    }

    /**
     * The true-column width oracle for a fanned chain selector's expression-lambda hug: the selector's rendered
     * continuation column ({@link LayoutWidth#nodeIndentWidth} — the enclosing type/block indentation — plus the two
     * continuation units the fan applies, the same {@code nodeIndentWidth(chain) + indentUnit * 2} column
     * {@link ExpressionLambdaArgumentLayout} measures the hugged body at), widened with {@code Math.max} against the
     * fixed budget the caller already threads so it is monotone (it can only ever measure the hug WIDER, never relax a
     * break, so it cannot introduce a new over-width and stays a pure function of the AST). This corrects the fixed
     * three-unit continuation budget's ({@link LayoutWidth#continuationStatement}) one-level under-count for a chain
     * nested below a top-level statement, so the lambda-hug admission gate sees the selector's real overflow. It still
     * under-counts a selector
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

    private Doc methodCallChainSegment(MethodCallExpr expression) {
        return methodCallChainSegment(expression, false);
    }

    private Doc methodCallChainSegment(MethodCallExpr expression, MethodCallChainTail finalSegmentSuffix) {
        return methodCallChainSegment(expression, Optional.empty(), finalSegmentSuffix);
    }

    private Doc methodCallChainSegment(MethodCallExpr expression, boolean reserveStatementTerminator) {
        return methodCallChainSegment(expression, reserveStatementTerminator, layoutWidth::continuationStatement);
    }

    private Doc methodCallChainSegmentAttachedToRootClose(
            MethodCallExpr expression,
            MethodCallChainTail finalSegmentSuffix
    ) {
        return methodCallChainSegmentAttachedToRootClose(
            expression,
            finalSegmentSuffix,
            layoutWidth::currentIndented
        );
    }

    private Doc methodCallChainSegmentAttachedToRootClose(
            MethodCallExpr expression,
            MethodCallChainTail finalSegmentSuffix,
            ToIntFunction<String> lineWidth
    ) {
        // Measure the segment at the continuation column of the root's closing line (the {@code ")" + segment} closure),
        // not the beside-a-token source column. This segment attaches to the broken root's {@code )} on its continuation
        // line ({@code ).thenReturn(arg)}), so its argument-break gate must use that rendered column; the default
        // source-column estimate reads the author's shape and flips the segment's argument list between broken and
        // collapsed across passes (the {@code when(...).thenReturn(...)} family).
        return methodCallChainSegment(
            expression,
            Optional.empty(),
            finalSegmentSuffix,
            segment -> lineWidth.applyAsInt(")" + segment),
            true
        );
    }

    private ToIntFunction<String> objectRootSegmentWidth(
            ObjectCreationExpr root,
            MethodCallChainSourcePlanner.ChainRootRendering rootRendering,
            ToIntFunction<String> lineWidth,
            ToIntFunction<String> firstLineWidth
    ) {
        if (!objectRootUsesCompactLine(root, rootRendering)) {
            return segment -> lineWidth.applyAsInt(")" + segment);
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
            ToIntFunction<String> compactSegmentWidth
    ) {
        if (
            sourceShapePolicy.hasContainedComments(expression)
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
            ToIntFunction<String> lineWidth,
            ToIntFunction<String> firstLineWidth,
            LayoutContext layout
    ) {
        // An inter-segment {@code //} line comment attached as the sole
        // selector's LEADING comment ({@code new X(...)}⏎{@code // note}⏎{@code .selector(...)}) must render on the
        // comment-preserving exploded path — the constructor broken open, the selector re-emitting its leading comment on
        // its own continuation line — regardless of the author's line breaks. The verdict keys purely on comment presence
        // ({@link ChainCommentLayout#methodCallSegmentHasLeadingLineComment}, a structural fact), never on the author's line breaks, so both
        // passes fan identically and the shape is a one-pass fixpoint. Keying this on the author's line breaks instead
        // of comment presence would let a comment-on-its-own-line source fall through to the width-driven compact-glued
        // shape below ({@code new X(...)// note}⏎{@code .selector(...)}), which re-attaches the comment as the ROOT's
        // trailing comment on the next pass and explodes — an attach⇄explode oscillation this keying avoids. The
        // segment renderer claims the leading comment exactly once and {@code brokenObjectCreationRenderer} renders a
        // comment-free constructor, so no comment is double-claimed or dropped (guarded by CommentPresenceDiagnosticTest).
        if (chainComments.methodCallSegmentHasLeadingLineComment(call)) {
            return Doc.concat(
                brokenObjectCreationRenderer.apply(objectCreation),
                chainContinuation(methodCallChainSegment(call, Optional.empty(), finalSegmentSuffix))
            );
        }
        ToIntFunction<String> compactSegmentWidth = objectRootSegmentWidth(
            objectCreation,
            rootRendering,
            lineWidth,
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
            // This dot-break applies to every caller. A statement chain
            // ({@code new ProfileRequest(...).submit(10);}) reaches this branch once
            // {@link #refuseOpeningSingleSimpleObjectRootChainTail} declines the arg-opening compact shape, and wants the
            // same {@code new ProfileRequest(...)}⏎{@code .submit(10)} shape, not the arg-opened
            // {@code .submit(}⏎{@code 10}⏎{@code )}. The choice is a pure function of the AST (single simple argument) and
            // the width probe above, so it is a fixpoint regardless of the leading prefix.
            if (segmentWidth.singleSimpleMethodCallSegmentArgument(call)) {
                return Doc.concat(
                    rootDoc,
                    objectRootContinuation(methodCallChainSegment(call, Optional.empty(), finalSegmentSuffix))
                );
            }
            // The tail selector fans onto its own continuation line, so measure it THERE
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
                    layoutWidth::continuationStatement,
                    true
                ))
            );
        }
        Optional<Doc> compactAttachedSegment = compactAttachedObjectRootSingleSegment(
            rootDoc,
            call,
            finalSegmentSuffix,
            compactSegmentWidth
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
                calls.emptyMethodCallArguments(prefix, expression);
            if (commentedArguments.isPresent()) {
                return Doc.concat(segmentPrefix, commentedArguments.orElseThrow(), finalSegmentSuffix.doc());
            }
            return Doc.concat(segmentPrefix, Doc.text(prefix + "()" + finalSegmentSuffix));
        }
        Optional<Doc> sourceMultilineArguments = sourceMultilineMethodCallSegmentArguments(prefix, expression, finalSegmentSuffix);
        if (sourceMultilineArguments.isPresent()) {
            return Doc.concat(segmentPrefix, sourceMultilineArguments.orElseThrow());
        }
        Optional<Doc> huggableLambda =
            huggableBlockLambdaArguments.apply(prefix, expression.getArguments());
        if (huggableLambda.isPresent()) {
            return Doc.concat(segmentPrefix, huggableLambda.orElseThrow(), finalSegmentSuffix.doc());
        }
        Optional<Doc> commentedExpressionLambda =
            commentedExpressionLambdaArgument.apply(prefix, expression);
        if (commentedExpressionLambda.isPresent()) {
            return Doc.concat(segmentPrefix, commentedExpressionLambda.orElseThrow(), finalSegmentSuffix.doc());
        }
        Optional<Doc> huggedCommentedExpressionLambda = chainSelectorLambda.huggedCommentCarryingExpressionLambdaSegment(prefix, expression, finalSegmentSuffix);
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
        Optional<Doc> sourceNeutralExpressionLambda = chainSelectorLambda.sourceNeutralExpressionLambdaSegment(
            prefix,
            expression,
            segmentPrefix,
            finalSegmentSuffix,
            segmentOnOwnLine,
            compactSegmentWidth
        );
        if (sourceNeutralExpressionLambda.isPresent()) {
            return sourceNeutralExpressionLambda.orElseThrow();
        }
        Optional<Doc> commentedArguments = commentedExpressionLists.parenthesized(prefix, expression, expression.getArguments());
        if (commentedArguments.isPresent()) {
            return Doc.concat(segmentPrefix, commentedArguments.orElseThrow(), finalSegmentSuffix.doc());
        }
        String compactSegment = prefix
            + "("
            + segmentWidth.methodCallSegmentArgumentsWidthText(expression.getArguments())
            + ")"
            + finalSegmentSuffix;
        if (segmentWidth.methodCallSegmentArgumentsShouldBreak(
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

    /**
     * A no-op stub: a chain segment's argument list breaks by width rather than being preserved in its authored
     * multi-line shape, so this always returns empty. Retained so the candidate-ladder dispatch in
     * {@link #methodCallChainSegment} stays wired.
     */
    private Optional<Doc> sourceMultilineMethodCallSegmentArguments(
            String prefix,
            MethodCallExpr expression,
            MethodCallChainTail finalSegmentSuffix
    ) {
        return Optional.empty();
    }

    private Doc methodCallSegmentPrefix(MethodCallExpr expression) {
        List<JavaCommentTrivia> leadingComments = chainComments.leadingLineCommentsBeforeSegment(expression);
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
        Doc interspersedOrphans = chainComments.interspersedOrphanCommentsBeforeSelector(expression);
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
                    layoutWidth::continuationStatement,
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
        return methodCallChainSegment(expression, nextCall, finalSegmentSuffix, layoutWidth::continuationStatement);
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
                .map(next -> chainComments.trailingLineCommentBeforeNextSegment(expression, Optional.of(next)))
                .orElseGet(() -> chainComments.finalTrailingLineComment(expression));
        if (trailingComment == Doc.EMPTY) {
            return segment;
        }
        return Doc.concat(segment, Doc.lineSuffix(Doc.concat(Doc.text(" "), trailingComment)));
    }

    private Doc appendFinalSegmentSuffix(Doc doc, MethodCallChainTail finalSegmentSuffix) {
        return finalSegmentSuffix.appendTo(doc);
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
