package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.comments.BlockComment;
import com.github.javaparser.ast.comments.Comment;
import com.github.javaparser.ast.comments.LineComment;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
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
    private final RawSource rawSource;
    private final SourceShape sourceShape;
    private final MethodCallChainSourcePlanner methodChainPlanner;
    private final FormatterOptions options;
    private final CompactSourceText compactSource;
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
    private final BiFunction<String, NodeList<Expression>, Optional<ExpressionLambdaArgumentLayout.Plan>> expressionLambdaArgumentPlan;
    private final ToIntFunction<String> currentIndentedWidth;
    private final ToIntFunction<String> continuationStatementWidth;
    private final ToIntFunction<String> blockStatementWidth;

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
            BiFunction<String, NodeList<Expression>, Optional<ExpressionLambdaArgumentLayout.Plan>> expressionLambdaArgumentPlan,
            ToIntFunction<String> currentIndentedWidth,
            ToIntFunction<String> continuationStatementWidth,
            ToIntFunction<String> blockStatementWidth) {
        this.comments = context.comments;
        this.commentPlacement = context.commentPlacementPolicy;
        this.rawSource = context.rawSource;
        this.sourceShape = context.sourceShape;
        this.methodChainPlanner = new MethodCallChainSourcePlanner(context, currentIndentedWidth);
        this.options = context.options;
        this.compactSource = context.compactSource;
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
    }

    Optional<Doc> methodCallChain(MethodCallExpr expression) {
        return methodCallChain(expression, MethodCallBreakMode.AUTO);
    }

    Optional<Doc> forcedMethodCallChain(MethodCallExpr expression) {
        return methodCallChain(expression, MethodCallBreakMode.FORCED);
    }

    Optional<Doc> forcedMethodCallChainWithSemicolon(MethodCallExpr expression) {
        return methodCallChain(expression, MethodCallBreakMode.FORCED, ";");
    }

    Optional<Doc> compactRootWithBrokenFinalChainSegment(MethodCallExpr expression) {
        List<MethodCallExpr> calls = new ArrayList<>();
        Expression root = methodChainPlanner.methodCallChainRoot(expression, calls);
        if (root instanceof MethodCallExpr methodRoot && calls.size() == 1) {
            return compactRootWithBrokenFinalSegment(methodRoot, calls.getFirst());
        }
        if (methodChainPlanner.promotesFirstCall(root) && calls.size() == 2) {
            return compactRootWithBrokenFinalSegment(calls.getFirst(), calls.get(1));
        }
        return Optional.empty();
    }

    /**
     * Preserves an already-multiline call statement when the argument list itself spans source lines.
     *
     * <p>Statement rendering still owns the trailing semicolon, but the call printer owns the call-shape decision because
     * it already owns argument breaks and source-multiline argument layout.
     */
    Optional<Doc> sourceMultilineMethodCallStatement(
            MethodCallExpr expression,
            ExpressionStmt statement) {
        if (!rawSource.rawWithoutOwnComment(statement).contains("\n")) {
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
        return methodCallChain(expression, breakMode, "");
    }

    Optional<Doc> methodCallChain(
            MethodCallExpr expression,
            MethodCallBreakMode breakMode,
            String finalSegmentSuffix) {
        return methodCallChain(expression, breakMode, MethodCallChainTail.of(finalSegmentSuffix));
    }

    private Optional<Doc> methodCallChain(
            MethodCallExpr expression,
            MethodCallBreakMode breakMode,
            MethodCallChainTail finalSegmentSuffix) {
        MethodCallChainSourcePlanner.MethodCallChainAnalysis analysis = methodCallChainAnalysis(expression);
        boolean rootObjectCreationNeedsBreak = methodChainPlanner.rootObjectCreationNeedsBreak(analysis);
        boolean sourceMultilineArguments = chainHasSourceMultilineArguments(analysis);
        if ((!breakMode.isForced()
                        && !analysis.hasComments()
                        && !analysis.hasBlockLambdaArgument()
                        && !analysis.sourceMultilineChain()
                        && !sourceMultilineArguments
                        && !rootObjectCreationNeedsBreak
                        && currentIndentedWidth.applyAsInt(compactSource.compact(expression)) <= options.lineWidth())
                || expression.getScope().isEmpty()) {
            return Optional.empty();
        }
        Expression root = analysis.root();
        List<MethodCallExpr> calls = analysis.calls();
        if (calls.isEmpty()
                || (calls.size() < 2
                        && !(root instanceof MethodCallExpr)
                        && !forcedSingleCallPrefixOverflows(breakMode, expression)
                        && !(breakMode.isForced() && root instanceof ObjectCreationExpr)
                        && !rootObjectCreationNeedsBreak
                        && !analysis.sourceMultilineChain()
                        && !analysis.rootHasComments()
                        && !analysis.singleCommentedSegment())) {
            return Optional.empty();
        }
        if (breakMode.isForced()
                && calls.size() == 1
                && root.getAllContainedComments().isEmpty()
                && calls.getFirst().getAllContainedComments().isEmpty()
                && !methodCallSegmentHasComment(calls.getFirst())
                && !analysis.rootHasBlockLambdaArgument()) {
            Optional<Doc> compactRootWithBrokenSegment =
                    compactRootWithBrokenFinalSegment(root, calls.getFirst(), finalSegmentSuffix);
            if (compactRootWithBrokenSegment.isPresent()) {
                return compactRootWithBrokenSegment;
            }
        }
        if (calls.size() == 1 && root instanceof MethodCallExpr methodRoot) {
            Doc rootDoc = this.calls.sourceMultilineArguments(methodRoot).orElseGet(() -> expressionRenderer.apply(methodRoot));
            return Optional.of(Doc.concat(rootDoc, methodCallChainSegmentAttachedToRootClose(calls.getFirst(), finalSegmentSuffix)));
        }
        if (root instanceof FieldAccessExpr fieldAccess
                && fieldAccess.getScope() instanceof MethodCallExpr methodRoot
                && calls.size() == 1) {
            return Optional.of(Doc.concat(
                    expressionRenderer.apply(methodRoot),
                    Doc.indent(Doc.concat(
                            Doc.HARD_LINE,
                            appendFinalSegmentSuffix(fieldAccessMethodCallSegment(fieldAccess, calls.getFirst()), finalSegmentSuffix)))));
        }
        MethodCallChainSourcePlanner.MethodCallChainPlan chainPlan =
                methodChainPlanner.plan(analysis, breakMode.isForced());
        chainPlan = promoteFirstBlockLambdaCallWithLambdaBodyComments(analysis, chainPlan).orElse(chainPlan);
        root = chainPlan.root();
        calls = chainPlan.calls();
        Doc rootDoc = methodCallChainRootDoc(chainPlan);
        boolean firstSegmentAttachedToRoot = false;
        if (canAttachFirstSegmentToSimpleRoot(chainPlan, calls, analysis)) {
            MethodCallExpr firstCall = calls.getFirst();
            root = firstCall;
            calls = new ArrayList<>(calls.subList(1, calls.size()));
            rootDoc = inlineMethodCall(firstCall);
            firstSegmentAttachedToRoot = true;
        }
        if (calls.isEmpty()) {
            return Optional.of(appendFinalSegmentSuffix(rootDoc, finalSegmentSuffix));
        }
        if (!analysis.sourceMultilineChain()
                && root instanceof MethodCallExpr methodRoot
                && calls.size() == 1
                && root.getAllContainedComments().isEmpty()
                && !methodCallSegmentHasComment(calls.getFirst())
                && methodCallSegmentHasBlockLambdaArgument(calls.getFirst())
                && blockLambdaSegmentFirstLine(compactSource.compact(methodRoot), calls.getFirst())
                        .filter(firstLine -> blockStatementWidth.applyAsInt(firstLine) <= options.lineWidth())
                        .isPresent()) {
            return Optional.empty();
        }
        Doc rootTrailingComment = rootTrailingLineCommentBeforeFirstSegment(root, calls);
        if (rootTrailingComment != Doc.EMPTY) {
            if (root instanceof ObjectCreationExpr objectCreation
                    && chainPlan.rootRendering() == MethodCallChainSourcePlanner.ChainRootRendering.EXPRESSION_RENDERER) {
                rootDoc = brokenObjectCreationRenderer.apply(objectCreation);
            }
            rootDoc = Doc.concat(rootDoc, Doc.text(" "), rootTrailingComment);
            if (calls.size() == 1) {
                return Optional.of(Doc.concat(
                        rootDoc,
                        Doc.indent(Doc.concat(Doc.HARD_LINE, methodCallChainSegment(calls.getFirst(), finalSegmentSuffix)))));
            }
        }
        if (canKeepSuffixAttachedToPromotedBlockLambdaRoot(chainPlan, root, calls, finalSegmentSuffix)) {
            return Optional.of(Doc.concat(rootDoc, methodCallChainSegment(calls.getFirst(), finalSegmentSuffix)));
        }
        if (chainPlan.rootRendering() == MethodCallChainSourcePlanner.ChainRootRendering.BROKEN_OBJECT_CREATION
                && calls.size() == 1) {
            return Optional.of(Doc.concat(rootDoc, methodCallChainSegment(calls.getFirst(), finalSegmentSuffix)));
        }
        if (root instanceof MethodCallExpr methodRoot
                && calls.size() == 1
                && !firstSegmentAttachedToRoot
                && methodRootCanKeepSingleSuffixAttached(methodRoot)
                && methodCallSegmentHasNoOwnContainedComments(calls.getFirst())
                && !methodCallSegmentHasComment(calls.getFirst())) {
            if (methodCallSegmentHasBlockLambdaArgument(calls.getFirst())
                    && blockLambdaSegmentFirstLine(compactSource.compact(methodRoot), calls.getFirst())
                            .filter(firstLine -> blockStatementWidth.applyAsInt(firstLine) <= options.lineWidth())
                            .isPresent()) {
                return Optional.empty();
            }
            if (!analysis.sourceMultilineChain()
                    && sourceShape.methodCallArgumentsSpanMultipleLines(calls.getFirst())) {
                Optional<Doc> compactRootWithBrokenSegment =
                        compactRootWithBrokenFinalSegment(methodRoot, calls.getFirst(), finalSegmentSuffix);
                if (compactRootWithBrokenSegment.isPresent()) {
                    return compactRootWithBrokenSegment;
                }
            }
            Optional<Doc> sourceMultilineRoot = this.calls.sourceMultilineArguments(methodRoot);
            if (sourceMultilineRoot.isPresent()) {
                return Optional.of(Doc.concat(
                        sourceMultilineRoot.orElseThrow(),
                        methodCallChainSegmentAttachedToRootClose(calls.getFirst(), finalSegmentSuffix)));
            }
            if (chainPlan.rootRendering() == MethodCallChainSourcePlanner.ChainRootRendering.GROUPED_PROMOTED_METHOD_CALL) {
                if (methodCallSegmentHasBlockLambdaArgument(methodRoot)) {
                    return Optional.of(Doc.concat(rootDoc, methodCallChainSegment(calls.getFirst(), finalSegmentSuffix)));
                }
                return Optional.of(groupedPromotedRootWithSingleSegment(root, rootDoc, calls.getFirst(), finalSegmentSuffix));
            }
            return Optional.of(Doc.concat(rootDoc, methodCallChainSegment(calls.getFirst(), finalSegmentSuffix)));
        }
        return Optional.of(Doc.concat(
                rootDoc,
                Doc.indent(Doc.concat(
                        Doc.HARD_LINE,
                        Doc.join(Doc.HARD_LINE, methodCallChainSegments(calls, finalSegmentSuffix))))));
    }

    private boolean methodRootCanKeepSingleSuffixAttached(MethodCallExpr methodRoot) {
        if (methodRoot.getAllContainedComments().isEmpty()) {
            return true;
        }
        if (methodCallSegmentHasLineComments(methodRoot)
                && !methodCallSegmentHasLeadingLineComment(methodRoot)
                && !methodCallSegmentHasNameComment(methodRoot)) {
            return true;
        }
        return methodCallSegmentHasBlockLambdaArgument(methodRoot)
                && !methodCallSegmentHasLeadingLineComment(methodRoot)
                && !methodCallSegmentHasNameComment(methodRoot);
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
            MethodCallChainTail finalSegmentSuffix) {
        if (chainPlan.rootRendering() != MethodCallChainSourcePlanner.ChainRootRendering.GROUPED_PROMOTED_METHOD_CALL
                || !(root instanceof MethodCallExpr methodRoot)
                || calls.size() != 1
                || !methodCallSegmentHasBlockLambdaArgument(methodRoot)
                || !methodRootCanKeepSingleSuffixAttached(methodRoot)
                || methodCallSegmentHasComment(calls.getFirst())) {
            return false;
        }
        return true;
    }

    private Optional<MethodCallChainSourcePlanner.MethodCallChainPlan> promoteFirstBlockLambdaCallWithLambdaBodyComments(
            MethodCallChainSourcePlanner.MethodCallChainAnalysis analysis,
            MethodCallChainSourcePlanner.MethodCallChainPlan chainPlan) {
        List<MethodCallExpr> calls = analysis.calls();
        if (chainPlan.root() != analysis.root()
                || calls.size() < 2
                || !methodChainPlanner.promotesFirstCall(analysis.root())) {
            return Optional.empty();
        }
        MethodCallExpr firstCall = calls.getFirst();
        if (!methodCallSegmentHasBlockLambdaArgument(firstCall)
                || methodCallSegmentHasLeadingLineComment(firstCall)
                || methodCallSegmentHasNameComment(firstCall)) {
            return Optional.empty();
        }
        return Optional.of(new MethodCallChainSourcePlanner.MethodCallChainPlan(
                firstCall,
                new ArrayList<>(calls.subList(1, calls.size())),
                MethodCallChainSourcePlanner.ChainRootRendering.GROUPED_PROMOTED_METHOD_CALL));
    }

    private boolean canAttachFirstSegmentToSimpleRoot(
            MethodCallChainSourcePlanner.MethodCallChainPlan chainPlan,
            List<MethodCallExpr> calls,
            MethodCallChainSourcePlanner.MethodCallChainAnalysis analysis) {
        if (chainPlan.rootRendering() != MethodCallChainSourcePlanner.ChainRootRendering.EXPRESSION_RENDERER
                || calls.size() < 2
                || analysis.hasComments()
                || !analysis.sourceMultilineChain()
                || chainPlan.root() instanceof MethodCallExpr
                || chainPlan.root() instanceof ObjectCreationExpr) {
            return false;
        }
        MethodCallExpr firstCall = calls.getFirst();
        return firstSelectorStartsOnRootLine(chainPlan.root(), firstCall)
                && !sourceShape.methodCallArgumentsSpanMultipleLines(firstCall)
                && !methodCallSegmentHasBlockLambdaArgument(firstCall)
                && currentIndentedWidth.applyAsInt(compactSource.compact(firstCall)) <= options.lineWidth();
    }

    private boolean firstSelectorStartsOnRootLine(Expression root, MethodCallExpr firstCall) {
        return root.getRange()
                .flatMap(rootRange -> firstCall.getName().getRange()
                        .map(nameRange -> nameRange.begin.line == rootRange.end.line))
                .orElse(false);
    }

    private boolean forcedSingleCallPrefixOverflows(MethodCallBreakMode breakMode, MethodCallExpr expression) {
        return breakMode.isForced()
                && expression.getScope().isPresent()
                && methodCallSegmentHasBlockLambdaArgument(expression)
                && currentIndentedWidth.applyAsInt(calls.methodCallPrefix(expression) + "(") > options.lineWidth();
    }

    private boolean chainHasSourceMultilineArguments(
            MethodCallChainSourcePlanner.MethodCallChainAnalysis analysis) {
        if (analysis.root() instanceof MethodCallExpr methodRoot
                && !analysis.calls().isEmpty()
                && sourceShape.methodCallArgumentsSpanMultipleLines(methodRoot)) {
            return true;
        }
        return analysis.calls().stream()
                .limit(Math.max(0, analysis.calls().size() - 1))
                .anyMatch(sourceShape::methodCallArgumentsSpanMultipleLines);
    }

    private Doc methodCallChainRootDoc(MethodCallChainSourcePlanner.MethodCallChainPlan chainPlan) {
        return switch (chainPlan.rootRendering()) {
            case INLINE_PROMOTED_METHOD_CALL -> chainPlan.root() instanceof MethodCallExpr methodCall
                    ? inlineMethodCall(methodCall)
                    : expressionRenderer.apply(chainPlan.root());
            case GROUPED_PROMOTED_METHOD_CALL -> chainPlan.root() instanceof MethodCallExpr methodCall
                    ? groupedPromotedMethodCall(methodCall)
                    : expressionRenderer.apply(chainPlan.root());
            case BROKEN_OBJECT_CREATION -> brokenObjectCreationRenderer.apply((ObjectCreationExpr) chainPlan.root());
            case EXPRESSION_RENDERER -> expressionRenderer.apply(chainPlan.root());
        };
    }

    private Doc groupedPromotedMethodCall(MethodCallExpr expression) {
        Optional<Doc> sourceMultilineArguments = calls.sourceMultilineArguments(expression);
        if (sourceMultilineArguments.isPresent()) {
            return sourceMultilineArguments.orElseThrow();
        }
        Optional<Doc> huggableExpressionLambda = groupedPromotedExpressionLambda(expression);
        if (huggableExpressionLambda.isPresent()) {
            return huggableExpressionLambda.orElseThrow();
        }
        if (methodCallSegmentHasBlockLambdaArgument(expression)) {
            return blockLambdaSegmentFirstLine(compactSource.compact(expression.getScope().orElseThrow()), expression)
                    .filter(firstLine -> blockStatementWidth.applyAsInt(firstLine) <= options.lineWidth())
                    .map(ignored -> expressionRenderer.apply(expression))
                    .orElseGet(() -> Doc.concat(
                            expressionRenderer.apply(expression.getScope().orElseThrow()),
                            Doc.indent(Doc.concat(Doc.HARD_LINE, methodCallChainSegment(expression)))));
        }
        return expression.getScope()
                .map(scope -> Doc.group(Doc.concat(
                        expressionRenderer.apply(scope),
                        Doc.indent(Doc.concat(Doc.SOFT_LINE, methodCallChainSegment(expression))))))
                .orElseGet(() -> expressionRenderer.apply(expression));
    }

    private Optional<Doc> groupedPromotedExpressionLambda(MethodCallExpr expression) {
        if (!expressionLambdaStartsOnSelectorLine(expression) || !expressionLambdaSpansMultipleLines(expression)) {
            return Optional.empty();
        }
        return expression.getScope()
                .map(scope -> compactSource.compact(scope)
                        + "."
                        + expression.getTypeArguments()
                                .map(arguments -> "<" + types.compactJoinTypeLike(arguments) + ">")
                                .orElse("")
                        + expression.getNameAsString())
                .flatMap(prefix -> huggableExpressionLambdaArguments.apply(prefix, expression.getArguments()));
    }

    private Doc groupedPromotedRootWithSingleSegment(
            Expression root,
            Doc rootDoc,
            MethodCallExpr expression,
            MethodCallChainTail finalSegmentSuffix) {
        if (methodCallSegmentHasBlockLambdaArgument(expression)) {
            return blockLambdaSegmentFirstLine(compactSource.compact(root), expression)
                    .filter(firstLine -> blockStatementWidth.applyAsInt(firstLine) <= options.lineWidth())
                    .map(ignored -> Doc.concat(rootDoc, methodCallChainSegment(expression, finalSegmentSuffix)))
                    .orElseGet(() -> Doc.concat(
                            rootDoc,
                            Doc.indent(Doc.concat(Doc.HARD_LINE, methodCallChainSegment(expression, finalSegmentSuffix)))));
        }
        if (expressionLambdaStartsOnSelectorLine(expression)
                && expressionLambdaSpansMultipleLines(expression)
                && expressionLambdaBodyOpenerOverflows(
                        root,
                        compactRootCallPrefix(root, expression),
                        expression.getArguments())) {
            return Doc.concat(
                    rootDoc,
                    Doc.indent(Doc.concat(Doc.HARD_LINE, methodCallChainSegment(expression, finalSegmentSuffix))));
        }
        return Doc.group(Doc.concat(
                rootDoc,
                Doc.indent(Doc.concat(Doc.SOFT_LINE, methodCallChainSegment(expression, finalSegmentSuffix)))));
    }

    private Optional<String> blockLambdaSegmentFirstLine(String root, MethodCallExpr expression) {
        String prefix = root + "."
                + expression.getTypeArguments()
                        .map(arguments -> "<" + types.compactJoinTypeLike(arguments) + ">")
                        .orElse("")
                + expression.getNameAsString();
        return huggableBlockLambdaFirstLine.apply(prefix, expression.getArguments());
    }

    private Optional<Doc> compactRootWithBrokenFinalSegment(Expression root, MethodCallExpr call) {
        return compactRootWithBrokenFinalSegment(root, call, MethodCallChainTail.EMPTY);
    }

    private Optional<Doc> compactRootWithBrokenFinalSegment(
            Expression root,
            MethodCallExpr call,
            MethodCallChainTail finalSegmentSuffix) {
        if (!(root instanceof ObjectCreationExpr || root instanceof MethodCallExpr) || call.getArguments().isEmpty()) {
            return Optional.empty();
        }
        if (root instanceof MethodCallExpr methodRoot
                && sourceShape.methodCallArgumentsSpanMultipleLines(methodRoot)) {
            return Optional.empty();
        }
        if (root instanceof ObjectCreationExpr objectCreation
                && sourceShape.objectCreationArgumentsSpanMultipleLines(objectCreation)) {
            return Optional.empty();
        }
        String typeArguments = call.getTypeArguments()
                .map(arguments -> "<" + types.compactJoinTypeLike(arguments) + ">")
                .orElse("");
        String callPrefix = compactSource.compact(root) + "." + typeArguments + call.getNameAsString();
        if (!compactRootFirstLineFits(root, callPrefix, call.getArguments())) {
            return Optional.empty();
        }
        Optional<Doc> huggableLambda = huggableBlockLambdaArguments.apply(callPrefix, call.getArguments());
        if (huggableLambda.isPresent()) {
            return Optional.of(Doc.concat(huggableLambda.orElseThrow(), finalSegmentSuffix.doc()));
        }
        if (expressionLambdaStartsOnSelectorLine(call) && expressionLambdaSpansMultipleLines(call)) {
            Optional<ExpressionLambdaArgumentLayout.Plan> expressionLambdaPlan =
                    expressionLambdaArgumentPlan.apply(callPrefix, call.getArguments());
            if (expressionLambdaPlan.isEmpty()) {
                return Optional.empty();
            }
            Optional<Doc> huggableExpressionLambda = huggableExpressionLambdaArguments.apply(callPrefix, call.getArguments());
            if (huggableExpressionLambda.isPresent()) {
                if (expressionLambdaPlan.orElseThrow()
                        .bodyOpenerOverflows(
                                line -> compactRootLineWidth(root, line),
                                options.lineWidth())) {
                    return Optional.empty();
                }
                return Optional.of(Doc.concat(huggableExpressionLambda.orElseThrow(), finalSegmentSuffix.doc()));
            }
        }
        String prefix = callPrefix + "(";
        if (currentIndentedWidth.applyAsInt(prefix + ")") > options.lineWidth()) {
            return Optional.empty();
        }
        return Optional.of(Doc.concat(
                Doc.text(prefix),
                Doc.indent(Doc.concat(
                        Doc.HARD_LINE,
                        calls.methodCallArgumentList(call.getArguments(), Doc.HARD_LINE))),
                Doc.HARD_LINE,
                Doc.text(")" + finalSegmentSuffix)));
    }

    private boolean compactRootFirstLineFits(
            Expression root,
            String callPrefix,
            NodeList<Expression> arguments) {
        Optional<String> blockLambdaFirstLine = huggableBlockLambdaFirstLine.apply(callPrefix, arguments);
        if (blockLambdaFirstLine.filter(firstLine -> compactRootLineWidth(root, firstLine) > options.lineWidth()).isPresent()) {
            return false;
        }
        Optional<ExpressionLambdaArgumentLayout.Plan> expressionLambdaPlan =
                expressionLambdaArgumentPlan.apply(callPrefix, arguments);
        return expressionLambdaPlan
                .map(plan -> plan.firstLineFits(line -> compactRootLineWidth(root, line), options.lineWidth()))
                .orElse(true);
    }

    private int compactRootLineWidth(Expression root, String firstLine) {
        return root.getRange()
                .map(range -> Math.max(0, range.begin.column + 1) + firstLine.length())
                .orElseGet(() -> currentIndentedWidth.applyAsInt(firstLine));
    }

    private boolean expressionLambdaBodyOpenerOverflows(
            Expression root,
            String callPrefix,
            NodeList<Expression> arguments) {
        return expressionLambdaArgumentPlan.apply(callPrefix, arguments)
                .filter(plan -> plan.bodyOpenerFitsOnContinuation(continuationStatementWidth, options.lineWidth()))
                .filter(plan -> plan.bodyOpenerOverflows(
                        line -> compactRootLineWidth(root, line),
                        options.lineWidth()))
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
        return Optional.of(Doc.concat(
                expressionRenderer.apply(root.orElseThrow()),
                Doc.indent(Doc.concat(Doc.HARD_LINE, Doc.join(Doc.HARD_LINE, segments)))));
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
        return analysis.sourceMultilineChain()
                || (analysis.root() instanceof ObjectCreationExpr objectCreation
                        && sourceShape.objectCreationArgumentsSpanMultipleLines(objectCreation));
    }

    private MethodCallChainSourcePlanner.MethodCallChainAnalysis methodCallChainAnalysis(MethodCallExpr expression) {
        return methodChainPlanner.analyze(
                expression,
                this::methodCallSegmentHasComment,
                this::methodCallSegmentHasNameComment,
                this::methodCallSegmentHasArgumentGapComment,
                this::methodCallSegmentHasBlockLambdaArgument,
                this::methodCallChainHasTrailingLineComments);
    }

    boolean methodCallChainRootIsObjectCreation(MethodCallExpr expression) {
        return methodChainPlanner.rootIsObjectCreation(expression);
    }

    boolean methodCallChainRootIsFieldAccess(MethodCallExpr expression) {
        return methodChainPlanner.rootIsFieldAccess(expression);
    }

    String methodCallChainFirstLine(MethodCallExpr expression) {
        MethodCallChainSourcePlanner.MethodCallChainAnalysis analysis = methodCallChainAnalysis(expression);
        if (analysis.root() instanceof MethodCallExpr methodRoot
                && analysis.calls().size() == 1
                && sourceShape.methodCallArgumentsSpanMultipleLines(methodRoot)) {
            return calls.methodCallPrefix(methodRoot) + "(";
        }
        if (analysis.root() instanceof MethodCallExpr && analysis.calls().size() == 1) {
            return compactSource.compact(expression);
        }
        MethodCallChainSourcePlanner.MethodCallChainPlan plan = methodChainPlanner.plan(analysis, true);
        if (plan.root() instanceof MethodCallExpr methodRoot
                && sourceShape.methodCallArgumentsSpanMultipleLines(methodRoot)) {
            return calls.methodCallPrefix(methodRoot) + "(";
        }
        if (plan.rootRendering() == MethodCallChainSourcePlanner.ChainRootRendering.BROKEN_OBJECT_CREATION) {
            return objectCreationPrefix.apply((ObjectCreationExpr) plan.root()) + "(";
        }
        return compactSource.compact(plan.root());
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
        return expression.getName().getComment()
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
        return expression.getArguments().stream()
                .anyMatch(argument -> argument instanceof LambdaExpr lambdaExpr
                        && lambdaExpr.getBody().isBlockStmt());
    }

    private Doc inlineMethodCall(MethodCallExpr expression) {
        Doc scope = expression.getScope().map(expressionRenderer).orElse(Doc.EMPTY);
        String typeArguments = expression.getTypeArguments()
                .map(arguments -> "<" + types.compactJoinTypeLike(arguments) + ">")
                .orElse("");
        String arguments = "(" + compactSource.compactJoin(expression.getArguments()) + ")";
        return Doc.concat(scope, Doc.text("." + typeArguments + expression.getNameAsString() + arguments));
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

    private Doc methodCallChainSegmentAttachedToRootClose(MethodCallExpr expression, MethodCallChainTail finalSegmentSuffix) {
        return methodCallChainSegment(
                expression,
                Optional.empty(),
                finalSegmentSuffix,
                segment -> methodCallSegmentWidth(
                        expression,
                        ")" + segment,
                        text -> blockStatementWidth.applyAsInt(text)));
    }

    private Doc methodCallChainSegment(
            MethodCallExpr expression,
            boolean reserveStatementTerminator,
            ToIntFunction<String> compactSegmentWidth) {
        return methodCallChainSegment(
                expression,
                reserveStatementTerminator,
                compactSegmentWidth,
                MethodCallChainTail.EMPTY);
    }

    private Doc methodCallChainSegment(
            MethodCallExpr expression,
            boolean reserveStatementTerminator,
            ToIntFunction<String> compactSegmentWidth,
            MethodCallChainTail finalSegmentSuffix) {
        String typeArguments = expression.getTypeArguments()
                .map(arguments -> "<" + types.compactJoinTypeLike(arguments) + ">")
                .orElse("");
        String prefix = "." + typeArguments + expression.getNameAsString();
        Doc segmentPrefix = methodCallSegmentPrefix(expression);
        if (expression.getArguments().isEmpty()) {
            Optional<Doc> commentedArguments = calls.emptyMethodCallArguments(prefix, expression);
            if (commentedArguments.isPresent()) {
                return Doc.concat(segmentPrefix, commentedArguments.orElseThrow(), finalSegmentSuffix.doc());
            }
            return Doc.concat(segmentPrefix, Doc.text(prefix + "()" + finalSegmentSuffix));
        }
        Optional<Doc> sourceMultilineArguments =
                sourceMultilineMethodCallSegmentArguments(prefix, expression, finalSegmentSuffix);
        if (sourceMultilineArguments.isPresent()) {
            return Doc.concat(segmentPrefix, sourceMultilineArguments.orElseThrow());
        }
        Optional<Doc> huggableLambda = huggableBlockLambdaArguments.apply(prefix, expression.getArguments());
        if (huggableLambda.isPresent()) {
            return Doc.concat(segmentPrefix, huggableLambda.orElseThrow(), finalSegmentSuffix.doc());
        }
        Optional<Doc> commentedExpressionLambda = commentedExpressionLambdaArgument.apply(prefix, expression);
        if (commentedExpressionLambda.isPresent()) {
            return Doc.concat(segmentPrefix, commentedExpressionLambda.orElseThrow(), finalSegmentSuffix.doc());
        }
        if (expressionLambdaStartsOnSelectorLine(expression) && expressionLambdaSpansMultipleLines(expression)) {
            Optional<Doc> huggableExpressionLambda = huggableExpressionLambdaArguments.apply(prefix, expression.getArguments());
            if (huggableExpressionLambda.isPresent()) {
                if (expressionLambdaSegmentBodyOpenerOverflows(expression, prefix, compactSegmentWidth)) {
                    return brokenMethodCallSegment(expression, prefix, segmentPrefix, finalSegmentSuffix);
                }
                return Doc.concat(segmentPrefix, huggableExpressionLambda.orElseThrow(), finalSegmentSuffix.doc());
            }
        }
        Optional<Doc> commentedArguments = commentedExpressionLists.parenthesized(prefix, expression, expression.getArguments());
        if (commentedArguments.isPresent()) {
            return Doc.concat(segmentPrefix, commentedArguments.orElseThrow(), finalSegmentSuffix.doc());
        }
        String compactSegment = prefix + "(" + methodCallSegmentArgumentsWidthText(expression.getArguments())
                + ")" + finalSegmentSuffix;
        if (reserveStatementTerminator
                && methodCallSegmentWidth(expression, compactSegment, compactSegmentWidth)
                        > options.lineWidth()) {
            return brokenMethodCallSegment(expression, prefix, segmentPrefix, finalSegmentSuffix);
        }
        return Doc.concat(segmentPrefix, Doc.group(Doc.concat(
                Doc.text(prefix + "("),
                Doc.indent(Doc.concat(
                        Doc.SOFT_LINE,
                        calls.methodCallArgumentList(expression.getArguments(), Doc.LINE))),
                Doc.SOFT_LINE,
                Doc.text(")" + finalSegmentSuffix))));
    }

    private Doc brokenMethodCallSegment(
            MethodCallExpr expression,
            String prefix,
            Doc segmentPrefix,
            MethodCallChainTail finalSegmentSuffix) {
        return Doc.concat(
                segmentPrefix,
                Doc.text(prefix + "("),
                Doc.indent(Doc.concat(
                        Doc.HARD_LINE,
                        calls.methodCallArgumentList(expression.getArguments(), Doc.HARD_LINE))),
                Doc.HARD_LINE,
                Doc.text(")" + finalSegmentSuffix));
    }

    private boolean expressionLambdaSegmentBodyOpenerOverflows(
            MethodCallExpr expression,
            String prefix,
            ToIntFunction<String> compactSegmentWidth) {
        return expressionLambdaArgumentPlan.apply(prefix, expression.getArguments())
                .filter(plan -> !plan.bodyFirstSourceLineFits())
                .filter(plan -> plan.bodyOpenerFitsOnContinuation(continuationStatementWidth, options.lineWidth()))
                .filter(plan -> plan.bodyOpenerOverflows(
                        line -> methodCallSegmentWidth(expression, line, compactSegmentWidth),
                        options.lineWidth()))
                .isPresent();
    }

    private Optional<Doc> sourceMultilineMethodCallSegmentArguments(
            String prefix,
            MethodCallExpr expression,
            MethodCallChainTail finalSegmentSuffix) {
        if (!expression.getAllContainedComments().isEmpty()
                || !methodCallSegmentHasBlockLambdaArgument(expression)
                || !sourceShape.methodCallArgumentsSpanMultipleLines(expression)) {
            return Optional.empty();
        }
        return Optional.of(Doc.concat(
                Doc.text(prefix + "("),
                Doc.indent(Doc.concat(
                        Doc.HARD_LINE,
                        calls.methodCallArgumentList(expression.getArguments(), Doc.HARD_LINE))),
                Doc.HARD_LINE,
                Doc.text(")" + finalSegmentSuffix)));
    }

    private boolean expressionLambdaStartsOnSelectorLine(MethodCallExpr expression) {
        Optional<Integer> selectorLine = expression.getName().getRange().map(range -> range.begin.line);
        if (selectorLine.isEmpty()) {
            return false;
        }
        return expression.getArguments().stream()
                .filter(LambdaExpr.class::isInstance)
                .map(LambdaExpr.class::cast)
                .filter(lambda -> lambda.getExpressionBody().isPresent())
                .flatMap(lambda -> lambda.getRange().stream())
                .anyMatch(range -> range.begin.line == selectorLine.orElseThrow());
    }

    private boolean expressionLambdaSpansMultipleLines(MethodCallExpr expression) {
        return expression.getArguments().stream()
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
            ToIntFunction<String> fallbackWidth) {
        return expression.getName().getRange()
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
        Doc leading = Doc.concat(leadingLineCommentsBeforeSegment(expression).stream()
                .map(comments::comment)
                .filter(comment -> comment != Doc.EMPTY)
                .map(comment -> Doc.concat(comment, Doc.HARD_LINE))
                .toList());
        Optional<Comment> rawNameComment = expression.getName().getComment()
                .filter(comment -> comment instanceof LineComment || comment instanceof BlockComment)
                .filter(comment -> CommentIndex.startsBefore(comment, expression.getName()));
        Doc nameComment = rawNameComment.map(comments::comment).orElse(Doc.EMPTY);
        if (nameComment == Doc.EMPTY) {
            return leading;
        }
        Doc namePrefix = rawNameComment.filter(comment -> comment instanceof BlockComment
                        && CommentIndex.startsOnSameLine(comment, expression.getName()))
                .map(ignored -> Doc.concat(nameComment, Doc.text(" ")))
                .orElseGet(() -> Doc.concat(nameComment, Doc.HARD_LINE));
        return Doc.concat(leading, namePrefix);
    }

    private List<JavaCommentTrivia> leadingLineCommentsBeforeSegment(MethodCallExpr expression) {
        Optional<Expression> scope = expression.getScope();
        if (scope.isEmpty()) {
            return List.of();
        }
        int scopeEndLine = CommentIndex.endLine(scope.orElseThrow(), Integer.MIN_VALUE);
        int nameBeginLine = CommentIndex.beginLine(expression.getName(), Integer.MAX_VALUE);
        return commentPlacement.containedComments(expression).stream()
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
            segments.add(methodCallChainSegment(calls.get(i), next, next.isEmpty() ? finalSegmentSuffix : MethodCallChainTail.EMPTY));
        }
        return segments;
    }

    private Doc methodCallChainSegment(MethodCallExpr expression, Optional<MethodCallExpr> nextCall) {
        return methodCallChainSegment(expression, nextCall, MethodCallChainTail.EMPTY);
    }

    private Doc methodCallChainSegment(
            MethodCallExpr expression,
            Optional<MethodCallExpr> nextCall,
            MethodCallChainTail finalSegmentSuffix) {
        return methodCallChainSegment(expression, nextCall, finalSegmentSuffix, continuationStatementWidth);
    }

    private Doc methodCallChainSegment(
            MethodCallExpr expression,
            Optional<MethodCallExpr> nextCall,
            MethodCallChainTail finalSegmentSuffix,
            ToIntFunction<String> compactSegmentWidth) {
        MethodCallChainTail segmentSuffix = nextCall.isEmpty() ? finalSegmentSuffix : MethodCallChainTail.EMPTY;
        Doc segment = methodCallChainSegment(expression, nextCall.isEmpty(), compactSegmentWidth, segmentSuffix);
        Doc trailingComment = nextCall
                .map(next -> trailingLineCommentBeforeNextSegment(expression, Optional.of(next)))
                .orElseGet(() -> finalTrailingLineComment(expression));
        if (!finalSegmentSuffix.isEmpty() && nextCall.isEmpty()) {
            return trailingComment == Doc.EMPTY
                    ? segment
                    : Doc.concat(segment, Doc.text(" "), trailingComment);
        }
        return trailingComment == Doc.EMPTY ? segment : Doc.concat(segment, Doc.text(" "), trailingComment);
    }

    private Doc appendFinalSegmentSuffix(Doc doc, MethodCallChainTail finalSegmentSuffix) {
        return finalSegmentSuffix.appendTo(doc);
    }

    private Doc trailingLineCommentBeforeNextSegment(Node expression, Optional<MethodCallExpr> nextCall) {
        if (nextCall.isEmpty()) {
            return Doc.EMPTY;
        }
        MethodCallExpr next = nextCall.orElseThrow();
        List<Doc> sourceComments = trailingLineCommentsBeforeNextSegment(expression, next).stream()
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
        return lineCommentCandidatesBeforeNextSegment(next).stream()
                .filter(comment -> comment.startsAfterNodeOnSameLine(previous))
                .filter(comment -> comment.startsBeforeBeginLine(next.getName()))
                .toList();
    }

    private List<JavaCommentTrivia> lineCommentCandidatesBeforeNextSegment(MethodCallExpr next) {
        if (!next.getArguments().isEmpty()) {
            return commentPlacement.lineCommentsBeforeFirst(next, next.getArguments().get(0));
        }
        return commentPlacement.containedComments(next).stream()
                .filter(JavaCommentTrivia::isLine)
                .toList();
    }

    /**
     * Keeps a final segment's same-line comment after the rendered call, even when the call arguments break.
     */
    private Doc finalTrailingLineComment(MethodCallExpr expression) {
        List<Doc> sourceComments = finalTrailingLineComments(expression).stream()
                .map(comments::comment)
                .filter(comment -> comment != Doc.EMPTY)
                .toList();
        return sourceComments.isEmpty() ? Doc.EMPTY : Doc.join(Doc.text(" "), sourceComments);
    }

    private List<JavaCommentTrivia> finalTrailingLineComments(MethodCallExpr expression) {
        List<JavaCommentTrivia> sourceComments = new ArrayList<>();
        commentPlacement.trailingLineComment(expression).ifPresent(sourceComments::add);
        commentPlacement.containedComments(expression).stream()
                .filter(JavaCommentTrivia::isLine)
                .filter(comment -> comment.startsAfterNodeOnSameLine(expression))
                .filter(comment -> sourceComments.stream().noneMatch(existing -> existing.comment() == comment.comment()))
                .forEach(sourceComments::add);
        return sourceComments;
    }

    private Doc fieldAccessMethodCallSegment(FieldAccessExpr fieldAccess, MethodCallExpr methodCall) {
        String typeArguments = methodCall.getTypeArguments()
                .map(arguments -> "<" + types.compactJoinTypeLike(arguments) + ">")
                .orElse("");
        return Doc.text(fieldAccessSuffixAfterMethodRoot(fieldAccess) + "." + typeArguments + methodCall.getNameAsString()
                + "(" + compactSource.compactJoin(methodCall.getArguments()) + ")");
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
