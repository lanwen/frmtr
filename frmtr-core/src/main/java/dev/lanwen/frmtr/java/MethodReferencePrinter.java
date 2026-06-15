package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.MethodReferenceExpr;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Renders method references after broad expression dispatch has selected {@code scope::member} syntax.
 *
 * <p>This helper owns the method-reference-specific fallback path: preserve compact source text for ordinary method
 * references, but break references whose scope is already parenthesized when the suffix needs to remain attached to the
 * broken enclosed scope. The boundary exists because method references share enclosed-scope suffix mechanics with method
 * calls and array accesses, while the actual parenthesized expression breaking and compact source text still belong to
 * broader expression collaborators.
 *
 * <p>{@link JavaPrinter} still owns broad expression dispatch, compact source-derived text, parenthesized expression
 * breaking, and width calculations. {@link TypePrinter} still owns compact type-argument text. This helper receives
 * those decisions as callbacks and only decides how a selected {@link MethodReferenceExpr} is assembled.
 */
final class MethodReferencePrinter {

    private final FormatterOptions options;

    private final Function<Node, String> compact;

    private final Function<List<? extends Node>, String> compactJoinTypeLike;

    private final BiFunction<EnclosedExpr, Boolean, Doc> brokenEnclosedForSuffix;

    private final ToIntFunction<String> blockStatementWidth;

    MethodReferencePrinter(
            FormatterOptions options,
            Function<Node, String> compact,
            Function<List<? extends Node>, String> compactJoinTypeLike,
            BiFunction<EnclosedExpr, Boolean, Doc> brokenEnclosedForSuffix,
            ToIntFunction<String> blockStatementWidth
    ) {
        this.options = options;
        this.compact = compact;
        this.compactJoinTypeLike = compactJoinTypeLike;
        this.brokenEnclosedForSuffix = brokenEnclosedForSuffix;
        this.blockStatementWidth = blockStatementWidth;
    }

    /**
     * Prints a method reference, trying the parenthesized-scope suffix path before falling back to compact source text.
     *
     * <p>The helper only attempts a structured break for references whose scope is an {@link EnclosedExpr}. That keeps
     * ordinary references such as {@code Type::member} source-compact, while preserving the specific case where a broken
     * parenthesized scope must keep {@code ::member} attached after the closing parenthesis.
     */
    Doc methodReference(MethodReferenceExpr expression) {
        return suffixedEnclosedMethodReference(expression, false).orElseGet(
            () -> Doc.text(compact.apply(expression))
        );
    }

    /**
     * Prints only the suffix attached after a method-reference scope.
     */
    private String methodReferenceSuffix(MethodReferenceExpr expression) {
        return (
            "::"
            + expression.getTypeArguments()
                    .map(typeArguments -> "<" + compactJoinTypeLike.apply(typeArguments) + ">")
                    .orElse("")
            + expression.getIdentifier()
        );
    }

    /**
     * Breaks method references only when their scope is already parenthesized and the full statement is too wide.
     *
     * <p>Without a leading break request, a method reference that still fits as a statement is left to compact output.
     * Once the caller already knows the surrounding assignment or declaration must break, or this reference alone would
     * overflow a block statement, the enclosed scope is rendered through the shared suffix breaker and {@code ::...}
     * stays attached after that broken parenthesized scope.
     */
    Optional<Doc> suffixedEnclosedMethodReference(MethodReferenceExpr expression, boolean leadingBreak) {
        if (
            !leadingBreak
            && blockStatementWidth.applyAsInt(compact.apply(expression) + ";") <= options.lineWidth()
        ) {
            return Optional.empty();
        }
        if (!(expression.getScope() instanceof EnclosedExpr enclosed)) {
            return Optional.empty();
        }
        return Optional.of(
            Doc.concat(
                brokenEnclosedForSuffix.apply(enclosed, leadingBreak),
                Doc.text(methodReferenceSuffix(expression))
            )
        );
    }
}
