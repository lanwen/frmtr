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
import com.github.javaparser.ast.nodeTypes.NodeWithArguments;
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

    private final BiFunction<String, NodeList<Expression>, Optional<Doc>> explodedMethodChainBlockLambdaArgument;

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

    private final ExpressionLambdaArgumentLayout.ExpressionLambdaMethodCallChainBodyFan expressionLambdaMethodCallChainBodyFan;

    private final ChainSelectorLambdaLayout chainSelectorLambda;

    private final ChainSegmentWidthLayout segmentWidth;

    private final ChainCommentLayout chainComments;

    private final ChainFanLayout chainFan;

    private final ChainSegmentRenderer segmentRenderer;

    private final ChainRootPromotionLayout rootPromotion;

    private final CompactRootBrokenSegmentLayout compactRootBrokenSegment;

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
            BiFunction<String, NodeList<Expression>, Optional<Doc>> explodedMethodChainBlockLambdaArgument,
            BiFunction<String, NodeList<Expression>, Optional<String>> huggableBlockLambdaFirstLine,
            BiFunction<String, MethodCallExpr, Optional<Doc>> commentedExpressionLambdaArgument,
            ExpressionLambdaArgumentLayout.HuggableExpressionLambdaArguments huggableExpressionLambdaArguments,
            ExpressionLambdaArgumentLayout.PlanFactory expressionLambdaArgumentPlan,
            Function<LambdaExpr, Optional<Doc>> huggedGapCommentedLambdaBody,
            Function<LambdaExpr, String> lambdaParameters,
            ExpressionLambdaArgumentLayout.ExpressionLambdaMethodCallBodyOpener expressionLambdaMethodCallBodyOpener,
            ExpressionLambdaArgumentLayout.ExpressionLambdaObjectCreationBodyOpener expressionLambdaObjectCreationBodyOpener,
            ExpressionLambdaArgumentLayout.ExpressionLambdaLogicalBinaryBodyOpenerHug expressionLambdaLogicalBinaryBodyOpenerHug,
            ExpressionLambdaArgumentLayout.ExpressionLambdaMethodCallChainBodyFan expressionLambdaMethodCallChainBodyFan
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
        this.explodedMethodChainBlockLambdaArgument = explodedMethodChainBlockLambdaArgument;
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
        this.expressionLambdaMethodCallChainBodyFan = expressionLambdaMethodCallChainBodyFan;
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
            this::brokenMethodCallSegment,
            expressionLambdaMethodCallChainBodyFan
        );
        this.segmentWidth = new ChainSegmentWidthLayout(
            options,
            compactSource::compactWithoutOwnComment,
            methodChainPlanner::promotesFirstCall
        );
        this.chainComments = new ChainCommentLayout(comments, commentPlacement, commentedExpressionLists);
        this.segmentRenderer = new ChainSegmentRenderer(
            types,
            calls,
            compactSource,
            layoutWidth,
            options,
            sourceShapePolicy,
            segmentWidth,
            chainComments,
            comments,
            commentedExpressionLists,
            chainSelectorLambda,
            methodChainPlanner,
            huggableBlockLambdaArguments,
            explodedMethodChainBlockLambdaArgument,
            commentedExpressionLambdaArgument,
            this::methodCallChainAnalysis,
            this::chainBreaksByRule
        );
        this.rootPromotion = new ChainRootPromotionLayout(
            calls,
            types,
            compactSource,
            layoutWidth,
            options,
            sourceShapePolicy,
            methodChainPlanner,
            commentedExpressionLists,
            segmentRenderer,
            expressionRenderer,
            brokenObjectCreationRenderer,
            this::chainContinuation,
            this::softChainContinuation,
            this::methodCallSegmentHasBlockLambdaArgument,
            this::rootLineWidth
        );
        this.compactRootBrokenSegment = new CompactRootBrokenSegmentLayout(
            segmentWidth,
            options,
            types,
            compactSource,
            layoutWidth,
            calls,
            sourceShapePolicy,
            segmentRenderer,
            huggableBlockLambdaArguments,
            huggableBlockLambdaFirstLine,
            expressionLambdaArgumentPlan
        );
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
            this::lambdaBodyChainContinuation,
            this::lambdaBodyChainContinuation,
            this::methodCallChainSegments,
            this::rootLineWidth
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
     * Reports whether a chain fans by WIDTH rather than the author's line breaks — a trivial-receiver two- or
     * three-selector chain, or an enclosed/cast-rooted fanning chain. Delegates to {@link ChainFanLayout}.
     */
    private boolean chainIsWidthDrivenFan(MethodCallChainSourcePlanner.MethodCallChainAnalysis analysis) {
        return chainFan.chainIsWidthDrivenFan(analysis);
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

    /**
     * Fan doc for a call-rooted width-driven two-selector chain in a lambda-body position. Applies the same
     * carve-outs as the width-driven block: comment-free, no block-lambda arguments, no segment comments.
     * Scoped to {@code MethodCallExpr} roots only — trivial-receiver chains keep their existing paths.
     */
    Optional<Doc> widthDrivenLambdaBodyFanChain(MethodCallExpr expression) {
        if (expression.getScope().isEmpty()) {
            return Optional.empty();
        }
        MethodCallChainSourcePlanner.MethodCallChainAnalysis analysis = methodCallChainAnalysis(expression);
        if (
            !(analysis.root() instanceof MethodCallExpr)
            || !chainFan.chainIsWidthDrivenFan(analysis)
            || analysis.hasComments()
            || analysis.calls().stream().anyMatch(chainComments::methodCallSegmentHasComment)
        ) {
            return Optional.empty();
        }
        return Optional.of(chainFanOut(analysis.root(), analysis.calls(), MethodCallChainTail.EMPTY, LayoutContext.root()));
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

    // The terminator-threading forced entry: renders the chain with {@code tail} (the caller's same-line {@code ;})
    // folded into the LAST segment, so the width-driven fit-or-fan verdict counts the terminator. A caller whose chain
    // owns its statement terminator threads it here rather than appending it outside the chain doc, so a chain whose flat
    // form fits at exactly the column but overflows once the {@code ;} lands fans instead of keeping an over-width line.
    Optional<Doc> forcedMethodCallChain(
            MethodCallExpr expression,
            MethodCallChainTail tail,
            ToIntFunction<String> firstLineWidth,
            LayoutContext layout
    ) {
        return methodCallChain(
            expression,
            MethodCallBreakMode.FORCED,
            tail,
            layoutWidth::currentIndented,
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
        if (!breakMode.isForced() && chainRootIsTrivialReceiver(expression)) {
            Optional<Doc> rankedFinalBlockLambdaSegment = rankedFinalBlockLambdaSegmentHug(expression, finalSegmentSuffix);
            if (rankedFinalBlockLambdaSegment.isPresent()) {
                return rankedFinalBlockLambdaSegment;
            }
        } else if (
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
                // A trivial-receiver three-selector chain ({@link ChainFanLayout#chainIsWidthDrivenFan}) picks flat
                // vs. fan via a {@code conditionalGroup} at the real rendered column. The flat probe below is
                // column-blind (e.g. unaware of a lambda-body position), so skip it and let the
                // {@code conditionalGroup} decide. Two-selector chains are exempt: they predate this change and pass
                // through the flat probe correctly in the contexts that existed before.
                && !(chainRootIsTrivialReceiver(expression) && analysis.calls().size() == 3)
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
                && !forcedSingleCallPrefixOverflows(breakMode, expression, firstLineWidth)
                && !(breakMode.isForced() && root instanceof ObjectCreationExpr)
                && !rootObjectCreationNeedsBreak
                // A block-bodied lambda in the object-creation ROOT of a single-selector chain
                // ({@code new Observer(x -> {…}).run()}) must fan open, not collapse flat. Keyed on the root only: a
                // block lambda in the SELECTOR ({@code runner.attach(x -> {…})}) still hugs through the flat render.
                && !analysis.rootHasBlockLambdaArgument()
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
        // The two chain families the canonical link-count/root-kind rule does NOT claim — a trivial-receiver two- or
        // three-selector chain and an enclosed/cast-rooted fanning chain ({@link #chainIsWidthDrivenFan}) — fan by WIDTH, not
        // by the author's line breaks. Route them through a WIDTH-driven {@link Doc#bestFitting}: the flat compact form
        // when it fits, the source-neutral {@code chainFanOut} on overflow. The fan arm is a pure function of the AST (root
        // + one selector per dotted line), so both passes rebuild the identical fan — a fixpoint. The enclosed/cast family
        // is ALWAYS-FAN, not bestFitting: its {@code fanRootDoc} renders the enclosed/cast root at {@code root()} (column
        // zero), so a flat arm measured at the real column would flip flat<->fan against the fan arm's column-zero render
        // and oscillate; an enclosed fanning root also already spans lines, so the flat arm can never win. Block-lambda
        // selectors are admitted: {@code bodyForcesMultiline} below routes them to {@link Doc#bestFitting} (not
        // {@link Doc#conditionalGroup}), which correctly fans when the flat compact overflows. Same last-selector-trailing-
        // line relaxation ({@code chainCommentsAreOnlyTrailingLine}) as the canonical route: admitted but forced to fan.
        if (
            chainIsWidthDrivenFan(analysis)
            && (!analysis.hasComments() || chainCommentsAreOnlyTrailingLine(analysis))
            && calls.stream().noneMatch(chainComments::methodCallSegmentHasComment)
        ) {
            chainWidthBreakExplain.record(expression, analysis, layout);
            // A plain trivial-receiver two-selector chain whose SHORT final selector wraps one breakable call/creation
            // argument attaches that opener and breaks the argument ONE indent deep, rather than fanning the outer chain
            // and pushing the argument two indents deeper. Comment-free and short-name-gated so the fan-only comment
            // shapes and longer selectors keep the fan; declines (falls through to the fan) when the opener overflows.
            if (
                !rootIsEnclosedFanningChain(root)
                && calls.size() == 2
                && calls.getFirst() instanceof MethodCallExpr
                && !analysis.hasComments()
                && !sourceShapePolicy.hasContainedComments(expression)
                && finalSegmentAttachesShortBreakingCallArgument(calls.getLast())
            ) {
                Optional<Doc> attached = compactRootWithBrokenFinalSegment(
                    calls.getFirst(), calls.getLast(), finalSegmentSuffix, lineWidth, layout);
                if (attached.isPresent()) {
                    return attached;
                }
            }
            Doc fanOut = chainFanOut(root, calls, finalSegmentSuffix, layout);
            if (rootIsEnclosedFanningChain(root)) {
                return Optional.of(fanOut);
            }
            // Three-selector trivial-receiver chains in forced (lambda-body) position always fan — the
            // width-driven flat/fan choice would stay flat at shallow lambda-body columns, but lambda-body
            // position warrants the stable canonical shape (matching lambdaBodyChainFansByCanonicalRule).
            if (breakMode.isForced() && calls.size() == 3) {
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
            // every chain WHOSE FLAT FORM CAN ACTUALLY FIT. bestFitting ranks by rendered line count with a fewest-lines
            // tie-break, so when BOTH arms overflow it picks the single flat line, jamming the whole body onto one 300+
            // column line. A conditionalGroup chooses the flat compact ONLY when it genuinely fits flat at the real
            // column and otherwise renders the fan in break mode. For the case where the flat arm fits, both combinators
            // agree, and the conditionalGroup is strictly better when the flat arm overflows.
            //
            // A nested-lambda body ({@link #lambdaArgumentForcesMultilineBody}) normally keeps the {@link Doc#bestFitting}
            // arm (rendered collapsed): its flat compact never fits, so a conditionalGroup would fan the receiver every
            // pass while the deferred lambda-arrow renderer still shapes the body, and the two oscillate. The exception is
            // a body that is ITSELF a source-neutral fanning chain ({@code lambdaBodyChainFansByCanonicalRule}) — its arrow
            // shape is decided source-neutrally, so a conditionalGroup stays stable AND fans in break mode on overflow
            // instead of collapsing a deep reactive pipeline onto one runaway flat line (what bestFitting picks when both
            // arms overflow: its fewest-lines tie-break takes the single flat arm).
            List<Doc> arms = List.of(flat, fanOut);
            boolean bodyForcesMultiline = calls.getLast().getArguments().stream()
                    .anyMatch(this::lambdaArgumentForcesMultilineBody);
            boolean bodyIsSourceNeutralFanningChain = calls.getLast().getArguments().stream()
                    .filter(LambdaExpr.class::isInstance)
                    .map(LambdaExpr.class::cast)
                    .flatMap(lambda -> lambda.getExpressionBody().stream())
                    .anyMatch(body -> body instanceof MethodCallExpr call && lambdaBodyChainFansByCanonicalRule(call));
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
            boolean collapseArm = bodyForcesMultiline && !bodyIsSourceNeutralFanningChain;
            // A block-lambda body always breaks deterministically — the rendered shape is stable from the first
            // pass. Rank the inline chain header (up to the opening {@code {}) against the fan by first-line
            // fit; the body's width does not influence the selection, so the verdict is idempotent.
            if (collapseArm && methodCallSegmentHasBlockLambdaArgument(calls.getLast())) {
                String inlinePrefix = this.calls.methodCallPrefix(calls.getLast());
                Optional<Doc> inlineHug = this.calls.eligibleBlockLambdaHugCandidate(
                    inlinePrefix, calls.getLast().getArguments());
                if (inlineHug.isPresent()) {
                    Doc inlineArm = appendFinalSegmentSuffix(inlineHug.orElseThrow(), finalSegmentSuffix);
                    return Optional.of(Doc.bestFittingFirstLine(List.of(inlineArm, fanOut)));
                }
            }
            return Optional.of(collapseArm ? Doc.bestFitting(arms) : Doc.conditionalGroup(arms));
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
            // A terminal `.to("") // note` trails a line comment past the call's last token that the strictly-interior
            // contained-comment scan misses; this compact-root arm never emits it, so withhold and let the
            // comment-preserving fan/segment path below carry it.
            && chainComments.finalTrailingLineComments(calls.getFirst()).isEmpty()
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
                // A genuine between-selector `//` trailing the root on its own line hugs the root close as a line suffix
                // ({@code from(…) //}) while the sole selector still fans onto its own continuation line, whenever that
                // selector fits flat there. On reformat the marker again trails the root and precedes the fanned selector,
                // so the same receiver-line-suffix fixpoint recurs and both source geometries converge.
                if (fannedFinalSegmentFitsFlat(calls.getFirst(), finalSegmentSuffix)) {
                    return Optional.of(
                        Doc.concat(
                            rootDoc,
                            Doc.lineSuffix(Doc.concat(Doc.text(" "), rootTrailingComment)),
                            chainContinuation(methodCallChainSegment(calls.getFirst(), finalSegmentSuffix))
                        )
                    );
                }
                // Otherwise the selector's argument overflows: hugging the comment as a `.to( //` line suffix flips to an
                // argument-leading own-line comment on the reformat, since the collapsed opener re-buckets the marker
                // inside its parens. Render it own-line above the broken argument on this pass too, matching that
                // argument-leading fixpoint, so both source geometries converge.
                if (
                    compactRootFinalSegmentLineOverflows(
                        methodRoot,
                        calls.getFirst(),
                        finalSegmentSuffix,
                        lineWidth,
                        layout
                    )
                ) {
                    Optional<Doc> brokenWithLeadingComment = compactRootWithBrokenFinalSegment(
                        methodRoot,
                        calls.getFirst(),
                        finalSegmentSuffix,
                        lineWidth,
                        layout,
                        rootTrailingComment
                    );
                    if (brokenWithLeadingComment.isPresent()) {
                        return brokenWithLeadingComment;
                    }
                }
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
                        // Segment sits alone on its own continuation line, so measure it at the continuation column.
                        chainContinuation(methodCallChainSegment(
                            calls.getFirst(),
                            Optional.empty(),
                            finalSegmentSuffix,
                            layoutWidth::continuationStatement,
                            true
                        ))
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
                        // Segment sits alone on its own continuation line, so measure it at the continuation column.
                        chainContinuation(methodCallChainSegment(
                            calls.getFirst(),
                            Optional.empty(),
                            finalSegmentSuffix,
                            layoutWidth::continuationStatement,
                            true
                        ))
                    )
                );
            }
            // When the selector attached to the root close would overflow and the root carries no trailing comment,
            // rank the compact-broken and fan shapes so the renderer picks the shorter one at the real column.
            if (
                rootTrailingComment == Doc.EMPTY
                && compactRootFinalSegmentLineOverflows(
                    methodRoot, calls.getFirst(), finalSegmentSuffix, lineWidth, layout
                )
            ) {
                Optional<Doc> ranked = rankedSingleSegmentChain(
                    methodRoot,
                    calls.getFirst(),
                    finalSegmentSuffix,
                    MethodCallChainSourcePlanner.ChainRootRendering.EXPRESSION_RENDERER,
                    analysis,
                    lineWidth,
                    layout
                );
                if (ranked.isPresent()) {
                    return ranked;
                }
                // No compact-broken alternative exists (a no-arg selector, or one whose opener alone already
                // overflows), so the attach below has no way to shed columns. Rank it against the fan-out
                // so an attach that still overflows yields to the selector's own continuation line.
                Doc attach = Doc.concat(
                    rootDoc,
                    methodCallChainSegmentAttachedToRootClose(calls.getFirst(), finalSegmentSuffix, lineWidth)
                );
                Doc fanOut = chainFanOut(methodRoot, List.of(calls.getFirst()), finalSegmentSuffix, layout);
                return Optional.of(Doc.bestFitting(List.of(attach, fanOut)));
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
        Doc rootDoc = methodCallChainRootDoc(chainPlan, firstLineWidth, layout, !analysis.hasComments());
        // Track whether {@code rootDoc} is an EXPRESSION_RENDERER root the multi-segment fall-through below may route
        // through the shared {@link #chainFanOut} builder (which re-renders root and selectors from the AST, ranked
        // against a force-broken-root alternative for a multi-arg root — see the fall-through). A promoted/grouped/
        // broken-object-creation root, a first-segment-attached root, or a root-trailing-comment-wrapped root produces
        // a different {@code rootDoc} and stays on the inline construction.
        //
        // The comment gate is load-bearing: re-rendering the root a second time through {@code chainFanOut} (the
        // {@code rootDoc} built here is discarded in that path) is admitted comment-free, OR when the chain's only
        // comment is a last-selector trailing line comment ({@code chainCommentsAreOnlyTrailingLine}) — that relaxation
        // requires {@code !rootHasComments}, so the root re-renders to a byte-identical {@code Doc} and {@code chainFanOut}'s
        // {@code methodCallChainSegments} re-emits the last selector's trailing slot. Every other comment family keeps the
        // unchanged inline construction (rendered once); re-rendering the root through the fan would drop or destabilize a
        // root / segment / between-selector comment.
        boolean rootDocIsPlainExpressionRenderRoot =
            chainPlan.rootRendering() == MethodCallChainSourcePlanner.ChainRootRendering.EXPRESSION_RENDERER
            && (!analysis.hasComments() || chainCommentsAreOnlyTrailingLine(analysis));
        if (calls.isEmpty()) {
            return Optional.of(appendFinalSegmentSuffix(rootDoc, finalSegmentSuffix));
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
                        // The segment lands alone on its own continuation line below the comment-carrying root, so
                        // measure it at the continuation column.
                        chainContinuation(methodCallChainSegment(
                            calls.getFirst(),
                            Optional.empty(),
                            finalSegmentSuffix,
                            layoutWidth::continuationStatement,
                            true
                        ))
                    )
                );
            }
        }
        if (canKeepSuffixAttachedToPromotedBlockLambdaRoot(chainPlan, root, calls, finalSegmentSuffix)) {
            return Optional.of(
                Doc.concat(
                    rootDoc,
                    methodCallChainSegmentAttachedToBlockLambdaClose(calls.getFirst(), finalSegmentSuffix)
                )
            );
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
            && methodRootCanKeepSingleSuffixAttached(methodRoot)
            && methodCallSegmentHasNoOwnContainedComments(calls.getFirst())
            && !chainComments.methodCallSegmentHasComment(calls.getFirst())
        ) {
            if (
                chainPlan.rootRendering() == MethodCallChainSourcePlanner.ChainRootRendering.INLINE_PROMOTED_METHOD_CALL
                && promotedNoArgRootScopeOverflows(methodRoot, firstLineWidth)
            ) {
                // Segment sits alone on its own continuation line, so measure it at the continuation column.
                return Optional.of(
                    Doc.concat(rootDoc, chainContinuation(methodCallChainSegment(
                        calls.getFirst(),
                        Optional.empty(),
                        finalSegmentSuffix,
                        layoutWidth::continuationStatement,
                        true
                    )))
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
                        Doc.concat(
                            rootDoc,
                            methodCallChainSegmentAttachedToBlockLambdaClose(calls.getFirst(), finalSegmentSuffix)
                        )
                    );
                }
                return Optional.of(
                    groupedPromotedRootWithSingleSegment(rootDoc, calls.getFirst(), finalSegmentSuffix)
                );
            }
            // The renderer, not a source-width estimate, decides whether an EXPRESSION_RENDERER root attaches the
            // trailing segment to its close (broken root) or sits it beside a flat root: both complete Docs are built
            // and ranked once with bestFitting, so the true rendered column always wins.
            if (chainPlan.rootRendering() == MethodCallChainSourcePlanner.ChainRootRendering.EXPRESSION_RENDERER) {
                Doc flat = Doc.concat(
                    expressionRenderer.format(methodRoot, LayoutContext.root()),
                    methodCallChainSegment(
                        calls.getFirst(),
                        Optional.empty(),
                        finalSegmentSuffix,
                        attachedRootSegmentWidth(methodRoot, firstLineWidth),
                        true
                    )
                );
                Doc broken = Doc.concat(
                    this.calls.brokenMethodCall(methodRoot),
                    methodCallChainSegmentAttachedToRootClose(calls.getFirst(), finalSegmentSuffix, lineWidth)
                );
                return Optional.of(Doc.bestFitting(List.of(flat, broken)));
            }
            // Only an INLINE_PROMOTED_METHOD_CALL root reaches here (EXPRESSION_RENDERER ranked above,
            // GROUPED_PROMOTED_METHOD_CALL returned earlier); rootDoc's own broken/flat shape already came from
            // promotedMethodCallRoot's width check, so attach the segment at the column that shape actually renders
            // at: a broken root's close line ({@code IntStream.range(}⏎ args ⏎{@code )}) beside a source-width
            // estimate of the SAME multi-arg-overflow condition, or, flat, beside the compact root.
            Doc singleSegment =
                methodRoot.getArguments().size() > 1
                    && firstLineWidth.applyAsInt(compactSource.compactWithoutOwnComment(methodRoot)) > options.lineWidth()
                ? methodCallChainSegmentAttachedToRootClose(calls.getFirst(), finalSegmentSuffix, lineWidth)
                : methodCallChainSegment(
                    calls.getFirst(),
                    Optional.empty(),
                    finalSegmentSuffix,
                    attachedRootSegmentWidth(methodRoot, firstLineWidth),
                    true
                );
            return Optional.of(Doc.concat(rootDoc, singleSegment));
        }
        // A promoted static-factory root with exactly one post-factory selector whose ONLY comment is a trailing line
        // comment past the chain's last token ({@code Mockito.when(x).thenReturn(...); // note}) attaches the selector to
        // the root and breaks its argument list, matching the comment-free promoted-single-selector shape above. Without
        // this, JavaParser binding that statement-trailing comment to the chain forces the segment onto the one-per-line
        // fan, flipping against the comment-free re-format (where the same comment binds to the enclosing statement) forever.
        // The canonical {@code hasContainedComments} gate keeps this to chains with nothing strictly inside — an interior
        // argument comment stays on the comment-preserving fan. The comment rides as a placement-stable line suffix after
        // the closed selector, claimed once, so the statement renderer never re-emits it.
        if (
            root instanceof MethodCallExpr promotedFactoryRoot
            && calls.size() == 1
            && (chainPlan.rootRendering()
                    == MethodCallChainSourcePlanner.ChainRootRendering.GROUPED_PROMOTED_METHOD_CALL
                || chainPlan.rootRendering()
                    == MethodCallChainSourcePlanner.ChainRootRendering.INLINE_PROMOTED_METHOD_CALL)
            && analysis.hasComments()
            && chainCommentsAreOnlyTrailingLine(analysis)
            && !sourceShapePolicy.hasContainedComments(expression)
            && !analysis.hasBlockLambdaArgument()
            && !methodCallSegmentHasBlockLambdaArgument(calls.getFirst())
            && !sourceShapePolicy.hasContainedComments(promotedFactoryRoot)
            && compactRootFinalSegmentLineOverflows(
                promotedFactoryRoot,
                calls.getFirst(),
                finalSegmentSuffix,
                lineWidth,
                layout
            )
        ) {
            Optional<Doc> attach = compactRootWithBrokenFinalSegment(
                promotedFactoryRoot,
                calls.getFirst(),
                finalSegmentSuffix,
                lineWidth,
                layout
            );
            if (attach.isPresent()) {
                Doc trailing = chainComments.finalTrailingLineComment(calls.getFirst());
                return Optional.of(
                    trailing == Doc.EMPTY
                        ? attach.orElseThrow()
                        : Doc.concat(
                            attach.orElseThrow(),
                            Doc.lineSuffix(Doc.concat(Doc.text(" "), trailing))
                        )
                );
            }
        }
        // Record the width break only here, where the printer has committed to the broken one-segment-per-line chain
        // this method's PrinterWrap describes. The earlier deferral branches hand rendering to a different printer that
        // does not lay the chain out one per line, so recording before them could attribute a "N segments, one per line"
        // layout to a path that never produced it.
        chainWidthBreakExplain.record(expression, analysis, layout);
        // The multi-segment fall-through routes an EXPRESSION_RENDERER root through the shared source-neutral
        // {@code chainFanOut} builder — root then each selector on its own dotted continuation line — rather than
        // reconstructing that shape inline. A promoted/grouped/broken-object-creation root, a first-segment-attached
        // root, or a root-trailing-comment-wrapped root produces a different {@code rootDoc}, so those keep the
        // inline construction below instead.
        if (rootDocIsPlainExpressionRenderRoot) {
            // A multi-arg MethodCallExpr root may need to break; rank the fan (plain root, one selector per line)
            // against a force-broken root (lone selector attached to close; multiple selectors uniform), so the
            // renderer picks the best fit at the true column instead of a source-width estimate deciding up front.
            if (root instanceof MethodCallExpr rootCall && rootCall.getArguments().size() > 1) {
                Doc fanned = chainFanOut(root, calls, finalSegmentSuffix, layout);
                Doc inlineBroken = brokenRootChainWithAttachedFirstSegment(rootCall, calls, finalSegmentSuffix, lineWidth);
                return Optional.of(Doc.bestFitting(List.of(fanned, inlineBroken)));
            }
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
            && (!analysis.hasComments() || chainCommentsAreOnlyTrailingLine(analysis))
            && !analysis.hasBlockLambdaArgument()
            && calls.stream().noneMatch(chainComments::methodCallSegmentHasComment)
        ) {
            return Optional.of(chainFanOut(root, calls, finalSegmentSuffix, layout));
        }
        List<Doc> segments = methodCallChainSegments(calls, finalSegmentSuffix);
        Doc fanned = Doc.concat(rootDoc, chainContinuation(root, segments));
        Optional<Doc> blockLambdaHug =
            attachedBlockLambdaSelectorHug(analysis, root, calls, finalSegmentSuffix, lineWidth, layout);
        return Optional.of(
            blockLambdaHug
                    .map(hug -> Doc.bestFittingFirstLine(List.of(hug, fanned)))
                    .orElse(fanned)
        );
    }

    /**
     * The hug shape for a chain whose sole selector carries a block-lambda argument: the selector rides the root line and
     * the lambda body opens under it. Its rival fan drops that selector one line lower, which indents the same body one
     * level deeper, so ranking the two prices the body's real width instead of a compact projection a comment inflates.
     *
     * <p>Withheld for a chain-level comment or a comment inside the root, whose compact reconstruction here would mangle
     * it. A comment in the lambda body is safe: the hug renders that body through the block printer.
     */
    private Optional<Doc> attachedBlockLambdaSelectorHug(
            MethodCallChainSourcePlanner.MethodCallChainAnalysis analysis,
            Expression root,
            List<MethodCallExpr> calls,
            MethodCallChainTail finalSegmentSuffix,
            ToIntFunction<String> lineWidth,
            LayoutContext layout
    ) {
        if (
            calls.size() != 1
            || !methodCallSegmentHasBlockLambdaArgument(calls.getFirst())
            || analysis.hasComments()
            || sourceShapePolicy.hasContainedComments(root)
        ) {
            return Optional.empty();
        }
        return compactRootWithBrokenFinalSegment(
            root,
            calls.getFirst(),
            finalSegmentSuffix,
            lineWidth,
            layout
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

    /**
     * The fixed-probe decline kept for a non-trivial (method-call/object-creation) chain root: such a root has its own
     * promoted-fan alternative ({@link #rankedFinalBlockLambdaSegmentHug} only models hug-vs-exploded, not the fan), so
     * ranking it here would starve that richer shape of a candidate it can win against.
     */
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
     * Ranks the sole selector's block-lambda hug against {@code MethodCallPrinter}'s real exploded-argument-list
     * fallback at the true rendered column, for a TRIVIAL chain root ({@link #chainRootIsTrivialReceiver}) only — a
     * non-trivial root has its own promoted/fan alternative this narrow ranking does not model (see
     * {@link #finalBlockLambdaSegmentCanStayCompact}, kept for that case). Declines (empty) exactly where the old
     * estimate always declined too — no block-lambda argument, a comment, a heavy argument list, or a structurally
     * ineligible hug — so those cases fall through unchanged to the chain-shape ladder below.
     */
    private Optional<Doc> rankedFinalBlockLambdaSegmentHug(
            MethodCallExpr expression,
            MethodCallChainTail finalSegmentSuffix
    ) {
        if (!methodCallSegmentHasBlockLambdaArgument(expression) || chainComments.methodCallSegmentHasComment(expression)) {
            return Optional.empty();
        }
        String prefix = calls.methodCallPrefix(expression);
        NodeList<Expression> arguments = expression.getArguments();
        Optional<Doc> hug = calls.eligibleBlockLambdaHugCandidate(prefix, arguments);
        if (hug.isEmpty()) {
            return Optional.empty();
        }
        Doc exploded = calls.explodedArgumentList(prefix, arguments, "", MethodCallBreakMode.AUTO);
        Doc ranked = Doc.bestFittingFirstLine(List.of(hug.orElseThrow(), exploded), new int[] {1, 0});
        return Optional.of(Doc.followedBy(ranked, finalSegmentSuffix.doc()));
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
    /**
     * Whether the sole selector fits flat on its own fanned continuation line ({@code nodeIndentWidth + two units}), so a
     * between-selector comment can keep it fanned rather than break its argument list. Reads only the AST (compact-joined
     * arguments at the selector's rendered depth), so the verdict is source-neutral and stable across passes; a selector
     * carrying its own interior comments defers, since compact-joining would not reflect them.
     */
    private boolean fannedFinalSegmentFitsFlat(MethodCallExpr call, MethodCallChainTail finalSegmentSuffix) {
        if (
            call.getArguments().stream().anyMatch(LambdaExpr.class::isInstance)
        ) {
            return false;
        }
        String typeArguments = call.getTypeArguments()
                .map(arguments -> "<" + types.compactJoinTypeLike(arguments) + ">")
                .orElse("");
        String segment = "."
            + typeArguments
            + call.getNameAsString()
            + "("
            + compactSource.compactJoin(call.getArguments())
            + ")"
            + finalSegmentSuffix;
        int continuationColumn = layoutWidth.nodeIndentWidth(call) + options.indentUnit().length() * 2;
        return continuationColumn + segment.length() <= options.lineWidth();
    }

    private boolean compactRootFinalSegmentLineOverflows(
            MethodCallExpr methodRoot,
            MethodCallExpr call,
            MethodCallChainTail finalSegmentSuffix,
            ToIntFunction<String> lineWidth,
            LayoutContext layout
    ) {
        return compactRootBrokenSegment.compactRootFinalSegmentLineOverflows(
            methodRoot,
            call,
            finalSegmentSuffix,
            lineWidth,
            layout
        );
    }

    private Optional<Doc> brokenRootWithAttachedFinalSegment(
            MethodCallExpr methodRoot,
            MethodCallExpr call,
            MethodCallChainTail finalSegmentSuffix,
            ToIntFunction<String> lineWidth,
            LayoutContext layout
    ) {
        return compactRootBrokenSegment.brokenRootWithAttachedFinalSegment(
            methodRoot,
            call,
            finalSegmentSuffix,
            lineWidth,
            layout
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
     * The dot-split of a single-selector chain: the root, then the sole selector on its own dotted continuation line
     * ({@code root()}⏎{@code .selector(args)}), through the source-neutral {@link #chainFanOut}. The initializer layout
     * ranks this against its opener-attach shape so the dot-split is kept only when the selector's arguments fit on one
     * continuation line.
     */
    Doc singleSelectorDotSplit(MethodCallExpr call, LayoutContext layout) {
        return chainFanOut(call.getScope().orElseThrow(), List.of(call), MethodCallChainTail.EMPTY, layout);
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

    /**
     * Reports whether a chain root is a parenthesized (or parenthesized-cast) expression wrapping a fan-threshold
     * method-call chain (its inner chain fans by the canonical rule). Delegates to {@link ChainFanLayout}.
     */
    private boolean rootIsEnclosedFanningChain(Expression root) {
        return chainFan.rootIsEnclosedFanningChain(root);
    }

    private String methodCallSegmentPrefixText(MethodCallExpr expression) {
        return "."
            + expression.getTypeArguments()
                    .map(arguments -> "<" + types.compactJoinTypeLike(arguments) + ">")
                    .orElse("")
            + expression.getNameAsString();
    }

    /**
     * Measures the bare selector prefix through the caller's {@code firstLineWidth} probe rather than a fixed budget,
     * so a same-line prefix a caller threads (an initializer's {@code NAME = }) counts toward the overflow verdict at
     * the true rendered column instead of being invisible to it.
     */
    private boolean forcedSingleCallPrefixOverflows(
            MethodCallBreakMode breakMode,
            MethodCallExpr expression,
            ToIntFunction<String> firstLineWidth
    ) {
        return breakMode.isForced()
            && expression.getScope().isPresent()
            && methodCallSegmentHasBlockLambdaArgument(expression)
            && firstLineWidth.applyAsInt(calls.methodCallPrefix(expression) + "(") > options.lineWidth();
    }

    private Doc methodCallChainRootDoc(
            MethodCallChainSourcePlanner.MethodCallChainPlan chainPlan,
            ToIntFunction<String> firstLineWidth,
            LayoutContext layout,
            boolean chainIsCommentFree
    ) {
        return rootPromotion.methodCallChainRootDoc(chainPlan, firstLineWidth, layout, chainIsCommentFree);
    }

    private Doc singleSegmentMethodRootDoc(MethodCallExpr methodRoot) {
        return rootPromotion.singleSegmentMethodRootDoc(methodRoot);
    }

    private Doc groupedPromotedMethodCall(MethodCallExpr expression) {
        return rootPromotion.groupedPromotedMethodCall(expression);
    }

    private Doc groupedPromotedRootWithSingleSegment(
            Doc rootDoc,
            MethodCallExpr expression,
            MethodCallChainTail finalSegmentSuffix
    ) {
        return rootPromotion.groupedPromotedRootWithSingleSegment(rootDoc, expression, finalSegmentSuffix);
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
    private Optional<Doc> compactRootWithBrokenFinalSegment(
            Expression root,
            MethodCallExpr call,
            ToIntFunction<String> lineWidth,
            LayoutContext layout
    ) {
        return compactRootBrokenSegment.compactRootWithBrokenFinalSegment(root, call, lineWidth, layout);
    }

    private Optional<Doc> compactRootWithBrokenFinalSegment(
            Expression root,
            MethodCallExpr call,
            MethodCallChainTail finalSegmentSuffix,
            ToIntFunction<String> lineWidth,
            LayoutContext layout
    ) {
        return compactRootBrokenSegment.compactRootWithBrokenFinalSegment(root, call, finalSegmentSuffix, lineWidth, layout);
    }

    private Optional<Doc> compactRootWithBrokenFinalSegment(
            Expression root,
            MethodCallExpr call,
            MethodCallChainTail finalSegmentSuffix,
            ToIntFunction<String> lineWidth,
            LayoutContext layout,
            Doc argumentLeadingComment
    ) {
        return compactRootBrokenSegment.compactRootWithBrokenFinalSegment(
            root,
            call,
            finalSegmentSuffix,
            lineWidth,
            layout,
            argumentLeadingComment
        );
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

    // Continuation at ONE indent level (+4) rather than {@link #chainContinuation}'s two (+8). A lambda-body fan offers
    // this as a width-safe alternative so a nested hop that would overflow at +8 drops to +4; the renderer picks between
    // them at the true column, so shallow chains that fit at +8 keep it.
    private Doc lambdaBodyChainContinuation(Doc doc) {
        return Doc.indent(Doc.concat(Doc.HARD_LINE, doc));
    }

    private Doc chainContinuation(Expression root, List<Doc> segments) {
        return rootChainContinuation(root, segments, this::chainContinuation);
    }

    /**
     * The broken-root chain shape: the root force-broken one-argument-per-line. A lone selector attaches to the
     * close, matching the single-segment site's shape. Two or more selectors instead continue at the SAME dotted
     * continuation indent (the shape {@link #chainFanOut} produces) — attaching only the first to the close while
     * the rest sit one level deeper is a stairstep no source shape would ever author.
     */
    private Doc brokenRootChainWithAttachedFirstSegment(
            MethodCallExpr root,
            List<MethodCallExpr> calls,
            MethodCallChainTail finalSegmentSuffix,
            ToIntFunction<String> lineWidth
    ) {
        Doc brokenRoot = this.calls.brokenMethodCall(root);
        if (calls.size() == 1) {
            return Doc.concat(brokenRoot, methodCallChainSegmentAttachedToRootClose(calls.getFirst(), finalSegmentSuffix, lineWidth));
        }
        return Doc.concat(brokenRoot, chainContinuation(root, methodCallChainSegments(calls, finalSegmentSuffix)));
    }

    private Doc lambdaBodyChainContinuation(Expression root, List<Doc> segments) {
        return rootChainContinuation(root, segments, this::lambdaBodyChainContinuation);
    }

    // Root-anchored continuation shared by both indent variants: a short root (shorter than one indent unit) pads its
    // selectors to align under it, which is already a single indent; a normal root defers to {@code continuation}, the
    // only place the +4 and the +8 differ.
    private Doc rootChainContinuation(Expression root, List<Doc> segments, Function<Doc, Doc> continuation) {
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
        return continuation.apply(Doc.join(Doc.HARD_LINE, segments));
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
            this::expressionHasBlockLambdaArgument,
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

    /**
     * Whether a chain root or selector carries a block-bodied lambda argument, spanning both method-call and
     * object-creation roots ({@code new X(state -> {...})}) so a constructor-rooted chain fans like its call-rooted twin
     * instead of collapsing flat.
     */
    private boolean expressionHasBlockLambdaArgument(Expression expression) {
        return expression instanceof NodeWithArguments<?> withArguments
                && withArguments.getArguments()
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
     * Measures a chain root's compact form at the column where it renders, feeding the compact-root break decision
     * and the trivial-receiver first-selector attach fit check ({@link ChainFanLayout#firstSelectorAttachesFlat}). A
     * threaded {@link LayoutContext#leftEdgePrefix()} gives the exact column; with none, measures at the root's
     * rendered indentation alone — never the root's source column, which differs between passes for the same chain.
     */
    private int rootLineWidth(Expression root, String text, LayoutContext layout) {
        if (!layout.leftEdgePrefix().isEmpty()) {
            return layoutWidth.nodeIndentWidth(root) + layout.leftEdgePrefix().length() + text.length();
        }
        return layoutWidth.nodeIndentWidth(root) + text.length();
    }

        private boolean promotedNoArgRootScopeOverflows(
            MethodCallExpr expression,
            ToIntFunction<String> firstLineWidth
    ) {
        return rootPromotion.promotedNoArgRootScopeOverflows(expression, firstLineWidth);
    }

        private ToIntFunction<String> fannedSelectorColumnWidth(MethodCallExpr expression, ToIntFunction<String> fallback) {
        return rootPromotion.fannedSelectorColumnWidth(expression, fallback);
    }

    private Doc methodCallChainSegment(MethodCallExpr expression) {
        return segmentRenderer.methodCallChainSegment(expression);
    }

    private Doc methodCallChainSegment(MethodCallExpr expression, MethodCallChainTail finalSegmentSuffix) {
        return segmentRenderer.methodCallChainSegment(expression, finalSegmentSuffix);
    }

    private Doc methodCallChainSegmentAttachedToBlockLambdaClose(
            MethodCallExpr expression,
            MethodCallChainTail finalSegmentSuffix
    ) {
        return segmentRenderer.methodCallChainSegmentAttachedToBlockLambdaClose(expression, finalSegmentSuffix);
    }

    private Doc methodCallChainSegmentAttachedToRootClose(
            MethodCallExpr expression,
            MethodCallChainTail finalSegmentSuffix,
            ToIntFunction<String> lineWidth
    ) {
        return segmentRenderer.methodCallChainSegmentAttachedToRootClose(expression, finalSegmentSuffix, lineWidth);
    }

    /**
     * Measures a single segment attached right after a flat, compact method-call root at the root's true rendered
     * column ({@code firstLineWidth}), so the segment's argument-break verdict follows the rendered geometry rather
     * than whatever column the author left the call at.
     */
    private ToIntFunction<String> attachedRootSegmentWidth(MethodCallExpr methodRoot, ToIntFunction<String> firstLineWidth) {
        String rootText = compactSource.compactWithoutOwnComment(methodRoot);
        return segment -> firstLineWidth.applyAsInt(rootText + segment);
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
            Doc fanned;
            if (segmentWidth.singleSimpleMethodCallSegmentArgument(call)) {
                fanned = Doc.concat(
                    rootDoc,
                    objectRootContinuation(methodCallChainSegment(call, Optional.empty(), finalSegmentSuffix))
                );
            } else {
                fanned = Doc.concat(
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
            // The probe above measures the compact chain as if the root rendered FLAT (constructor plus attached
            // selector on one line), which under-counts a root that is actually forced to break onto its own lines at
            // its true, possibly deeply nested column. Once the root breaks, the selector's real landing column is
            // right after the root's OWN close ({@code ).findSessions(args))}), not the flat-root estimate this probe
            // assumed. Rank a HUG that glues the lone tail there — its own argument group still explodes one argument
            // per line on overflow, so it never strands a bare over-width opener — against the fanned-onto-its-own-line
            // shape above, and let the renderer keep whichever wraps fewer lines at the real column: a root that stays
            // flat keeps the fan (attaching there would only push the selector's own arguments open for nothing), and a
            // root that genuinely breaks recovers the glued shape.
            Doc hugged = Doc.concat(rootDoc, methodCallChainSegmentAttachedToRootClose(call, finalSegmentSuffix, lineWidth));
            return Doc.bestFitting(List.of(hugged, fanned));
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

    private Doc brokenMethodCallSegment(
            MethodCallExpr expression,
            String prefix,
            Doc segmentPrefix,
            MethodCallChainTail finalSegmentSuffix
    ) {
        return segmentRenderer.brokenMethodCallSegment(expression, prefix, segmentPrefix, finalSegmentSuffix);
    }

            private boolean finalSegmentAttachesShortBreakingCallArgument(MethodCallExpr call) {
        return segmentRenderer.finalSegmentAttachesShortBreakingCallArgument(call);
    }

    private List<Doc> methodCallChainSegments(List<MethodCallExpr> calls, MethodCallChainTail finalSegmentSuffix) {
        return segmentRenderer.methodCallChainSegments(calls, finalSegmentSuffix);
    }

    private Doc methodCallChainSegment(
            MethodCallExpr expression,
            Optional<MethodCallExpr> nextCall,
            MethodCallChainTail finalSegmentSuffix
    ) {
        return segmentRenderer.methodCallChainSegment(expression, nextCall, finalSegmentSuffix);
    }

    private Doc methodCallChainSegment(
            MethodCallExpr expression,
            Optional<MethodCallExpr> nextCall,
            MethodCallChainTail finalSegmentSuffix,
            ToIntFunction<String> compactSegmentWidth
    ) {
        return segmentRenderer.methodCallChainSegment(expression, nextCall, finalSegmentSuffix, compactSegmentWidth);
    }

    private Doc methodCallChainSegment(
            MethodCallExpr expression,
            Optional<MethodCallExpr> nextCall,
            MethodCallChainTail finalSegmentSuffix,
            ToIntFunction<String> compactSegmentWidth,
            boolean segmentOnOwnLine
    ) {
        return segmentRenderer.methodCallChainSegment(
            expression,
            nextCall,
            finalSegmentSuffix,
            compactSegmentWidth,
            segmentOnOwnLine
        );
    }

    private Doc appendFinalSegmentSuffix(Doc doc, MethodCallChainTail finalSegmentSuffix) {
        return segmentRenderer.appendFinalSegmentSuffix(doc, finalSegmentSuffix);
    }

    private Doc fieldAccessMethodCallSegment(FieldAccessExpr fieldAccess, MethodCallExpr methodCall) {
        return segmentRenderer.fieldAccessMethodCallSegment(fieldAccess, methodCall);
    }

    record MethodCallChainTail(String text) {
        static final MethodCallChainTail EMPTY = new MethodCallChainTail("");

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
