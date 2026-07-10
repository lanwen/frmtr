package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import dev.lanwen.frmtr.doc.Doc;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Breaks a chain that alternates method calls and field accesses as one structural chain.
 *
 * <p>This helper owns the "mixed" chain walk: a chain such as {@code root.method().field.method()} whose selectors
 * interleave method calls and field accesses, where a field access can hide the earlier method-call root behind one or
 * more field names. It answers the two questions the ordinary method-call chain printer cannot — the structural root of
 * such a chain and its one-segment-per-line layout — by recursing through both method-call and field-access scopes. The
 * boundary exists because the ordinary chain analysis walks method-call scopes only, so folding the field-access
 * traversal back into it would blur that plain-chain state machine.
 *
 * <p>The caller still owns how each segment renders (it supplies the segment and field-access-segment builders and the
 * continuation-indent shape) and what to do with the answers: this helper only assembles the root doc plus continuation
 * or reports the structural root, and leaves declaration/initializer routing to the caller.
 */
final class MixedFieldMethodCallChainLayout {

    private final JavaFormatRule<Expression> expressionRenderer;

    private final Function<Doc, Doc> chainContinuation;

    private final Function<MethodCallExpr, Doc> methodCallChainSegment;

    private final BiFunction<FieldAccessExpr, MethodCallExpr, Doc> fieldAccessMethodCallSegment;

    MixedFieldMethodCallChainLayout(
            JavaFormatRule<Expression> expressionRenderer,
            Function<Doc, Doc> chainContinuation,
            Function<MethodCallExpr, Doc> methodCallChainSegment,
            BiFunction<FieldAccessExpr, MethodCallExpr, Doc> fieldAccessMethodCallSegment
    ) {
        this.expressionRenderer = expressionRenderer;
        this.chainContinuation = chainContinuation;
        this.methodCallChainSegment = methodCallChainSegment;
        this.fieldAccessMethodCallSegment = fieldAccessMethodCallSegment;
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
                expressionRenderer.format(root.orElseThrow(), LayoutContext.root()),
                chainContinuation.apply(Doc.join(Doc.HARD_LINE, segments))
            )
        );
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
            root.ifPresent(ignored -> segments.add(methodCallChainSegment.apply(expression)));
            return root;
        }
        if (scoped instanceof FieldAccessExpr fieldAccess) {
            Optional<MethodCallExpr> methodRoot = fieldAccessMethodRoot(fieldAccess);
            if (methodRoot.isEmpty()) {
                return Optional.empty();
            }
            Optional<Expression> root = collectMixedFieldMethodCallChain(methodRoot.orElseThrow(), segments);
            root.ifPresent(ignored -> segments.add(fieldAccessMethodCallSegment.apply(fieldAccess, expression)));
            return root;
        }
        segments.add(methodCallChainSegment.apply(expression));
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
}
