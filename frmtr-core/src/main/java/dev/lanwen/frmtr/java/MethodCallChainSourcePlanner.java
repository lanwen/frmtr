package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import dev.lanwen.frmtr.FormatterOptions;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Plans source-sensitive method-call chain roots before {@link MethodCallPrinter} assembles docs.
 *
 * <p>This helper owns the chain decisions that depend on source shape rather than rendered document structure: finding
 * the structural root, detecting selectors that were already split onto later source lines, promoting type-like roots
 * and static builder roots, and keeping small constructor roots compact when the original source did.
 * The boundary exists so the method-call printer can stay focused on argument docs, comments, and final chain assembly.
 *
 * <p>Callers still provide comment and block-lambda predicates because those are rendering concerns owned by
 * {@link MethodCallPrinter}. This helper combines those facts with AST ranges and compact width checks, but it does not
 * render comments, build method-call segment docs, or decide ordinary argument-list layout.
 */
final class MethodCallChainSourcePlanner {

    private final ObjectCreationLayoutPolicy objectCreationLayoutPolicy;

    private final CompactSourceText compactSource;

    private final SourceShapePolicy sourceShapePolicy;

    private final SourceText sourceText;

    private final FormatterOptions options;

    private final ToIntFunction<String> currentIndentedWidth;

    MethodCallChainSourcePlanner(JavaFormatContext context, ToIntFunction<String> currentIndentedWidth) {
        this.objectCreationLayoutPolicy = context.objectCreationLayoutPolicy;
        this.compactSource = context.compactSource;
        this.sourceShapePolicy = context.sourceShapePolicy;
        this.sourceText = context.sourceText;
        this.options = context.options;
        this.currentIndentedWidth = currentIndentedWidth;
    }

    /**
     * Names how a selected method-chain root should be printed after root promotion has adjusted the chain plan.
     *
     * <p>The enum is deliberately narrower than the chain collector: it only records the rendering policy for the root
     * expression. Segment collection, comment detection, and argument layout stay with the existing chain methods.
     */
    enum ChainRootRendering {
        /** Render the selected root through ordinary expression dispatch. */
        EXPRESSION_RENDERER,

        /** Render a promoted method-call root inline so its scope, name, and compact arguments stay on the root line. */
        INLINE_PROMOTED_METHOD_CALL,

        /** Render a promoted static-style call as a group that can break between the type-like scope and first call. */
        GROUPED_PROMOTED_METHOD_CALL,

        /** Render an object-creation root through the forced broken-constructor path selected by the caller. */
        BROKEN_OBJECT_CREATION,
    }

    /**
     * Carries the selected method-chain root, remaining call segments, and root rendering policy together.
     *
     * <p>This keeps root promotion from leaking boolean flags into the final chain assembly. The model does not own
     * segment rendering or decide whether a chain should be printed at all.
     */
    record MethodCallChainPlan(Expression root, List<MethodCallExpr> calls, ChainRootRendering rootRendering) {
        MethodCallChainPlan {
            calls = List.copyOf(calls);
        }
    }

    /**
     * Captures chain structure and source/comment traits once so eligibility and planning do not rescan calls.
     */
    record MethodCallChainAnalysis(
        Expression root,
        List<MethodCallExpr> calls,
        boolean hasComments,
        boolean hasBlockLambdaArgument,
        boolean rootHasBlockLambdaArgument,
        boolean rootHasComments,
        boolean sourceMultilineChain,
        boolean singleCommentedSegment,
        int firstCommentedSegment,
        boolean firstCallHasArgumentGapComment,
        boolean laterCallsHaveArgumentGapComment,
        boolean hasTrailingLineComments,
        boolean expressionSpansMultipleSourceLines
    ) {
        MethodCallChainAnalysis {
            calls = List.copyOf(calls);
        }
    }

    /**
     * Summarizes method-chain facts used by variable initializer layout.
     *
     * <p>Initializer layout decides where {@code =} and the continuation line go, but it should not rediscover chain
     * roots or classify type-like/static roots. This record keeps those source-shape facts with the chain planner while
     * still letting the declaration-specific layout apply its own comment and width checks.
     */
    record InitializerChainShape(
        boolean typeLikeRoot,
        boolean rootIsObjectCreation,
        boolean sourceMultilineChain,
        boolean singleCall,
        boolean tailHasArguments,
        boolean rootObjectCreationArgumentsSpanMultipleLines,
        boolean rootObjectCreationArgumentsAreWidthDriven,
        boolean expressionSpansMultipleSourceLines,
        boolean chainBreaksByRule
    ) {
        /**
         * SPIKE (fan-root-true-column, #190). Reports whether this initializer's chain root is an object creation whose
         * constructor arguments are always width-driven (never source-preserved) AND whose selector links reach the
         * canonical-fan threshold — the exact shape the object-creation-ROOT arm of
         * {@link VariableInitializerLayout#variableInitializerFanBestFitting} may fan source-neutrally. Roots that could
         * preserve source-multiline arguments are excluded so the fan doc stays column-invariant across passes.
         */
        boolean objectCreationRootFansSourceNeutrally() {
            return rootIsObjectCreation && rootObjectCreationArgumentsAreWidthDriven && chainBreaksByRule;
        }
        boolean shouldForceSourceMultilineInitializerChain() {
            return expressionSpansMultipleSourceLines && (typeLikeRoot || rootObjectCreationArgumentsSpanMultipleLines);
        }

        boolean shouldForceWideInitializerChain() {
            return typeLikeRoot;
        }

        boolean canUseDirectSourceMultilineInitializer() {
            return !typeLikeRoot;
        }

        boolean canUseCompactObjectCreationInitializer(
                boolean initializerStartsOnContinuationLine,
                boolean chainSpansMultipleSourceLines,
                boolean tailArgumentsSpanMultipleSourceLines
        ) {
            boolean sourceMultilineInitializer = chainSpansMultipleSourceLines || initializerStartsOnContinuationLine;
            return rootIsObjectCreation
                && !(chainSpansMultipleSourceLines && !singleCall)
                && !(!sourceMultilineInitializer && singleCall && tailHasArguments)
                && !(!initializerStartsOnContinuationLine && tailArgumentsSpanMultipleSourceLines && singleCall)
                && !rootObjectCreationArgumentsSpanMultipleLines;
        }
    }

    /**
     * Collects chain structure and source-line traits using AST ranges rather than rendered text.
     */
    MethodCallChainAnalysis analyze(
            MethodCallExpr expression,
            Predicate<MethodCallExpr> segmentHasComment,
            Predicate<MethodCallExpr> segmentHasNameComment,
            Predicate<MethodCallExpr> segmentHasArgumentGapComment,
            Predicate<MethodCallExpr> segmentHasBlockLambdaArgument,
            Predicate<List<MethodCallExpr>> chainHasTrailingLineComments,
            BiPredicate<Expression, List<MethodCallExpr>> rootHasTrailingLineComment
    ) {
        List<MethodCallExpr> calls = new ArrayList<>();
        Expression root = methodCallChainRoot(expression, calls);
        boolean rootHasComments = sourceShapePolicy.hasContainedComments(root)
            || rootToFirstSelectorGapHasBlockComment(root, calls);
        boolean rootHasBlockLambdaArgument = root instanceof MethodCallExpr methodRoot
            && segmentHasBlockLambdaArgument.test(methodRoot);
        boolean hasTrailingLineComments = chainHasTrailingLineComments.test(calls);
        // A line comment JavaParser attaches as the ROOT's own trailing comment (the root-to-first-selector gap, e.g.
        // {@code new Zone(...) // note}⏎{@code .with(...)}) is invisible to {@code rootHasComments} (the containment scan
        // omits a node's own comment) and to the between/after-selector {@code hasTrailingLineComments} scan. Fold it into
        // {@code hasComments} only — NOT into {@code rootHasComments} or {@code hasTrailingLineComments}, whose other
        // consumers (the FieldAccess-root promotion and {@code shouldPromoteFirstCallForTrailingComments}) must not treat a
        // pure root-trailing comment as a promotion trigger — so every source-neutral fan gate withholds and the chain stays
        // on the imperative path, which re-emits the comment via {@code rootTrailingLineCommentBeforeFirstSegment}. Without
        // this the fan re-renders the root comment-free and drops the comment (a correctness data-loss bug).
        boolean rootHasTrailingLineCommentBeforeFirstSelector = rootHasTrailingLineComment.test(root, calls);
        boolean hasComments = rootHasComments
            || calls.stream().anyMatch(segmentHasComment)
            || hasTrailingLineComments
            || rootHasTrailingLineCommentBeforeFirstSelector;
        boolean hasBlockLambdaArgument = rootHasBlockLambdaArgument
            || calls.stream().anyMatch(segmentHasBlockLambdaArgument);
        boolean singleCommentedSegment = calls.size() == 1 && segmentHasNameComment.test(calls.getFirst());
        int firstCommentedSegment = firstCommentedChainSegment(calls, segmentHasComment);
        boolean firstCallHasArgumentGapComment = !calls.isEmpty()
            && segmentHasArgumentGapComment.test(calls.getFirst());
        boolean laterCallsHaveArgumentGapComment = calls.stream().skip(1).anyMatch(segmentHasArgumentGapComment);
        return new MethodCallChainAnalysis(
            root,
            calls,
            hasComments,
            hasBlockLambdaArgument,
            rootHasBlockLambdaArgument,
            rootHasComments,
            sourceMultilineChain(root, calls),
            singleCommentedSegment,
            firstCommentedSegment,
            firstCallHasArgumentGapComment,
            laterCallsHaveArgumentGapComment,
            hasTrailingLineComments,
            sourceShapePolicy.wasMultiline(expression)
        );
    }

    /**
     * Selects the rendered chain root while preserving source-shaped builder and constructor-root forms.
     */
    MethodCallChainPlan plan(MethodCallChainAnalysis analysis, boolean forceBreak) {
        Expression root = analysis.root();
        List<MethodCallExpr> calls = analysis.calls();
        ChainRootRendering rootRendering = ChainRootRendering.EXPRESSION_RENDERER;
        List<MethodCallExpr> remainingCalls = calls;
        if (analysis.hasComments()) {
            if (shouldPromoteFirstCallWithOwnArgumentComments(root, calls, analysis)) {
                root = calls.getFirst();
                remainingCalls = new ArrayList<>(calls.subList(1, calls.size()));
                rootRendering = promotedStaticFirstCallRendering(calls);
            } else if (shouldPromoteFirstCallForArgumentComments(root, calls, analysis)) {
                root = calls.getFirst();
                remainingCalls = new ArrayList<>(calls.subList(1, calls.size()));
                rootRendering = promotedStaticFirstCallRendering(calls);
            } else if (shouldPromoteFirstCallForTrailingComments(root, calls, analysis)) {
                root = calls.getFirst();
                remainingCalls = new ArrayList<>(calls.subList(1, calls.size()));
                rootRendering = promotedStaticFirstCallRendering(calls);
            } else if (analysis.firstCommentedSegment() > 0 && promotesFirstCall(root)) {
                if (analysis.sourceMultilineChain()) {
                    root = calls.getFirst();
                    remainingCalls = new ArrayList<>(calls.subList(1, calls.size()));
                    rootRendering = promotedStaticFirstCallRendering(calls);
                } else {
                    root = calls.get(analysis.firstCommentedSegment() - 1);
                    remainingCalls = new ArrayList<>(calls.subList(analysis.firstCommentedSegment(), calls.size()));
                }
            } else if (
                analysis.firstCommentedSegment() == 0
                && root instanceof FieldAccessExpr
                && sourceShapePolicy.hasContainedComments(root)
                && calls.size() > 1
            ) {
                root = calls.getFirst();
                remainingCalls = new ArrayList<>(calls.subList(1, calls.size()));
                rootRendering = ChainRootRendering.INLINE_PROMOTED_METHOD_CALL;
            }
        } else if (shouldPromoteFirstCallForArgumentComments(root, calls, analysis)) {
            root = calls.getFirst();
            remainingCalls = new ArrayList<>(calls.subList(1, calls.size()));
            rootRendering = promotedStaticFirstCallRendering(calls);
        } else if (shouldPromoteBuilderRoot(root, calls)) {
            root = calls.getFirst();
            remainingCalls = new ArrayList<>(calls.subList(1, calls.size()));
            rootRendering = ChainRootRendering.INLINE_PROMOTED_METHOD_CALL;
        } else if (shouldPromoteFirstCall(root, calls)) {
            root = calls.getFirst();
            remainingCalls = new ArrayList<>(calls.subList(1, calls.size()));
            rootRendering = promotedStaticFirstCallRendering(calls);
        }
        if (
            rootRendering == ChainRootRendering.EXPRESSION_RENDERER
            && root instanceof ObjectCreationExpr objectCreation
            && !sourceCompactConstructorRoot(objectCreation)
        ) {
            rootRendering = ChainRootRendering.BROKEN_OBJECT_CREATION;
        }
        if (
            rootRendering == ChainRootRendering.EXPRESSION_RENDERER
            && root instanceof MethodCallExpr methodRoot
            && sourceMultilinePromotedMethodRoot(methodRoot)
            && !analysis.rootHasBlockLambdaArgument()
        ) {
            rootRendering = ChainRootRendering.INLINE_PROMOTED_METHOD_CALL;
        }
        return new MethodCallChainPlan(root, remainingCalls, rootRendering);
    }

    boolean rootIsObjectCreation(MethodCallExpr expression) {
        return methodCallChainRoot(expression, new ArrayList<>()) instanceof ObjectCreationExpr;
    }

    boolean rootObjectCreationNeedsBreak(MethodCallChainAnalysis analysis) {
        return analysis.root() instanceof ObjectCreationExpr objectCreation
            && !sourceCompactConstructorRoot(objectCreation);
    }

    boolean rootIsFieldAccess(MethodCallExpr expression) {
        return methodCallChainRoot(expression, new ArrayList<>()) instanceof FieldAccessExpr;
    }

    InitializerChainShape initializerShape(MethodCallChainAnalysis analysis) {
        boolean rootObjectCreationArgumentsSpanMultipleLines =
            analysis.root() instanceof ObjectCreationExpr objectCreation
            && sourceShapePolicy.objectCreationArgumentsSpanMultipleLines(objectCreation);
        boolean rootObjectCreationArgumentsAreWidthDriven =
            analysis.root() instanceof ObjectCreationExpr widthDrivenRoot
            && objectCreationLayoutPolicy.constructorArgumentsAreWidthDriven(widthDrivenRoot);
        MethodCallExpr tail = analysis.calls().isEmpty() && analysis.root() instanceof MethodCallExpr methodRoot
            ? methodRoot
            : analysis.calls().getLast();
        return new InitializerChainShape(
            hasTypeLikeChainRoot(analysis),
            analysis.root() instanceof ObjectCreationExpr,
            analysis.sourceMultilineChain(),
            analysis.calls().size() == 1,
            !tail.getArguments().isEmpty(),
            rootObjectCreationArgumentsSpanMultipleLines,
            rootObjectCreationArgumentsAreWidthDriven,
            analysis.expressionSpansMultipleSourceLines(),
            chainBreaksByRule(analysis)
        );
    }

    private boolean hasTypeLikeChainRoot(MethodCallChainAnalysis analysis) {
        return promotesFirstCall(analysis.root())
            || analysis.calls()
                    .stream()
                    .map(MethodCallExpr::getScope)
                    .flatMap(Optional::stream)
                    .anyMatch(this::promotesFirstCall);
    }

    /**
     * The canonical-fan structural rule (End-state A): decides whether a fluent chain fans one selector per line purely
     * by its structural shape, independent of the author's source layout and independent of width. This is the single
     * source of truth for the rule; {@code MethodCallChainPrinter.chainBreaksByRule} delegates here, and
     * {@link #initializerShape(MethodCallChainAnalysis)} folds the same verdict into
     * {@link InitializerChainShape#chainBreaksByRule()} so the initializer layout routes a fan-threshold chain onto the
     * same source-neutral fan without re-deriving the rule (and without threading a new callback through the declaration
     * printer graph). Lifted verbatim from PR #163 ({@code fix/method-chain-source-shape-independent}).
     *
     * <ul>
     *   <li><b>Call / constructor root → threshold 2.</b> An {@link ObjectCreationExpr} or {@link MethodCallExpr} root
     *   folds the leading invocation into {@code analysis.root()}, so every entry in {@code analysis.calls()} is a
     *   selector after that invocation; two or more selectors fan the chain.</li>
     *   <li><b>Static / factory root → threshold 2 selectors after the factory call.</b> A type-like qualifier surfaces
     *   as {@code analysis.root()} with the factory call as the first entry of {@code calls()}; count selectors after
     *   it.</li>
     *   <li><b>Plain receiver root → threshold 3.</b> A variable / field access / {@code this} / {@code super}; three or
     *   more selectors hang off it before the chain fans.</li>
     * </ul>
     */
    boolean chainBreaksByRule(MethodCallChainAnalysis analysis) {
        Expression root = analysis.root();
        int callRootedLinks = analysis.calls().size();
        if (root instanceof ObjectCreationExpr || root instanceof MethodCallExpr) {
            // Constructor or no-scope-call root: every selector in calls() is a link applied after the invocation root.
            return callRootedLinks >= 2;
        }
        if (promotesFirstCall(root) && !analysis.calls().isEmpty()) {
            // Static/factory root: the type-like qualifier is the root and its first call is the factory invocation, so
            // links are the selectors after that factory call.
            return callRootedLinks - 1 >= 2;
        }
        // Plain receiver root (variable / field / this / super / lowercase name): selectors hang directly off it.
        return callRootedLinks >= 3;
    }

    /**
     * Detects a block comment parked in the source gap between the chain root and its first selector, for example
     * {@code root.create() /* doc *}{@code / .seal()}.
     *
     * <p>The chain stay-flat gate guards on {@link MethodCallChainAnalysis#hasComments()}, which is built from
     * {@link SourceShapePolicy#hasContainedComments(Node)} on each parsed node. JavaParser does not attach a comment in
     * the root-to-first-selector gap to either the root or the first selector reliably (it can be parked as an orphan of
     * the enclosing statement), so {@code hasContainedComments} misses it. Left undetected, the gate keeps the chain flat
     * and the comment is dropped or its leading space is mangled. We therefore slice the source between the root's end and
     * the first selector's name and look for {@code /*}; a hit folds into {@code rootHasComments} so the gate forces the
     * chain off the stay-flat path, where the comment-aware chain renderer preserves it. Keying on the raw source slice
     * (the same technique as {@code AssignmentExpressionPrinter.gapBlockComment} and
     * {@code ControlConditionPrinter.rawTrailingLineCommentText}) keeps the comment owned regardless of which AST node
     * JavaParser happened to bucket it under.
     */
    private boolean rootToFirstSelectorGapHasBlockComment(Expression root, List<MethodCallExpr> calls) {
        if (calls.isEmpty()) {
            return false;
        }
        return root.getRange()
                .flatMap(rootRange -> calls.getFirst()
                            .getName()
                            .getRange()
                            .map(selectorRange -> sourceText.sliceBetween(rootRange, selectorRange))
                )
                .filter(gap -> gap.contains("/*"))
                .isPresent();
    }

    Expression methodCallChainRoot(MethodCallExpr expression, List<MethodCallExpr> calls) {
        if (expression.getScope().orElse(null) instanceof MethodCallExpr methodCallExpr) {
            Expression root = methodCallChainRoot(methodCallExpr, calls);
            calls.add(expression);
            return root;
        }
        if (expression.getScope().isEmpty()) {
            return expression;
        }
        calls.add(expression);
        return expression.getScope().orElseThrow();
    }

    boolean promotesFirstCall(Expression root) {
        if (root.isNameExpr()) {
            return startsWithUppercase(root.asNameExpr().getNameAsString());
        }
        if (root instanceof FieldAccessExpr fieldAccess) {
            return startsWithUppercase(
                fieldAccess.getNameAsString()
            ) || fieldAccessRootName(fieldAccess).map(this::startsWithUppercase).orElse(false);
        }
        return false;
    }

    private boolean sourceMultilineChain(Expression root, List<MethodCallExpr> calls) {
        if (calls.isEmpty()) {
            return false;
        }
        Node previous = root;
        for (MethodCallExpr call : calls) {
            if (sourceShapePolicy.selectorBrokeAfter(previous, call)) {
                return true;
            }
            previous = call;
        }
        return false;
    }

    boolean methodCallStartsAfterScopeLine(MethodCallExpr call) {
        return call.getScope()
                .map(scope -> sourceShapePolicy.selectorBrokeAfter(scope, call))
                .orElse(false);
    }

    boolean methodCallHasTypeLikeScope(MethodCallExpr call) {
        return call.getScope().filter(this::promotesFirstCall).isPresent();
    }

    private boolean sourceMultilinePromotedMethodRoot(MethodCallExpr methodRoot) {
        return methodRoot.getScope()
                .map(scope -> sourceShapePolicy.selectorBrokeAfter(scope, methodRoot))
                .orElse(false);
    }

    private int firstCommentedChainSegment(
            List<MethodCallExpr> calls,
            Predicate<MethodCallExpr> segmentHasComment
    ) {
        for (int i = 0; i < calls.size(); i++) {
            if (segmentHasComment.test(calls.get(i))) {
                return i;
            }
        }
        return 0;
    }

    private boolean shouldPromoteFirstCall(Expression root, List<MethodCallExpr> calls) {
        return promotesFirstCall(root) && !calls.isEmpty();
    }

    private boolean shouldPromoteFirstCallWithOwnArgumentComments(
            Expression root,
            List<MethodCallExpr> calls,
            MethodCallChainAnalysis analysis
    ) {
        return promotesFirstCall(root) && calls.size() > 1 && analysis.firstCallHasArgumentGapComment();
    }

    private boolean shouldPromoteBuilderRoot(Expression root, List<MethodCallExpr> calls) {
        return (
            !calls.isEmpty()
            && typeLikeChainRoot(root)
            && calls.getFirst().getArguments().isEmpty()
            && (calls.getFirst().getNameAsString().equals("builder")
                || calls.getFirst().getNameAsString().equals("newBuilder"))
        );
    }

    private boolean typeLikeChainRoot(Expression root) {
        if (root.isNameExpr()) {
            return startsWithUppercase(root.asNameExpr().getNameAsString());
        }
        if (root instanceof FieldAccessExpr fieldAccess) {
            return startsWithUppercase(fieldAccess.getNameAsString());
        }
        return false;
    }

    private boolean startsWithUppercase(String name) {
        return !name.isEmpty() && Character.isUpperCase(name.charAt(0));
    }

    private boolean shouldPromoteFirstCallForArgumentComments(
            Expression root,
            List<MethodCallExpr> calls,
            MethodCallChainAnalysis analysis
    ) {
        return promotesFirstCall(root)
            && calls.size() > 1
            && !analysis.firstCallHasArgumentGapComment()
            && analysis.laterCallsHaveArgumentGapComment();
    }

    private boolean shouldPromoteFirstCallForTrailingComments(
            Expression root,
            List<MethodCallExpr> calls,
            MethodCallChainAnalysis analysis
    ) {
        return promotesFirstCall(root) && calls.size() > 1 && analysis.hasTrailingLineComments();
    }

    private ChainRootRendering promotedStaticFirstCallRendering(List<MethodCallExpr> calls) {
        return groupedPromotedFirstCallCanKeepArgumentsFlat(calls.getFirst())
            ? ChainRootRendering.GROUPED_PROMOTED_METHOD_CALL
            : ChainRootRendering.EXPRESSION_RENDERER;
    }

    private boolean groupedPromotedFirstCallCanKeepArgumentsFlat(MethodCallExpr expression) {
        return expression.getArguments().size() <= 1;
    }

    private boolean sourceCompactConstructorRoot(ObjectCreationExpr expression) {
        return objectCreationLayoutPolicy.canKeepCompactChainRoot(
            expression,
            currentIndentedWidth.applyAsInt(compactSource.compact(expression)),
            options.lineWidth()
        );
    }

    private Optional<String> fieldAccessRootName(FieldAccessExpr fieldAccess) {
        Expression scope = fieldAccess.getScope();
        if (scope.isNameExpr()) {
            return Optional.of(scope.asNameExpr().getNameAsString());
        }
        if (scope instanceof FieldAccessExpr innerFieldAccess) {
            return fieldAccessRootName(innerFieldAccess);
        }
        return Optional.empty();
    }
}
