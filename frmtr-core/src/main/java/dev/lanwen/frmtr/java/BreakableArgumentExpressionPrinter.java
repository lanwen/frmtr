package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.expr.Expression;
import dev.lanwen.frmtr.doc.Doc;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Renders expression arguments that may need their own broken form inside a broken argument list.
 *
 * <p>This helper owns the shared decision used by method-call and constructor argument lists: once an enclosing
 * argument list breaks, an over-wide expression argument should be allowed to render through its expression-specific
 * broken form instead of collapsing back to a single argument line. The boundary keeps method-call and object-creation
 * printers focused on their delimiters, suffixes, and comment-specific list handling.
 *
 * <p>Callers still decide list separators, argument suffix ownership, and syntax-specific cases such as method-call
 * tails or object-creation suffixes.
 */
final class BreakableArgumentExpressionPrinter {

    private final SourceShapePolicy sourceShapePolicy;

    private final Function<Expression, Doc> expressionRenderer;

    private final Function<Expression, Optional<Doc>> brokenArgumentRenderer;

    private final Predicate<Expression> binaryFansChainOperand;

    BreakableArgumentExpressionPrinter(
            SourceShapePolicy sourceShapePolicy,
            Function<Expression, Doc> expressionRenderer,
            Function<Expression, Optional<Doc>> brokenArgumentRenderer,
            Predicate<Expression> binaryFansChainOperand
    ) {
        this.sourceShapePolicy = sourceShapePolicy;
        this.expressionRenderer = expressionRenderer;
        this.brokenArgumentRenderer = brokenArgumentRenderer;
        this.binaryFansChainOperand = binaryFansChainOperand;
    }

    /**
     * Offers an expression argument its expression-specific broken form as a renderer-measured alternative to the flat
     * rendering.
     *
     * <p>The flat/broken choice is a {@link Doc#conditionalGroup(List)} ranked by the renderer at
     * the argument's true output column, not a precomputed {@link LayoutWidth} continuation-budget probe. Because both
     * arms are pure functions of the AST, the choice is a fixpoint (the pre-flip {@code CONTINUATION}-probe-to-group
     * conversion oscillated because the surrounding hub still read source shape; post-flip it does not), and the renderer
     * measures the real column so a deeply nested argument breaks instead of freezing flat over width. The trailing
     * comma/tail the caller appends after this Doc is accounted for by the renderer's line-fit lookahead, so no width
     * suffix needs to be threaded here.
     */
    Doc argument(Expression argument) {
        Doc flat = expressionRenderer.apply(argument);
        // When this argument is a binary/ternary whose dispatched {@code flat} rendering
        // already fans a fluent chain operand by the structural fan rule, commit that {@code flat} shape and do
        // not offer the operand-per-line {@code broken} alternative. Below, {@code flat} fans the operand (via the
        // dispatched source-neutral {@code chainFanOut}) while the {@code broken} alternative keeps it flat with the
        // operator on its own line, so the two arms are different byte shapes and offering both would oscillate between
        // them forever (KafkaConsumerTest {@code chain + 1}, SinglePointMetricTest {@code chain || chain}). {@code flat}
        // is itself a pure function of the AST — the chain fans by the width-independent link-count rule on every pass —
        // so returning it unconditionally is the fixpoint. Chains the rule does not fan (a plain-receiver 1–2-link
        // operand, the #119 {@code binary-chain-wrap-converge} guard) and comment / lambda chains are excluded by
        // {@code binaryFansChainOperand}, so those arguments keep the width-driven {@code broken} arm below. The carve-out
        // predicate is the shared {@link MethodCallChainPrinter#binaryFansChainOperand}, so every binary-operand carrier
        // (this argument path, the single-binary-argument path, the broken object-creation binary argument) applies one
        // carve-out definition.
        if (binaryFansChainOperand.test(argument)) {
            return flat;
        }
        Optional<Doc> broken = brokenArgument(argument);
        if (broken.isPresent()) {
            return Doc.conditionalGroup(List.of(flat, broken.orElseThrow()));
        }
        return flat;
    }

    /**
     * Source-multiline-list sibling of {@link #argument(Expression)}.
     *
     * <p>Behaves identically to {@link #argument(Expression)} except for the {@code +}-concat carve-out that keeps a
     * source-multiline concatenation flat, which only the source-multiline argument-list path needs.
     */
    Doc sourceMultilineArgument(Expression argument) {
        Doc flat = expressionRenderer.apply(argument);
        // Same convergence as {@link #argument(Expression)}, on the source-multiline
        // argument-list path (a method-call argument list whose arguments already span source lines, e.g.
        // {@code assertTrue("...", chain.equals(a) || chain.equals(b))} once its second argument wrapped). A binary/ternary
        // argument whose {@code flat} rendering fans a chain operand by the structural fan rule must commit {@code flat} — the
        // source-neutral fan — else it flips to the operand-per-line {@code broken} shape. Non-fan / comment / lambda
        // chains are excluded by {@code binaryFansChainOperand} and keep the {@code broken} arm.
        if (binaryFansChainOperand.test(argument)) {
            return flat;
        }
        Optional<Doc> broken = brokenArgument(argument);
        if (broken.isPresent()) {
            return Doc.conditionalGroup(List.of(flat, broken.orElseThrow()));
        }
        return flat;
    }

    private Optional<Doc> brokenArgument(Expression argument) {
        if (sourceShapePolicy.hasContainedComments(argument)) {
            return Optional.empty();
        }
        return brokenArgumentRenderer.apply(argument);
    }
}
