package dev.lanwen.frmtr.java;

import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MethodCallExpr;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.doc.Doc;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

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

    private final SourceShapePolicy sourceShapePolicy;

    private final FormatterOptions options;

    private final Function<Expression, Doc> expressionRenderer;

    private final Function<Expression, Optional<Doc>> brokenArgumentRenderer;

    private final Function<Expression, String> compact;

    private final Predicate<Expression> binaryFansChainOperand;

    private final ConditionalExpressionLineProjection conditionalProjection;

    private final LayoutWidth layoutWidth;

    BreakableArgumentExpressionPrinter(
            SourceShapePolicy sourceShapePolicy,
            FormatterOptions options,
            Function<Expression, Doc> expressionRenderer,
            Function<Expression, Optional<Doc>> brokenArgumentRenderer,
            Function<Expression, String> compact,
            Predicate<Expression> binaryFansChainOperand,
            LayoutWidth layoutWidth
    ) {
        this.sourceShapePolicy = sourceShapePolicy;
        this.options = options;
        this.expressionRenderer = expressionRenderer;
        this.brokenArgumentRenderer = brokenArgumentRenderer;
        this.compact = compact;
        this.binaryFansChainOperand = binaryFansChainOperand;
        this.conditionalProjection = new ConditionalExpressionLineProjection(compact);
        this.layoutWidth = layoutWidth;
    }

    Doc argument(Expression argument) {
        return argument(argument, "");
    }

    Doc argument(Expression argument, String suffix) {
        return argument(argument, suffix, argumentLayout(suffix));
    }

    /**
     * Derives the per-argument {@link LayoutContext} the break-gate is threaded (D1a/#190): an {@code ARGUMENT}-position
     * context whose {@link LayoutContext#trailingContent()} carries the caller's {@code suffix} (the trailing comma or
     * call tail) and whose {@link LayoutContext#leftEdgePrefix()} is deliberately empty.
     *
     * <p>The prefix is empty because an argument's extra offset is pure continuation indentation applied by the enclosing
     * list's nested {@code Doc.indent} at render time, not a textual left-edge prefix like an assignment's {@code NAME = }
     * (see the sibling reasoning in {@code MethodCallPrinter}'s argument-chain seam). This reproduces the ad-hoc
     * {@code suffix} the seam already carried, so deriving it changes no decision.
     */
    private LayoutContext argumentLayout(String suffix) {
        return new LayoutContext(
            EnclosingConstruct.ARGUMENT,
            "",
            suffix,
            false
        );
    }

    /**
     * Keeps an expression argument breakable when its source shape or rendered continuation would otherwise overflow.
     *
     * <p>The suffix is only part of the width probe; callers still own rendering commas, semicolons, or call tails.
     *
     * <p>D1a (#190): {@code layout} threads the argument's positional context so the true continuation column
     * ({@link LayoutContext#leftEdgePrefix()}) and the trailing separator ({@link LayoutContext#trailingContent()})
     * become AVAILABLE at this arg break-gate for the eventual reflow-by-width flip. It is NOT yet consulted: the gate
     * decides from its {@link LayoutWidth#nodeLine} + fixed-{@code CONTINUATION}-floor probe over the ad-hoc
     * {@code suffix} string. Sourcing the break decision from a renderer-measured group at the threaded column instead is
     * a Phase-3 decision change (atomic-rewrite plan rows 3/4/5) and stays out of this seam.
     */
    // C10 (#220): the continuation-line width is measured at the argument's real rendered depth, floored by the fixed
    // CONTINUATION budget, rather than at the fixed budget alone (see continuationWidth). The trailing comma/tail arrives
    // both as the ad-hoc `suffix` string (still the value the gate measures) and, since D1a, as layout.trailingContent();
    // sourcing the gate from layout.trailingContent() instead of `suffix` is a decision change (Phase-3) and stays out.
    private Doc argument(Expression argument, String suffix, LayoutContext layout) {
        Doc flat = expressionRenderer.apply(argument);
        // Canonical-fan cutover seam U8: when this argument is a binary/ternary whose dispatched {@code flat} rendering
        // already fans a fluent chain operand by the End-state A structural rule, commit that {@code flat} shape and do
        // not offer the operand-per-line {@code broken} alternative. Below, {@code flat} fans the operand (via the
        // dispatched source-neutral {@code chainFanOut}) while the {@code broken} alternative keeps it flat with the
        // operator on its own line, so the two arms are different byte shapes and offering both through {@code ifBreak}
        // oscillates between them forever (KafkaConsumerTest {@code chain + 1}, SinglePointMetricTest
        // {@code chain || chain}). {@code flat} is itself a pure function of the AST — the chain fans by the width-
        // independent link-count rule on every pass — so returning it unconditionally is the fixpoint. Chains the rule
        // does not fan (a plain-receiver 1–2-link operand, the #119 {@code binary-chain-wrap-converge} guard) and
        // comment / lambda chains are excluded by {@code binaryFansChainOperand}, so those arguments keep the width-driven
        // {@code broken} arm below. The carve-out predicate is the shared
        // {@link MethodCallChainPrinter#binaryFansChainOperand}, so every binary-operand carrier (this argument path, the
        // single-binary-argument path, the broken object-creation binary argument) applies one carve-out definition.
        if (binaryFansChainOperand.test(argument)) {
            return flat;
        }
        Optional<Doc> broken = brokenArgument(argument);
        if (
            broken.isPresent()
            && (conditionalArgumentLineOverflows(argument, suffix)
                || continuationWidth(argument, compact.apply(argument) + suffix) > options.lineWidth())
        ) {
            return Doc.ifBreak(broken.orElseThrow(), flat);
        }
        return flat;
    }

    Doc sourceMultilineArgument(Expression argument) {
        return sourceMultilineArgument(argument, "");
    }

    Doc sourceMultilineArgument(Expression argument, String suffix) {
        return sourceMultilineArgument(argument, suffix, argumentLayout(suffix));
    }

    /**
     * Source-multiline-list sibling of {@link #argument(Expression, String, LayoutContext)}.
     *
     * <p>D1a (#190): {@code layout} threads the same {@code ARGUMENT}-position context so the true continuation column and
     * trailing separator are AVAILABLE at this break-gate. It is not yet consulted: the gate breaks purely from the
     * {@code conditionalArgumentLineOverflows} width test and the {@code nodeLine}+floor continuation-width probe over the
     * {@code suffix} string.
     */
    private Doc sourceMultilineArgument(Expression argument, String suffix, LayoutContext layout) {
        Doc flat = expressionRenderer.apply(argument);
        if (binaryPlusContainsSourceMultilineMethodCallArgument(argument)) {
            return flat;
        }
        // Canonical-fan cutover seam U8: same convergence as {@link #argument(Expression, String)}, on the
        // source-multiline argument-list path (a method-call argument list whose arguments already span source lines, e.g.
        // {@code assertTrue("...", chain.equals(a) || chain.equals(b))} once its second argument wrapped). Here the arm
        // choice returns {@code broken} outright rather than {@code ifBreak}, so a binary/ternary argument whose {@code flat}
        // rendering fans a chain operand by the End-state A rule must commit {@code flat} — the source-neutral fan — or it
        // flips to the operand-per-line {@code broken} shape on the pass that observes the wrapped argument list. Non-fan /
        // comment / lambda chains are excluded by {@code binaryFansChainOperand} and keep the {@code broken} arm.
        if (binaryFansChainOperand.test(argument)) {
            return flat;
        }
        Optional<Doc> broken = brokenArgument(argument);
        if (
            broken.isPresent()
            && (conditionalArgumentLineOverflows(argument, suffix)
                || continuationWidth(argument, compact.apply(argument) + suffix) > options.lineWidth())
        ) {
            return broken.orElseThrow();
        }
        return flat;
    }

    private Optional<Doc> brokenArgument(Expression argument) {
        if (!argument.getAllContainedComments().isEmpty()) {
            return Optional.empty();
        }
        return brokenArgumentRenderer.apply(argument);
    }

    private boolean conditionalArgumentLineOverflows(Expression argument, String suffix) {
        if (!(argument instanceof ConditionalExpr conditionalExpr)) {
            return false;
        }
        String line = conditionalProjection.line(conditionalExpr) + suffix;
        return continuationWidth(argument, line) > options.lineWidth();
    }

    /**
     * Measures an argument's own continuation line at the column where it actually renders, floored by the fixed
     * {@link LayoutWidth.LineBudget#CONTINUATION} budget.
     *
     * <p>C10 (#220): once the enclosing argument list breaks, a breakable argument renders on its own line at the
     * argument's rendered nesting depth. The historical fixed CONTINUATION budget (three units) is the shallow common
     * case but under-counts an argument list that sits inside an inner class or nested type, so an argument that
     * overflowed its true column was frozen flat over width. {@link LayoutWidth#nodeLine} counts every enclosing
     * {@code TypeDeclaration}/{@code BlockStmt} around the argument, and the CONTINUATION term is kept as a floor so a
     * shallow argument (at or below three units, the corpus's common case) is measured exactly as before while a deeply
     * nested one breaks at its real column. This mirrors the throws-clause, parameter-list, and try-with-resources
     * rendered-column gates. The argument-list {@code Doc.indent} itself is not counted here (it is applied by the
     * caller, not visible from the argument node), so this stays a floor on the shallow estimate rather than a full
     * reconstruction of the running column.
     */
    private int continuationWidth(Expression argument, String line) {
        return Math.max(
            layoutWidth.nodeLine(argument, line),
            layoutWidth.line(LayoutWidth.LineBudget.CONTINUATION, line)
        );
    }

    private boolean binaryPlusContainsSourceMultilineMethodCallArgument(Expression argument) {
        if (argument instanceof BinaryExpr binaryExpr) {
            return binaryExpr.getOperator() == BinaryExpr.Operator.PLUS
                && (binaryPlusContainsSourceMultilineMethodCallArgument(binaryExpr.getLeft())
                    || binaryPlusContainsSourceMultilineMethodCallArgument(binaryExpr.getRight()));
        }
        if (argument instanceof EnclosedExpr enclosedExpr) {
            return binaryPlusContainsSourceMultilineMethodCallArgument(enclosedExpr.getInner());
        }
        return false;
    }
}
