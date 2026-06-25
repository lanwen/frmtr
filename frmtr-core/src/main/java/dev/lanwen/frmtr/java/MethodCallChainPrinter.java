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

    private final Function<Expression, Doc> expressionRenderer;

    private final Function<ObjectCreationExpr, Doc> brokenObjectCreationRenderer;

    private final Function<ObjectCreationExpr, String> objectCreationPrefix;

    private final BiFunction<String, NodeList<Expression>, Optional<Doc>> huggableBlockLambdaArguments;

    private final BiFunction<String, NodeList<Expression>, Optional<String>> huggableBlockLambdaFirstLine;

    private final BiFunction<String, MethodCallExpr, Optional<Doc>> commentedExpressionLambdaArgument;

    private final BiFunction<String, NodeList<Expression>, Optional<Doc>> huggableExpressionLambdaArguments;

    private final BiFunction<
        String,
        NodeList<Expression>,
        Optional<ExpressionLambdaArgumentLayout.Plan>
    > expressionLambdaArgumentPlan;

    private final ToIntFunction<String> currentIndentedWidth;

    private final ToIntFunction<String> continuationStatementWidth;

    private final ToIntFunction<String> blockStatementWidth;

    private final LayoutDecisionLog layoutDecisions;

    private final SourceMultilineLambdaCallLayout sourceMultilineLambdaCalls;

    MethodCallChainPrinter(
            JavaFormatContext context,
            MethodCallPrinter calls,
            TypePrinter types,
            CommentedExpressionListPrinter commentedExpressionLists,
            Function<Expression, Doc> expressionRenderer,
            Function<ObjectCreationExpr, Doc> brokenObjectCreationRenderer,
            Function<ObjectCreationExpr, String> objectCreationPrefix,
            BiFunction<String, NodeList<Expression>, Optional<Doc>> huggableBlockLambdaArguments,
            BiFunction<String, NodeList<Expression>, Optional<String>> huggableBlockLambdaFirstLine,
            BiFunction<String, MethodCallExpr, Optional<Doc>> commentedExpressionLambdaArgument,
            BiFunction<String, NodeList<Expression>, Optional<Doc>> huggableExpressionLambdaArguments,
            BiFunction<
                String,
                NodeList<Expression>,
                Optional<ExpressionLambdaArgumentLayout.Plan>
            > expressionLambdaArgumentPlan,
            Function<LambdaExpr, String> lambdaParameters,
            ToIntFunction<String> currentIndentedWidth,
            ToIntFunction<String> continuationStatementWidth,
            ToIntFunction<String> blockStatementWidth
    ) {
        this.comments = context.comments;
        this.commentPlacement = context.commentPlacementPolicy;
        this.rawSource = context.rawSource;
        this.sourceShapePolicy = context.sourceShapePolicy;
        this.methodChainPlanner = new MethodCallChainSourcePlanner(context, currentIndentedWidth);
        this.options = context.options;
        this.compactSource = context.compactSource;
        this.layoutWidth = context.layoutWidth;
        this.calls = calls;
        this.types = types;
        this.commentedExpressionLists = commentedExpressionLists;
        this.expressionRenderer = expressionRenderer;
        this.brokenObjectCreationRenderer = brokenObjectCreationRenderer;
        this.objectCreationPrefix = objectCreationPrefix;
        this.huggableBlockLambdaArguments = huggableBlockLambdaArguments;
        this.huggableBlockLambdaFirstLine = huggableBlockLambdaFirstLine;
        this.commentedExpressionLambdaArgument = commentedExpressionLambdaArgument;
        this.huggableExpressionLambdaArguments = huggableExpressionLambdaArguments;
        this.expressionLambdaArgumentPlan = expressionLambdaArgumentPlan;
        this.currentIndentedWidth = currentIndentedWidth;
        this.continuationStatementWidth = continuationStatementWidth;
        this.blockStatementWidth = blockStatementWidth;
        this.layoutDecisions = context.layoutDecisions;
        this.sourceMultilineLambdaCalls = new SourceMultilineLambdaCallLayout(
            context.sourceShapePolicy,
            expressionRenderer,
            lambdaParameters,
            calls::methodCallPrefix,
            this::methodCallSegmentPrefixText,
            calls::methodCallArgumentList
        );
    }

    Optional<Doc> methodCallChain(MethodCallExpr expression) {
        return methodCallChain(expression, MethodCallBreakMode.AUTO);
    }

    Optional<Doc> forcedMethodCallChain(MethodCallExpr expression) {
        return forcedMethodCallChain(expression, LayoutWidth.LineBudget.CURRENT);
    }

    Optional<Doc> forcedMethodCallChain(MethodCallExpr expression, LayoutWidth.LineBudget lineBudget) {
        return forcedMethodCallChain(expression, lineBudget, lineWidth(lineBudget));
    }

    Optional<Doc> forcedMethodCallChain(
            MethodCallExpr expression,
            ToIntFunction<String> firstLineWidth
    ) {
        return forcedMethodCallChain(expression, LayoutWidth.LineBudget.CURRENT, firstLineWidth);
    }

    Optional<Doc> packedMethodCallChain(
            MethodCallExpr expression,
            ToIntFunction<String> firstLineWidth
    ) {
        return packedCompactMethodCallChain(
            expression,
            firstLineWidth,
            continuationStatementWidth,
            true
        )
                .map(this::packedMethodCallChainDoc)
                .or(() -> packedBrokenObjectRootChain(expression, firstLineWidth));
    }

    private Optional<PackedMethodCallChainText> packedCompactMethodCallChain(
            MethodCallExpr expression,
            ToIntFunction<String> firstLineWidth,
            ToIntFunction<String> continuationWidth,
            boolean reserveFinalTerminator
    ) {
        if (!expression.getAllContainedComments().isEmpty()) {
            return Optional.empty();
        }
        List<String> segments = new ArrayList<>();
        Optional<String> root = compactMethodCallChainRoot(expression, segments);
        if (root.isEmpty() || segments.isEmpty()) {
            return Optional.empty();
        }
        String firstLine = root.orElseThrow();
        if (firstLineWidth.applyAsInt(firstLine) > options.lineWidth()) {
            return Optional.empty();
        }
        int splitIndex = 0;
        for (int index = 0; index < segments.size(); index++) {
            String candidate = firstLine + segments.get(index);
            String widthText = packedChainWidthText(candidate, index + 1 == segments.size(), reserveFinalTerminator);
            if (firstLineWidth.applyAsInt(widthText) > options.lineWidth()) {
                break;
            }
            firstLine = candidate;
            splitIndex = index + 1;
        }
        if (splitIndex >= segments.size()) {
            return Optional.empty();
        }
        List<String> remaining = segments.subList(splitIndex, segments.size());
        for (int index = 0; index < remaining.size(); index++) {
            boolean finalSegment = splitIndex + index + 1 == segments.size();
            String widthText = packedChainWidthText(remaining.get(index), finalSegment, reserveFinalTerminator);
            if (continuationWidth.applyAsInt(widthText) > options.lineWidth()) {
                return Optional.empty();
            }
        }
        return Optional.of(new PackedMethodCallChainText(firstLine, remaining));
    }

    private String packedChainWidthText(String text, boolean finalSegment, boolean reserveFinalTerminator) {
        return finalSegment && reserveFinalTerminator ? text + ";" : text;
    }

    private Doc packedMethodCallChainDoc(PackedMethodCallChainText chain) {
        return Doc.concat(
            Doc.text(chain.firstLine()),
            chainContinuation(Doc.join(Doc.HARD_LINE, chain.remainingSegments().stream().map(Doc::text).toList()))
        );
    }

    private Optional<Doc> packedBrokenObjectRootChain(
            MethodCallExpr expression,
            ToIntFunction<String> firstLineWidth
    ) {
        MethodCallChainSourcePlanner.MethodCallChainAnalysis analysis = methodCallChainAnalysis(expression);
        if (
            analysis.hasComments()
            || analysis.hasBlockLambdaArgument()
            || !(analysis.root() instanceof ObjectCreationExpr objectCreation)
            || analysis.calls().isEmpty()
            || objectCreation.getArguments().isEmpty()
            || objectCreation.getAnonymousClassBody().isPresent()
            || sourceShapePolicy.objectCreationArgumentsSpanMultipleLines(objectCreation)
            || analysis.calls().stream().anyMatch(call -> !compactMethodCallChainSegmentCanStayFlat(call))
            || sourceShapePolicy.fitsOnOneLine(objectCreation, firstLineWidth)
            || firstLineWidth.applyAsInt(objectCreationPrefix.apply(objectCreation) + "(") > options.lineWidth()
        ) {
            return Optional.empty();
        }
        Doc rootDoc = brokenObjectCreationRenderer.apply(objectCreation);
        List<MethodCallExpr> calls = analysis.calls();
        if (calls.size() == 1) {
            return Optional.of(objectRootSingleSegmentChain(
                objectCreation,
                rootDoc,
                calls.getFirst(),
                MethodCallChainTail.EMPTY,
                MethodCallChainSourcePlanner.ChainRootRendering.BROKEN_OBJECT_CREATION,
                analysis.sourceMultilineChain(),
                LayoutWidth.LineBudget.CURRENT,
                firstLineWidth
            ));
        }
        Doc firstSegment = methodCallChainSegment(
            calls.getFirst(),
            Optional.of(calls.get(1)),
            MethodCallChainTail.EMPTY,
            segment -> layoutWidth.line(LayoutWidth.LineBudget.CURRENT, ")" + segment)
        );
        return Optional.of(
            Doc.concat(
                rootDoc,
                firstSegment,
                chainContinuation(
                    Doc.join(
                        Doc.HARD_LINE,
                        methodCallChainSegments(calls.subList(1, calls.size()), MethodCallChainTail.EMPTY)
                    )
                )
            )
        );
    }

    private record PackedMethodCallChainText(String firstLine, List<String> remainingSegments) {
        PackedMethodCallChainText {
            remainingSegments = List.copyOf(remainingSegments);
        }
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
    }

    private Optional<Doc> forcedMethodCallChain(
            MethodCallExpr expression,
            LayoutWidth.LineBudget lineBudget,
            ToIntFunction<String> firstLineWidth
    ) {
        return methodCallChain(
            expression,
            MethodCallBreakMode.FORCED,
            MethodCallChainTail.EMPTY,
            lineBudget,
            firstLineWidth
        );
    }

    Optional<Doc> compactRootWithBrokenFinalChainSegment(MethodCallExpr expression) {
        return compactRootWithBrokenFinalChainSegment(expression, LayoutWidth.LineBudget.CURRENT);
    }

    Optional<Doc> compactRootWithBrokenFinalChainSegment(
            MethodCallExpr expression,
            LayoutWidth.LineBudget lineBudget
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
            return compactRootWithBrokenFinalSegment(methodRoot, calls.getFirst(), lineBudget);
        }
        if (methodChainPlanner.promotesFirstCall(root) && calls.size() == 2) {
            return compactRootWithBrokenFinalSegment(calls.getFirst(), calls.get(1), lineBudget);
        }
        return Optional.empty();
    }

    /**
     * Builds the chain fragment used when an expression-lambda body is packed after {@code ->}.
     *
     * <p>The lambda helper still owns the enclosing call suffix, but chain root collection, compact segment text, and
     * split-point selection stay with the method-call chain printer.
     */
    Optional<Doc> packedExpressionLambdaBodyChain(String firstLine, MethodCallExpr expression) {
        return packedExpressionLambdaBodyChain(firstLine, expression, this::expressionLambdaBodyLineWidth);
    }

    private Optional<Doc> packedExpressionLambdaBodyChain(
            String firstLine,
            MethodCallExpr expression,
            ToIntFunction<String> bodyLineWidth
    ) {
        return packedCompactMethodCallChain(
            expression,
            text -> bodyLineWidth.applyAsInt(firstLine + " " + text),
            this::packedExpressionLambdaBodyLineWidth,
            false
        ).map(this::packedMethodCallChainDoc);
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
        if (!scoped.getAllContainedComments().isEmpty() || sourceShapePolicy.wasMultiline(scoped)) {
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
                        || sourceShapePolicy.wasMultiline(argument)
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

    private int expressionLambdaBodyLineWidth(String line) {
        return blockStatementWidth.applyAsInt(options.indentUnit() + line);
    }

    private int packedExpressionLambdaBodyLineWidth(String line) {
        return blockStatementWidth.applyAsInt(options.indentUnit().repeat(3) + line);
    }

    /**
     * Preserves an already-multiline call statement when the argument list itself spans source lines.
     *
     * <p>Statement rendering still owns the trailing semicolon, but the call printer owns the call-shape decision because
     * it already owns argument breaks and source-multiline argument layout.
     */
    Optional<Doc> sourceMultilineMethodCallStatement(
            MethodCallExpr expression,
            ExpressionStmt statement
    ) {
        if (!sourceShapePolicy.wasMultiline(statement)) {
            return Optional.empty();
        }
        return calls.sourceMultilineArguments(expression);
    }

    /**
     * Prints a dotted call chain when the call is naturally chain-shaped or when a caller forces the chain break.
     *
     * <p>Auto mode leaves short uncommented calls alone. Forced mode is used by return, assignment, statement, and field
     * contexts that already know the surrounding line overflowed and need a broken call shape.
     */
    Optional<Doc> methodCallChain(MethodCallExpr expression, boolean force) {
        return methodCallChain(expression, MethodCallBreakMode.fromForced(force));
    }

    private Optional<Doc> methodCallChain(MethodCallExpr expression, MethodCallBreakMode breakMode) {
        return methodCallChain(expression, breakMode, "", LayoutWidth.LineBudget.CURRENT);
    }

    Optional<Doc> methodCallChain(
            MethodCallExpr expression,
            MethodCallBreakMode breakMode,
            String finalSegmentSuffix
    ) {
        return methodCallChain(
            expression,
            breakMode,
            finalSegmentSuffix,
            LayoutWidth.LineBudget.CURRENT
        );
    }

    Optional<Doc> methodCallChain(
            MethodCallExpr expression,
            MethodCallBreakMode breakMode,
            String finalSegmentSuffix,
            LayoutWidth.LineBudget lineBudget
    ) {
        return methodCallChain(expression, breakMode, MethodCallChainTail.of(finalSegmentSuffix), lineBudget);
    }

    Optional<Doc> methodCallChain(
            MethodCallExpr expression,
            MethodCallBreakMode breakMode,
            String finalSegmentSuffix,
            LayoutWidth.LineBudget lineBudget,
            ToIntFunction<String> firstLineWidth
    ) {
        return methodCallChain(
            expression,
            breakMode,
            MethodCallChainTail.of(finalSegmentSuffix),
            lineBudget,
            firstLineWidth
        );
    }

    private Optional<Doc> methodCallChain(
            MethodCallExpr expression,
            MethodCallBreakMode breakMode,
            MethodCallChainTail finalSegmentSuffix
    ) {
        return methodCallChain(expression, breakMode, finalSegmentSuffix, LayoutWidth.LineBudget.CURRENT);
    }

    private Optional<Doc> methodCallChain(
            MethodCallExpr expression,
            MethodCallBreakMode breakMode,
            MethodCallChainTail finalSegmentSuffix,
            LayoutWidth.LineBudget lineBudget
    ) {
        return methodCallChain(expression, breakMode, finalSegmentSuffix, lineBudget, lineWidth(lineBudget));
    }

    private Optional<Doc> methodCallChain(
            MethodCallExpr expression,
            MethodCallBreakMode breakMode,
            MethodCallChainTail finalSegmentSuffix,
            LayoutWidth.LineBudget lineBudget,
            ToIntFunction<String> firstLineWidth
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
                && !sourceMultilineArguments
                && !rootObjectCreationNeedsBreak
                && layoutWidth.line(lineBudget, compactSource.compact(expression)) <= options.lineWidth())
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
        if (singleStringLiteralCallWithSourceMultilineArguments(root, calls)) {
            return Optional.empty();
        }
        if (canBreakAfterCompactExpressionLambdaRoot(breakMode, root, calls, sourceMultilineLambdaPlan)) {
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
                    lineBudget
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
                    expressionRenderer.apply(methodRoot),
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
        Doc rootDoc = methodCallChainRootDoc(chainPlan, firstLineWidth);
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
                    .filter(firstLine -> blockStatementWidth.applyAsInt(firstLine) <= options.lineWidth())
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
            return Optional.of(objectRootSingleSegmentChain(
                objectCreation,
                rootDoc,
                calls.getFirst(),
                finalSegmentSuffix,
                chainPlan.rootRendering(),
                analysis.sourceMultilineChain(),
                lineBudget,
                firstLineWidth
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
                        .filter(firstLine -> blockStatementWidth.applyAsInt(firstLine) <= options.lineWidth())
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
            if (
                !analysis.sourceMultilineChain()
                && sourceShapePolicy.methodCallArgumentsSpanMultipleLines(calls.getFirst())
            ) {
                Optional<Doc> compactRootWithBrokenSegment = comments.speculatively(
                    () -> compactRootWithBrokenFinalSegment(methodRoot, probeCall, finalSegmentSuffix, lineBudget)
                );
                if (compactRootWithBrokenSegment.isPresent()) {
                    return compactRootWithBrokenSegment;
                }
            }
            Optional<Doc> expressionLambdaRoot = comments.speculatively(
                () -> expressionLambdaRootWithSingleSegment(methodRoot, probeCall, finalSegmentSuffix, lineBudget)
            );
            if (expressionLambdaRoot.isPresent()) {
                return expressionLambdaRoot;
            }
            if (compactRootFinalSegmentLineOverflows(
                    methodRoot,
                    calls.getFirst(),
                    finalSegmentSuffix,
                    lineBudget
                )) {
                Optional<Doc> compactRootWithBrokenSegment = comments.speculatively(
                    () -> compactRootWithBrokenFinalSegment(methodRoot, probeCall, finalSegmentSuffix, lineBudget)
                );
                if (compactRootWithBrokenSegment.isPresent()) {
                    return compactRootWithBrokenSegment;
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
                    groupedPromotedRootWithSingleSegment(root, rootDoc, calls.getFirst(), finalSegmentSuffix)
                );
            }
            return Optional.of(Doc.concat(rootDoc, methodCallChainSegment(calls.getFirst(), finalSegmentSuffix)));
        }
        // Record the width break only here, where the printer has committed to the broken one-segment-per-line chain
        // this method's PrinterWrap describes. The earlier deferral branches hand rendering to a different printer that
        // does not lay the chain out one per line, so recording before them could attribute a "N segments, one per line"
        // layout to a path that never produced it.
        recordChainWidthBreak(expression, analysis, lineBudget);
        List<Doc> segments = methodCallChainSegments(calls, finalSegmentSuffix);
        return Optional.of(
            Doc.concat(
                rootDoc,
                chainContinuation(root, segments)
            )
        );
    }

    private boolean singleStringLiteralCallWithSourceMultilineArguments(
            Expression root,
            List<MethodCallExpr> calls
    ) {
        return root instanceof StringLiteralExpr
            && calls.size() == 1
            && calls.getFirst().getAllContainedComments().isEmpty()
            && !methodCallSegmentHasComment(calls.getFirst())
            && sourceShapePolicy.methodCallArgumentsSpanMultipleLines(calls.getFirst());
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

    private boolean compactRootFinalSegmentLineOverflows(
            MethodCallExpr methodRoot,
            MethodCallExpr call,
            MethodCallChainTail finalSegmentSuffix,
            LayoutWidth.LineBudget lineBudget
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
            || sourceFirstLineIsOnlyChainRoot(chainPlan.root(), expression)
            || !sourceShapePolicy.startsOnSameLine(chainPlan.root(), calls.getFirst().getName())
        ) {
            return false;
        }
        MethodCallExpr firstCall = calls.getFirst();
        return (
            !methodCallSegmentHasBlockLambdaArgument(firstCall)
            && (sourceShapePolicy.fitsOnOneLine(firstCall, currentIndentedWidth)
                || currentIndentedWidth.applyAsInt(this.calls.methodCallPrefix(firstCall) + "(") <= options.lineWidth())
        );
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
                () -> huggableExpressionLambdaArguments.apply(plan.chainSegmentPrefix(), firstCall.getArguments())
            );
            if (huggableExpressionLambda.isPresent()) {
                return Doc.concat(expressionRenderer.apply(root), huggableExpressionLambda.orElseThrow());
            }
            return Doc.concat(expressionRenderer.apply(root), methodCallChainSegment(firstCall));
        }
        if (sourceShapePolicy.fitsOnOneLine(firstCall, currentIndentedWidth)) {
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
        if (sourceShapePolicy.fitsOnOneLine(firstCall, currentIndentedWidth)) {
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
            expressionRenderer.apply(root),
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
            && (sourceShapePolicy.methodCallArgumentsSpanMultipleLines(methodRoot)
                || sourceMultilineLambdaPlan.rootCanAttachExpressionLambdaBody())
        ) {
            return true;
        }
        for (int index = 0; index < Math.max(0, analysis.calls().size() - 1); index++) {
            MethodCallExpr call = analysis.calls().get(index);
            if (
                sourceShapePolicy.methodCallArgumentsSpanMultipleLines(call)
                || methodCallSegmentHasSourceMultilineBlockLambdaArgument(call)
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
            ToIntFunction<String> firstLineWidth
    ) {
        return switch (chainPlan.rootRendering()) {
            case INLINE_PROMOTED_METHOD_CALL -> chainPlan.root() instanceof MethodCallExpr methodCall
                ? promotedMethodCallRoot(methodCall, firstLineWidth)
                : expressionRenderer.apply(chainPlan.root());
            case GROUPED_PROMOTED_METHOD_CALL -> chainPlan.root() instanceof MethodCallExpr methodCall
                ? groupedPromotedMethodCall(methodCall)
                : expressionRenderer.apply(chainPlan.root());
            case BROKEN_OBJECT_CREATION -> brokenObjectCreationRenderer.apply((ObjectCreationExpr) chainPlan.root());
            case EXPRESSION_RENDERER -> expressionRenderedChainRoot(chainPlan.root(), firstLineWidth);
        };
    }

    private Doc expressionRenderedChainRoot(
            Expression root,
            ToIntFunction<String> firstLineWidth
    ) {
        if (
            root instanceof MethodCallExpr methodCall
            && methodCall.getArguments().size() > 1
            && (firstLineWidth.applyAsInt(compactSourceWidthText(methodCall)) > options.lineWidth()
                || (sourceMultilineTypeLikeRoot(methodCall)
                    && !sourceShapePolicy.fitsOnOneLine(methodCall, firstLineWidth)))
        ) {
            return calls.brokenMethodCall(methodCall);
        }
        return expressionRenderer.apply(root);
    }

    private boolean sourceMultilineTypeLikeRoot(MethodCallExpr methodCall) {
        return methodChainPlanner.methodCallHasTypeLikeScope(methodCall)
            && sourceShapePolicy.wasMultiline(methodCall);
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
            currentIndentedWidth.applyAsInt(compactSourceWidthText(methodRoot)) > options.lineWidth()
            || methodCallRootScopeOverflows(methodRoot)
        ) {
            return methodCallChain(methodRoot, MethodCallBreakMode.FORCED).orElseGet(() -> expressionRenderer.apply(
                    methodRoot
            ));
        }
        return expressionRenderer.apply(methodRoot);
    }

    private Optional<Doc> brokenTypeLikeScopedMethodRoot(MethodCallExpr methodRoot) {
        Optional<MethodCallExpr> scopedCall = methodRoot.getScope()
                .filter(MethodCallExpr.class::isInstance)
                .map(MethodCallExpr.class::cast)
                .filter(call -> call.getArguments().size() > 1)
                .filter(call -> call.getScope().filter(methodChainPlanner::promotesFirstCall).isPresent())
                .filter(call -> currentIndentedWidth.applyAsInt(compactSourceWidthText(call)) > options.lineWidth());
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
                .map(scopedCall -> currentIndentedWidth.applyAsInt(
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
            && !sourceShapePolicy.fitsOnOneLine(expression, currentIndentedWidth)
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
                    .filter(firstLine -> blockStatementWidth.applyAsInt(firstLine) <= options.lineWidth())
                    .map(ignored -> expressionRenderer.apply(expression))
                    .orElseGet(() -> Doc.concat(
                            expressionRenderer.apply(expression.getScope().orElseThrow()),
                            chainContinuation(methodCallChainSegment(expression))
                    ));
        }
        return expression.getScope()
                .map(scope -> Doc.group(
                        Doc.concat(
                            expressionRenderer.apply(scope),
                            softChainContinuation(methodCallChainSegment(expression))
                        )
                ))
                .orElseGet(() -> expressionRenderer.apply(expression));
    }

    private Optional<Doc> groupedPromotedExpressionLambda(MethodCallExpr expression) {
        if (
            !sourceShapePolicy.expressionLambdaStartsOnSelectorLine(expression)
            || !expressionLambdaSpansMultipleLines(expression)
        ) {
            return Optional.empty();
        }
        return expression.getScope()
                .map(
                    scope -> compactSource.compact(scope)
                            + "."
                            + expression
                                    .getTypeArguments()
                                    .map(arguments -> "<" + types.compactJoinTypeLike(arguments) + ">")
                                    .orElse("")
                            + expression.getNameAsString()
                )
                .flatMap(prefix -> huggableExpressionLambdaArguments.apply(prefix, expression.getArguments()));
    }

    private Doc groupedPromotedRootWithSingleSegment(
            Expression root,
            Doc rootDoc,
            MethodCallExpr expression,
            MethodCallChainTail finalSegmentSuffix
    ) {
        if (methodCallSegmentHasBlockLambdaArgument(expression)) {
            return blockLambdaSegmentFirstLine(compactSource.compact(root), expression)
                    .filter(firstLine -> blockStatementWidth.applyAsInt(firstLine) <= options.lineWidth())
                    .map(ignored -> Doc.concat(rootDoc, methodCallChainSegment(expression, finalSegmentSuffix)))
                    .orElseGet(() -> Doc.concat(
                            rootDoc,
                            chainContinuation(methodCallChainSegment(expression, finalSegmentSuffix))
                    ));
        }
        if (
            sourceShapePolicy.expressionLambdaStartsOnSelectorLine(expression)
            && expressionLambdaSpansMultipleLines(expression)
            && expressionLambdaBodyOpenerOverflows(
                root,
                compactRootCallPrefix(root, expression),
                expression.getArguments()
            )
        ) {
            return Doc.concat(
                rootDoc,
                chainContinuation(methodCallChainSegment(expression, finalSegmentSuffix))
            );
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

    private Optional<Doc> compactRootWithBrokenFinalSegment(Expression root, MethodCallExpr call) {
        return compactRootWithBrokenFinalSegment(
            root,
            call,
            MethodCallChainTail.EMPTY,
            LayoutWidth.LineBudget.CURRENT
        );
    }

    private Optional<Doc> compactRootWithBrokenFinalSegment(
            Expression root,
            MethodCallExpr call,
            LayoutWidth.LineBudget lineBudget
    ) {
        return compactRootWithBrokenFinalSegment(root, call, MethodCallChainTail.EMPTY, lineBudget);
    }

    private Optional<Doc> compactRootWithBrokenFinalSegment(
            Expression root,
            MethodCallExpr call,
            MethodCallChainTail finalSegmentSuffix
    ) {
        return compactRootWithBrokenFinalSegment(
            root,
            call,
            finalSegmentSuffix,
            LayoutWidth.LineBudget.CURRENT
        );
    }

    private Optional<Doc> compactRootWithBrokenFinalSegment(
            Expression root,
            MethodCallExpr call,
            MethodCallChainTail finalSegmentSuffix,
            LayoutWidth.LineBudget lineBudget
    ) {
        if (call.getArguments().isEmpty()) {
            return Optional.empty();
        }
        if (
            root instanceof MethodCallExpr methodRoot
            && sourceShapePolicy.methodCallArgumentsSpanMultipleLines(methodRoot)
        ) {
            return Optional.empty();
        }
        if (
            root instanceof MethodCallExpr methodRoot
            && methodCallSegmentHasSourceMultilineBlockLambdaArgument(methodRoot)
        ) {
            return Optional.empty();
        }
        if (
            root instanceof ObjectCreationExpr objectCreation
            && sourceShapePolicy.objectCreationArgumentsSpanMultipleLines(objectCreation)
        ) {
            return Optional.empty();
        }
        if (
            !(root instanceof MethodCallExpr)
            && !(root instanceof ObjectCreationExpr)
            && !(root instanceof FieldAccessExpr)
            && !sourceShapePolicy.startsOnSameLine(root, call.getName())
        ) {
            return Optional.empty();
        }
        String typeArguments = call.getTypeArguments()
                .map(arguments -> "<" + types.compactJoinTypeLike(arguments) + ">")
                .orElse("");
        String callPrefix = compactSource.compact(root) + "." + typeArguments + call.getNameAsString();
        if (!compactRootFirstLineFits(root, callPrefix, call.getArguments(), lineBudget)) {
            return Optional.empty();
        }
        Optional<Doc> huggableLambda =
            comments.speculatively(() -> huggableBlockLambdaArguments.apply(callPrefix, call.getArguments()));
        if (huggableLambda.isPresent()) {
            return Optional.of(Doc.concat(huggableLambda.orElseThrow(), finalSegmentSuffix.doc()));
        }
        if (sourceShapePolicy.expressionLambdaStartsOnSelectorLine(call) && expressionLambdaSpansMultipleLines(call)) {
            Optional<ExpressionLambdaArgumentLayout.Plan> expressionLambdaPlan = expressionLambdaArgumentPlan.apply(
                callPrefix,
                call.getArguments()
            );
            if (expressionLambdaPlan.isEmpty()) {
                return Optional.empty();
            }
            Optional<Doc> huggableExpressionLambda = comments.speculatively(
                () -> huggableExpressionLambdaArguments.apply(callPrefix, call.getArguments())
            );
            if (huggableExpressionLambda.isPresent()) {
                if (
                    expressionLambdaPlan
                            .orElseThrow()
                            .bodyOpenerOverflows(
                                line -> compactRootLineWidth(root, line, lineBudget),
                                options.lineWidth()
                            )
                ) {
                    return Optional.empty();
                }
                return Optional.of(Doc.concat(huggableExpressionLambda.orElseThrow(), finalSegmentSuffix.doc()));
            }
        }
        String prefix = callPrefix + "(";
        if (
            root instanceof ObjectCreationExpr
            && compactRootLineWidth(root, prefix, lineBudget) > options.lineWidth()
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
            LayoutWidth.LineBudget lineBudget
    ) {
        Optional<String> blockLambdaFirstLine = huggableBlockLambdaFirstLine.apply(callPrefix, arguments);
        if (
            blockLambdaFirstLine
                    .filter(
                        firstLine -> compactRootLineWidth(
                            root,
                            firstLine,
                            lineBudget
                        ) > options.lineWidth()
                    )
                    .isPresent()
        ) {
            return false;
        }
        Optional<ExpressionLambdaArgumentLayout.Plan> expressionLambdaPlan = expressionLambdaArgumentPlan.apply(
            callPrefix,
            arguments
        );
        return expressionLambdaPlan
                .map(plan -> plan.firstLineFits(
                        line -> compactRootLineWidth(root, line, lineBudget),
                        options.lineWidth()
                ))
                .orElse(true);
    }

    private int compactRootLineWidth(Expression root, String firstLine, LayoutWidth.LineBudget lineBudget) {
        return root.getRange()
                .map(range -> Math.max(0, range.begin.column + 1) + firstLine.length())
                .orElseGet(() -> layoutWidth.line(lineBudget, firstLine));
    }

    private boolean expressionLambdaBodyOpenerOverflows(
            Expression root,
            String callPrefix,
            NodeList<Expression> arguments
    ) {
        return expressionLambdaArgumentPlan.apply(callPrefix, arguments)
                .filter(plan -> plan.bodyOpenerFitsOnContinuation(continuationStatementWidth, options.lineWidth()))
                .filter(plan -> plan.bodyOpenerOverflows(
                        line -> compactRootLineWidth(root, line, LayoutWidth.LineBudget.CURRENT),
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
     * Breaks chains that alternate method calls and field accesses as one structural chain.
     *
     * <p>A normal method-call chain can walk through method-call scopes directly. Mixed chains need a separate
     * structural-root path because field accesses can hide the earlier method-call root behind one or more field names.
     */
    Optional<Doc> mixedFieldMethodCallChain(MethodCallExpr expression) {
        if (!expression.getAllContainedComments().isEmpty()) {
            return Optional.empty();
        }
        List<Doc> segments = new ArrayList<>();
        Optional<Expression> root = collectMixedFieldMethodCallChain(expression, segments);
        if (root.isEmpty() || segments.size() < 2) {
            return Optional.empty();
        }
        return Optional.of(
            Doc.concat(
                expressionRenderer.apply(root.orElseThrow()),
                chainContinuation(Doc.join(Doc.HARD_LINE, segments))
            )
        );
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
        if (!root.getAllContainedComments().isEmpty() || sourceShapePolicy.wasMultiline(root)) {
            return Optional.empty();
        }
        return Optional.of(compactSource.compact(root));
    }

    private record PaddedDoc(Doc doc, boolean lineStart) {}

    private Doc softChainContinuation(Doc doc) {
        return Doc.indent(Doc.indent(Doc.concat(Doc.SOFT_LINE, doc)));
    }

    Optional<Expression> mixedFieldMethodCallRoot(MethodCallExpr expression) {
        if (!expression.getAllContainedComments().isEmpty()) {
            return Optional.empty();
        }
        if (mixedFieldMethodCallSegmentCount(expression) < 2) {
            return Optional.empty();
        }
        return mixedFieldMethodCallStructuralRoot(expression);
    }

    private int mixedFieldMethodCallSegmentCount(MethodCallExpr expression) {
        Optional<Expression> scope = expression.getScope();
        if (scope.isEmpty()) {
            return 0;
        }
        Expression scoped = scope.orElseThrow();
        if (scoped instanceof MethodCallExpr methodScope) {
            int segments = mixedFieldMethodCallSegmentCount(methodScope);
            return segments == 0 ? 0 : segments + 1;
        }
        if (scoped instanceof FieldAccessExpr fieldAccess) {
            Optional<MethodCallExpr> methodRoot = fieldAccessMethodRoot(fieldAccess);
            return methodRoot.map(root -> mixedFieldMethodCallSegmentCount(root) + 1).orElse(0);
        }
        return 1;
    }

    private Optional<Expression> mixedFieldMethodCallStructuralRoot(MethodCallExpr expression) {
        Optional<Expression> scope = expression.getScope();
        if (scope.isEmpty()) {
            return Optional.empty();
        }
        Expression scoped = scope.orElseThrow();
        if (scoped instanceof MethodCallExpr methodScope) {
            return mixedFieldMethodCallStructuralRoot(methodScope);
        }
        if (scoped instanceof FieldAccessExpr fieldAccess) {
            return fieldAccessMethodRoot(fieldAccess).flatMap(this::mixedFieldMethodCallStructuralRoot);
        }
        return Optional.of(scoped);
    }

    private Optional<Expression> collectMixedFieldMethodCallChain(MethodCallExpr expression, List<Doc> segments) {
        Optional<Expression> scope = expression.getScope();
        if (scope.isEmpty()) {
            return Optional.empty();
        }
        Expression scoped = scope.orElseThrow();
        if (scoped instanceof MethodCallExpr methodScope) {
            Optional<Expression> root = collectMixedFieldMethodCallChain(methodScope, segments);
            root.ifPresent(ignored -> segments.add(methodCallChainSegment(expression)));
            return root;
        }
        if (scoped instanceof FieldAccessExpr fieldAccess) {
            Optional<MethodCallExpr> methodRoot = fieldAccessMethodRoot(fieldAccess);
            if (methodRoot.isEmpty()) {
                return Optional.empty();
            }
            Optional<Expression> root = collectMixedFieldMethodCallChain(methodRoot.orElseThrow(), segments);
            root.ifPresent(ignored -> segments.add(fieldAccessMethodCallSegment(fieldAccess, expression)));
            return root;
        }
        segments.add(methodCallChainSegment(expression));
        return Optional.of(scoped);
    }

    private Optional<MethodCallExpr> fieldAccessMethodRoot(FieldAccessExpr fieldAccess) {
        Expression scope = fieldAccess.getScope();
        if (scope instanceof MethodCallExpr methodCall) {
            return Optional.of(methodCall);
        }
        if (scope instanceof FieldAccessExpr innerFieldAccess) {
            return fieldAccessMethodRoot(innerFieldAccess);
        }
        return Optional.empty();
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
            || chainHasSourceMultilineArguments(analysis, sourceMultilineLambdaPlan)
            || (analysis.root() instanceof MethodCallExpr methodRoot
                && sourceShapePolicy.methodCallArgumentsSpanMultipleLines(methodRoot))
            || (analysis.root() instanceof ObjectCreationExpr objectCreation
                && sourceShapePolicy.objectCreationArgumentsSpanMultipleLines(objectCreation))
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
            this::methodCallChainHasTrailingLineComments
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
        if (sourceShapePolicy.methodCallArgumentsSpanMultipleLines(methodRoot)) {
            return Optional.of(prefix + "(");
        }
        if (promotedRootArgumentsShouldBreak(methodRoot, lineWidth(LayoutWidth.LineBudget.CURRENT))) {
            return Optional.of(prefix + "(");
        }
        if (!sourceShapePolicy.fitsOnOneLine(methodRoot, currentIndentedWidth)) {
            return Optional.of(prefix + "(");
        }
        if (methodCallSegmentHasBlockLambdaArgument(methodRoot)) {
            return huggableBlockLambdaFirstLine.apply(prefix, methodRoot.getArguments());
        }
        return Optional.empty();
    }

    private Optional<Doc> expressionLambdaRootWithSingleSegment(
            MethodCallExpr methodRoot,
            MethodCallExpr call,
            MethodCallChainTail finalSegmentSuffix,
            LayoutWidth.LineBudget lineBudget
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
                Doc.indent(Doc.concat(Doc.HARD_LINE, expressionRenderer.apply(methodRoot.getArgument(0)))),
                Doc.HARD_LINE,
                Doc.text(")"),
                methodCallChainSegmentAttachedToRootClose(call, finalSegmentSuffix, lineBudget)
            )
        );
    }

    private boolean hasSingleExpressionLambdaArgument(MethodCallExpr expression) {
        return hasSingleExpressionLambdaArgumentAnyShape(expression)
            && !sourceShapePolicy.methodCallArgumentsSpanMultipleLines(expression);
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

    private boolean methodCallSegmentHasBlockLambdaArgument(MethodCallExpr expression) {
        return expression.getArguments()
                .stream()
                .anyMatch(argument -> argument instanceof LambdaExpr lambdaExpr
                        && lambdaExpr.getBody().isBlockStmt()
                );
    }

    private boolean methodCallSegmentHasSourceMultilineBlockLambdaArgument(MethodCallExpr expression) {
        return expression.getArguments()
                .stream()
                .filter(LambdaExpr.class::isInstance)
                .map(LambdaExpr.class::cast)
                .filter(lambdaExpr -> lambdaExpr.getBody().isBlockStmt())
                .anyMatch(lambdaExpr -> sourceShapePolicy.wasMultiline(lambdaExpr.getBody()));
    }

    private boolean canBreakAfterCompactExpressionLambdaRoot(
            MethodCallBreakMode breakMode,
            Expression root,
            List<MethodCallExpr> calls,
            SourceMultilineLambdaChainPlan sourceMultilineLambdaPlan
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
        return rootLineWidth(root, compactSource.compact(root)) <= options.lineWidth();
    }

    private boolean methodCallSegmentHasExpressionLambdaArgument(MethodCallExpr expression) {
        return expression.getArguments()
                .stream()
                .anyMatch(argument -> argument instanceof LambdaExpr lambdaExpr
                        && lambdaExpr.getExpressionBody().isPresent()
                );
    }

    private int rootLineWidth(Expression root, String text) {
        return root.getRange()
                .map(range -> Math.max(0, range.begin.column - 1) + text.length())
                .orElseGet(() -> currentIndentedWidth.applyAsInt(text));
    }

    private Doc inlineMethodCall(MethodCallExpr expression) {
        Doc scope = expression.getScope().map(expressionRenderer).orElse(Doc.EMPTY);
        String typeArguments = expression.getTypeArguments()
                .map(arguments -> "<" + types.compactJoinTypeLike(arguments) + ">")
                .orElse("");
        String arguments = "(" + compactSource.compactJoin(expression.getArguments()) + ")";
        return Doc.concat(scope, Doc.text("." + typeArguments + expression.getNameAsString() + arguments));
    }

    private Doc promotedMethodCallRoot(
            MethodCallExpr expression,
            ToIntFunction<String> firstLineWidth
    ) {
        Optional<Doc> sourceMultilineArguments =
            comments.speculatively(() -> calls.sourceMultilineArguments(expression));
        if (sourceMultilineArguments.isPresent()) {
            return sourceMultilineArguments.orElseThrow();
        }
        if (promotedRootArgumentsShouldBreak(expression, firstLineWidth)) {
            return brokenPromotedMethodCallRoot(expression);
        }
        if (promotedNoArgRootScopeOverflows(expression, firstLineWidth)) {
            return expression.getScope()
                    .filter(FieldAccessExpr.class::isInstance)
                    .map(FieldAccessExpr.class::cast)
                    .map(scope -> promotedFieldAccessRootMethodCall(scope, expression))
                    .or(() -> expression.getScope().map(
                            scope -> Doc.concat(
                                expressionRenderer.apply(scope),
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
            ToIntFunction<String> firstLineWidth
    ) {
        if (expression.getArguments().size() <= 1) {
            return false;
        }
        String compact = compactSource.compact(expression);
        return currentIndentedWidth.applyAsInt(compact) > options.lineWidth()
            || rootLineWidth(expression, compact) > options.lineWidth()
            || (methodChainPlanner.methodCallStartsAfterScopeLine(expression)
                && selectorLineWidth(expression, compact) > options.lineWidth())
            || ((sourceMultilineTypeLikeRoot(expression) || methodChainPlanner.methodCallStartsAfterScopeLine(expression))
                && firstLineWidth.applyAsInt(compact) > options.lineWidth());
    }

    private ToIntFunction<String> lineWidth(LayoutWidth.LineBudget lineBudget) {
        return text -> layoutWidth.line(lineBudget, text);
    }

    private int selectorLineWidth(MethodCallExpr expression, String text) {
        return expression.getName()
                .getRange()
                .map(range -> Math.max(0, range.begin.column - 1) + text.length())
                .orElseGet(() -> currentIndentedWidth.applyAsInt(text));
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
        return methodCallChainSegment(expression, reserveStatementTerminator, continuationStatementWidth);
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
        return rootRendering != MethodCallChainSourcePlanner.ChainRootRendering.BROKEN_OBJECT_CREATION
            && !sourceShapePolicy.objectCreationArgumentsSpanMultipleLines(root);
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
            ToIntFunction<String> firstLineWidth
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
            return Doc.concat(
                rootDoc,
                objectRootContinuation(brokenMethodCallChainSegment(call, finalSegmentSuffix))
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
        if (
            sourceShapePolicy.expressionLambdaStartsOnSelectorLine(expression)
            && expressionLambdaSpansMultipleLines(expression)
        ) {
            Optional<Doc> huggableExpressionLambda = comments.speculatively(
                () -> huggableExpressionLambdaArguments.apply(prefix, expression.getArguments())
            );
            if (huggableExpressionLambda.isPresent()) {
                Optional<Doc> packedBodyChain = comments.speculatively(
                    () -> packedSegmentExpressionLambdaBodyChain(
                        expression,
                        prefix,
                        compactSegmentWidth,
                        finalSegmentSuffix
                    )
                );
                if (packedBodyChain.isPresent()) {
                    return Doc.concat(segmentPrefix, packedBodyChain.orElseThrow());
                }
                if (expressionLambdaSegmentBodyOpenerOverflows(expression, prefix, compactSegmentWidth)) {
                    return brokenMethodCallSegment(expression, prefix, segmentPrefix, finalSegmentSuffix);
                }
                return Doc.concat(segmentPrefix, huggableExpressionLambda.orElseThrow(), finalSegmentSuffix.doc());
            }
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

    private Optional<Doc> packedSegmentExpressionLambdaBodyChain(
            MethodCallExpr expression,
            String prefix,
            ToIntFunction<String> compactSegmentWidth,
            MethodCallChainTail finalSegmentSuffix
    ) {
        return expressionLambdaArgumentPlan.apply(prefix, expression.getArguments())
                .filter(ExpressionLambdaArgumentLayout.Plan::bodyFirstSourceLineFits)
                .flatMap(plan ->
                    plan.bodyExpression() instanceof MethodCallExpr methodCall && sourceFirstLineIsOnlyChainRoot(
                        methodCall
                    )
                        ? packedExpressionLambdaBodyChain(
                                plan.firstLine(),
                                methodCall,
                                line -> methodCallSegmentWidth(expression, line, compactSegmentWidth))
                                .map(body -> packedSegmentExpressionLambda(plan, body, finalSegmentSuffix))
                        : Optional.empty()
                );
    }

    private Doc packedSegmentExpressionLambda(
            ExpressionLambdaArgumentLayout.Plan plan,
            Doc body,
            MethodCallChainTail finalSegmentSuffix
    ) {
        return Doc.concat(
            Doc.text(plan.firstLine() + " "),
            Doc.indent(body),
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

    private boolean expressionLambdaSegmentBodyOpenerOverflows(
            MethodCallExpr expression,
            String prefix,
            ToIntFunction<String> compactSegmentWidth
    ) {
        return expressionLambdaArgumentPlan.apply(prefix, expression.getArguments())
                .filter(plan -> !plan.bodyFirstSourceLineFits())
                .filter(plan -> plan.bodyOpenerFitsOnContinuation(continuationStatementWidth, options.lineWidth()))
                .filter(plan -> plan.bodyOpenerOverflows(
                        line -> methodCallSegmentWidth(expression, line, compactSegmentWidth),
                        options.lineWidth()
                ))
                .isPresent();
    }

    private Optional<Doc> sourceMultilineMethodCallSegmentArguments(
            String prefix,
            MethodCallExpr expression,
            MethodCallChainTail finalSegmentSuffix
    ) {
        if (
            !expression.getAllContainedComments().isEmpty()
            || !methodCallSegmentHasBlockLambdaArgument(expression)
            || !sourceShapePolicy.methodCallArgumentsSpanMultipleLines(expression)
        ) {
            return Optional.empty();
        }
        return Optional.of(
            Doc.concat(
                Doc.text(prefix + "("),
                Doc.indent(
                    Doc.concat(
                        Doc.HARD_LINE,
                        calls.methodCallArgumentList(prefix, expression.getArguments(), Doc.HARD_LINE)
                    )
                ),
                Doc.HARD_LINE,
                Doc.text(")" + finalSegmentSuffix)
            )
        );
    }

    private boolean expressionLambdaSpansMultipleLines(MethodCallExpr expression) {
        return expression.getArguments()
                .stream()
                .filter(LambdaExpr.class::isInstance)
                .map(LambdaExpr.class::cast)
                .filter(lambda -> lambda.getExpressionBody().isPresent())
                .flatMap(lambda -> lambda.getRange().stream())
                .anyMatch(range -> range.begin.line < range.end.line);
    }

    private String methodCallSegmentArgumentsWidthText(NodeList<Expression> arguments) {
        return arguments.stream()
                .map(argument -> rawSource.normalizeWhitespace(rawSource.rawWithoutOwnComment(argument)))
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
    }

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

    /**
     * Records the chain's flat-width decision when width is the actual cause of the break, so explain can report real
     * arithmetic instead of an opaque forced break.
     *
     * <p>Only a chain whose compact single-line form overflows the line budget is recorded as a width break: chains
     * forced apart purely by comments or by already-multiline source are not width decisions, so attributing them to
     * width would mislead. This is called after the printer has already committed to breaking, so it never changes the
     * layout that is produced.
     */
    private void recordChainWidthBreak(
            MethodCallExpr expression,
            MethodCallChainSourcePlanner.MethodCallChainAnalysis analysis,
            LayoutWidth.LineBudget lineBudget
    ) {
        String compact = compactSource.compact(expression);
        int flatWidth = layoutWidth.line(lineBudget, compact);
        if (flatWidth <= options.lineWidth()) {
            return;
        }
        int segments = analysis.calls().size() + 1;
        layoutDecisions.recordWidthBreak(
            "method chain",
            "java.expression:" + expression.getClass().getSimpleName(),
            chainPreview(compact),
            flatWidth,
            options.lineWidth(),
            segments
        );
    }

    /**
     * Builds a short headline snippet of the chain: the first two call selectors followed by an ellipsis when the chain
     * is longer, so the reader recognizes the construct without seeing the whole line.
     */
    private String chainPreview(String compact) {
        int firstCall = compact.indexOf('(');
        if (firstCall < 0) {
            return compact;
        }
        int firstClose = matchingClose(compact, firstCall);
        if (firstClose < 0) {
            return compact;
        }
        int secondDot = compact.indexOf('.', firstClose);
        if (secondDot < 0) {
            return compact.substring(0, firstClose + 1);
        }
        int secondCall = compact.indexOf('(', secondDot);
        if (secondCall < 0) {
            return compact.substring(0, firstClose + 1) + "…";
        }
        int secondClose = matchingClose(compact, secondCall);
        if (secondClose < 0) {
            return compact.substring(0, firstClose + 1) + "…";
        }
        String head = compact.substring(0, secondClose + 1);
        return secondClose + 1 < compact.length() ? head + "…" : head;
    }

    private int matchingClose(String text, int open) {
        int depth = 0;
        for (int index = open; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character == '(') {
                depth++;
            } else if (character == ')') {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }
        return -1;
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
     * comment onto the enclosing call's orphan pool even though the AST is otherwise unchanged, so the own slot no longer
     * holds it and it is dropped. Selecting by source position from the orphan pool — strictly after the scope ends and
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
                    continuationStatementWidth,
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
        return methodCallChainSegment(expression, nextCall, finalSegmentSuffix, continuationStatementWidth);
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
        List<Doc> sourceComments = trailingLineCommentsBeforeNextSegment(expression, next)
                .stream()
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

    private record MethodCallChainTail(String text) {
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
