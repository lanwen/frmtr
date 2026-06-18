package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.expr.Expression;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/**
 * Renders expression arguments that may need their own broken form inside a broken argument list.
 *
 * <p>This helper owns the shared decision used by method-call and constructor argument lists: once an enclosing
 * argument list breaks, a source-multiline or over-wide expression argument should be allowed to render through its
 * expression-specific broken form instead of collapsing back to a single argument line. The boundary keeps method-call
 * and object-creation printers focused on their delimiters, suffixes, and comment-specific list handling.
 *
 * <p>Callers still decide list separators, argument suffix ownership, and syntax-specific cases such as method-call
 * tails or object-creation suffixes.
 */
final class BreakableArgumentExpressionPrinter {

    private final SourceShape sourceShape;

    private final FormatterOptions options;

    private final Function<Expression, Doc> expressionRenderer;

    private final Function<Expression, Optional<Doc>> brokenArgumentRenderer;

    private final Function<Expression, String> compact;

    private final ToIntFunction<String> continuationStatementWidth;

    BreakableArgumentExpressionPrinter(
            SourceShape sourceShape,
            FormatterOptions options,
            Function<Expression, Doc> expressionRenderer,
            Function<Expression, Optional<Doc>> brokenArgumentRenderer,
            Function<Expression, String> compact,
            ToIntFunction<String> continuationStatementWidth
    ) {
        this.sourceShape = sourceShape;
        this.options = options;
        this.expressionRenderer = expressionRenderer;
        this.brokenArgumentRenderer = brokenArgumentRenderer;
        this.compact = compact;
        this.continuationStatementWidth = continuationStatementWidth;
    }

    Doc argument(Expression argument) {
        return argument(argument, "");
    }

    /**
     * Keeps an expression argument breakable when its source shape or rendered continuation would otherwise overflow.
     *
     * <p>The suffix is only part of the width probe; callers still own rendering commas, semicolons, or call tails.
     */
    Doc argument(Expression argument, String suffix) {
        Doc flat = expressionRenderer.apply(argument);
        Optional<Doc> broken = brokenArgument(argument);
        if (
            broken.isPresent()
            && (sourceShape.spansMultipleLines(argument)
                || continuationStatementWidth.applyAsInt(compact.apply(argument) + suffix) > options.lineWidth())
        ) {
            return Doc.ifBreak(broken.orElseThrow(), flat);
        }
        return flat;
    }

    Doc sourceMultilineArgument(Expression argument) {
        Optional<Doc> broken = brokenArgument(argument);
        if (broken.isPresent() && sourceShape.spansMultipleLines(argument)) {
            return broken.orElseThrow();
        }
        return expressionRenderer.apply(argument);
    }

    private Optional<Doc> brokenArgument(Expression argument) {
        if (!argument.getAllContainedComments().isEmpty()) {
            return Optional.empty();
        }
        return brokenArgumentRenderer.apply(argument);
    }
}
