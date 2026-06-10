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
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Plans source-sensitive method-call chain roots before {@link MethodCallPrinter} assembles docs.
 *
 * <p>This helper owns the chain decisions that depend on source shape rather than rendered document structure: finding
 * the structural root, detecting selectors that were already split onto later source lines, promoting type-like roots
 * and static builder roots, and keeping simple single-argument constructor roots compact when the original source did.
 * The boundary exists so the method-call printer can stay focused on argument docs, comments, and final chain assembly.
 *
 * <p>Callers still provide comment and block-lambda predicates because those are rendering concerns owned by
 * {@link MethodCallPrinter}. This helper combines those facts with AST ranges and compact width checks, but it does not
 * render comments, build method-call segment docs, or decide ordinary argument-list layout.
 */
final class MethodCallChainSourcePlanner {
    private final SourceShape sourceShape;
    private final CompactSourceText compactSource;
    private final FormatterOptions options;
    private final ToIntFunction<String> currentIndentedWidth;

    MethodCallChainSourcePlanner(
            JavaFormatContext context,
            ToIntFunction<String> currentIndentedWidth) {
        this.sourceShape = context.sourceShape;
        this.compactSource = context.compactSource;
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
        BROKEN_OBJECT_CREATION
    }

    /**
     * Carries the selected method-chain root, remaining call segments, and root rendering policy together.
     *
     * <p>This keeps root promotion from leaking boolean flags into the final chain assembly. The model does not own
     * segment rendering or decide whether a chain should be printed at all.
     */
    record MethodCallChainPlan(
            Expression root,
            List<MethodCallExpr> calls,
            ChainRootRendering rootRendering) {
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
            boolean hasTrailingLineComments) {
        MethodCallChainAnalysis {
            calls = List.copyOf(calls);
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
            Predicate<List<MethodCallExpr>> chainHasTrailingLineComments) {
        List<MethodCallExpr> calls = new ArrayList<>();
        Expression root = methodCallChainRoot(expression, calls);
        boolean rootHasComments = !root.getAllContainedComments().isEmpty();
        boolean rootHasBlockLambdaArgument = root instanceof MethodCallExpr methodRoot
                && segmentHasBlockLambdaArgument.test(methodRoot);
        boolean hasTrailingLineComments = chainHasTrailingLineComments.test(calls);
        boolean hasComments = rootHasComments
                || calls.stream().anyMatch(segmentHasComment)
                || hasTrailingLineComments;
        boolean hasBlockLambdaArgument = rootHasBlockLambdaArgument
                || calls.stream().anyMatch(segmentHasBlockLambdaArgument);
        boolean singleCommentedSegment = calls.size() == 1 && segmentHasNameComment.test(calls.getFirst());
        int firstCommentedSegment = firstCommentedChainSegment(calls, segmentHasComment);
        boolean firstCallHasArgumentGapComment = !calls.isEmpty() && segmentHasArgumentGapComment.test(calls.getFirst());
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
                hasTrailingLineComments);
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
            if (shouldPromoteFirstCallForArgumentComments(root, calls, analysis)) {
                root = calls.getFirst();
                remainingCalls = new ArrayList<>(calls.subList(1, calls.size()));
                rootRendering = promotedStaticFirstCallRendering(calls);
            } else if (shouldPromoteFirstCallForTrailingComments(root, calls, analysis)) {
                root = calls.getFirst();
                remainingCalls = new ArrayList<>(calls.subList(1, calls.size()));
                rootRendering = promotedStaticFirstCallRendering(calls);
            } else if (analysis.firstCommentedSegment() > 0 && promotesFirstCall(root)) {
                root = calls.get(analysis.firstCommentedSegment() - 1);
                remainingCalls = new ArrayList<>(calls.subList(analysis.firstCommentedSegment(), calls.size()));
            } else if (analysis.firstCommentedSegment() == 0
                    && root instanceof FieldAccessExpr
                    && !root.getAllContainedComments().isEmpty()
                    && calls.size() > 1) {
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
        if (rootRendering == ChainRootRendering.EXPRESSION_RENDERER
                && forceBreak
                && root instanceof ObjectCreationExpr objectCreation
                && !sourceCompactSingleArgumentConstructorRoot(objectCreation)) {
            rootRendering = ChainRootRendering.BROKEN_OBJECT_CREATION;
        }
        if (rootRendering == ChainRootRendering.EXPRESSION_RENDERER
                && root instanceof MethodCallExpr methodRoot
                && sourceMultilinePromotedMethodRoot(methodRoot)
                && !analysis.rootHasBlockLambdaArgument()) {
            rootRendering = ChainRootRendering.INLINE_PROMOTED_METHOD_CALL;
        }
        return new MethodCallChainPlan(root, remainingCalls, rootRendering);
    }

    boolean rootIsObjectCreation(MethodCallExpr expression) {
        return methodCallChainRoot(expression, new ArrayList<>()) instanceof ObjectCreationExpr;
    }

    boolean rootIsFieldAccess(MethodCallExpr expression) {
        return methodCallChainRoot(expression, new ArrayList<>()) instanceof FieldAccessExpr;
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
            String name = root.asNameExpr().getNameAsString();
            return !name.isEmpty() && Character.isUpperCase(name.charAt(0));
        }
        if (root instanceof FieldAccessExpr fieldAccess) {
            return fieldAccessRootName(fieldAccess)
                    .filter(name -> !name.isEmpty())
                    .map(name -> Character.isUpperCase(name.charAt(0)))
                    .orElse(false);
        }
        return false;
    }

    private boolean sourceMultilineChain(Expression root, List<MethodCallExpr> calls) {
        if (calls.isEmpty()) {
            return false;
        }
        Node previous = root;
        for (MethodCallExpr call : calls) {
            if (selectorStartsAfterPreviousSegmentLine(previous, call)) {
                return true;
            }
            previous = call;
        }
        return false;
    }

    private boolean selectorStartsAfterPreviousSegmentLine(Node previous, MethodCallExpr call) {
        return previous.getRange()
                .flatMap(previousRange -> call.getName().getRange()
                        .map(nameRange -> nameRange.begin.line > previousRange.end.line))
                .orElse(false);
    }

    private boolean sourceMultilinePromotedMethodRoot(MethodCallExpr methodRoot) {
        return methodRoot.getScope()
                .map(scope -> selectorStartsAfterPreviousSegmentLine(scope, methodRoot))
                .orElse(false);
    }

    private int firstCommentedChainSegment(
            List<MethodCallExpr> calls,
            Predicate<MethodCallExpr> segmentHasComment) {
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

    private boolean shouldPromoteBuilderRoot(Expression root, List<MethodCallExpr> calls) {
        return !calls.isEmpty()
                && typeLikeChainRoot(root)
                && calls.getFirst().getArguments().isEmpty()
                && (calls.getFirst().getNameAsString().equals("builder")
                        || calls.getFirst().getNameAsString().equals("newBuilder"));
    }

    private boolean typeLikeChainRoot(Expression root) {
        if (root.isNameExpr()) {
            String name = root.asNameExpr().getNameAsString();
            return !name.isEmpty() && Character.isUpperCase(name.charAt(0));
        }
        if (root instanceof FieldAccessExpr fieldAccess) {
            String name = fieldAccess.getNameAsString();
            return !name.isEmpty() && Character.isUpperCase(name.charAt(0));
        }
        return false;
    }

    private boolean shouldPromoteFirstCallForArgumentComments(
            Expression root,
            List<MethodCallExpr> calls,
            MethodCallChainAnalysis analysis) {
        return promotesFirstCall(root)
                && calls.size() > 1
                && !analysis.firstCallHasArgumentGapComment()
                && analysis.laterCallsHaveArgumentGapComment();
    }

    private boolean shouldPromoteFirstCallForTrailingComments(
            Expression root,
            List<MethodCallExpr> calls,
            MethodCallChainAnalysis analysis) {
        return promotesFirstCall(root)
                && calls.size() > 1
                && analysis.hasTrailingLineComments();
    }

    private ChainRootRendering promotedStaticFirstCallRendering(List<MethodCallExpr> calls) {
        return groupedPromotedFirstCallCanKeepArgumentsFlat(calls.getFirst())
                ? ChainRootRendering.GROUPED_PROMOTED_METHOD_CALL
                : ChainRootRendering.EXPRESSION_RENDERER;
    }

    private boolean groupedPromotedFirstCallCanKeepArgumentsFlat(MethodCallExpr expression) {
        return expression.getArguments().size() <= 1;
    }

    private boolean sourceCompactSingleArgumentConstructorRoot(ObjectCreationExpr expression) {
        return expression.getArguments().size() == 1
                && expression.getAnonymousClassBody().isEmpty()
                && expression.getAllContainedComments().isEmpty()
                && !sourceShape.objectCreationArgumentsSpanMultipleLines(expression)
                && currentIndentedWidth.applyAsInt(compactSource.compact(expression)) <= options.lineWidth();
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
