package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import dev.lanwen.frmtr.doc.Doc;
import java.util.Optional;

/**
 * Routes broken enclosed expressions to the suffix-aware printer that can keep a call or reference suffix attached.
 *
 * <p>This helper owns the shared method-call and method-reference suffix dispatch used after assignment and field
 * initializer layout has already decided that an enclosed expression needs the suffix-preserving bridge. The boundary
 * exists so those callers do not each need to know the same AST fork, while {@link MethodCallPrinter} and
 * {@link MethodReferencePrinter} remain responsible for the actual {@code .method(...)} and {@code ::member} layout.
 * Expressions without one of those suffix shapes stay with the caller's ordinary fallback path.
 */
final class EnclosedSuffixDispatcher {

    private final MethodCallPrinter methodCalls;

    private final MethodReferencePrinter methodReferences;

    EnclosedSuffixDispatcher(MethodCallPrinter methodCalls, MethodReferencePrinter methodReferences) {
        this.methodCalls = methodCalls;
        this.methodReferences = methodReferences;
    }

    /**
     * Chooses between the two suffix forms that can follow a broken parenthesized scope.
     *
     * <p>Method calls preserve a dotted call suffix, method references preserve a {@code ::} suffix, and every other
     * expression reports no bridge so the caller can keep its existing expression handling.
     *
     * <p>Whether the receiver is already committed to a leading break is a positional fact, so it is read from the
     * {@link LayoutContext} the caller threads in ({@link LayoutContext#leadingBreak()}, #189) rather than carried as a
     * separate dispatch argument. The concrete suffix printers still take the resolved boolean because they have
     * non-positional callers (an ordinary {@code methodCall}/{@code methodReference} that has no context to break)
     * that pass it directly.
     */
    Optional<Doc> suffixedEnclosedExpression(Expression expression, LayoutContext layout) {
        boolean leadingBreak = layout.leadingBreak();
        return switch (expression) {
            case MethodCallExpr methodCallExpr -> methodCalls.suffixedEnclosedMethodCall(methodCallExpr, leadingBreak);
            case MethodReferenceExpr methodReferenceExpr ->
                methodReferences.suffixedEnclosedMethodReference(methodReferenceExpr, leadingBreak);
            default -> Optional.empty();
        };
    }
}
