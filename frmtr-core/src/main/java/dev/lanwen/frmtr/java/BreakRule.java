package dev.lanwen.frmtr.java;

import dev.lanwen.frmtr.doc.Doc;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A named, source-neutral rule for how one construct breaks: a pure predicate over a construct-specific layout
 * candidate paired with the {@link Doc} shape to emit when it fires.
 *
 * <p>This is the authoring unit of the reprint-by-default break-rule model
 * ({@code docs/proposals/reprint-by-default-break-rules.md}). It is deliberately distinct from {@link JavaFormatRule}:
 * a {@code JavaFormatRule} is the type-dispatched node&rarr;{@link Doc} handoff a dispatcher selects by node kind,
 * whereas a {@code BreakRule} is consulted by a printer to choose <em>which break shape</em> a construct takes when an
 * AST predicate holds. Rules are gathered in a {@link BreakRuleRegistry} and resolved first-match-wins, so a set of
 * rules expresses a construct's break choices as named, enumerable units.
 *
 * <p>The candidate type {@code C} is whatever facts a construct hands its rules — for the first consumer, a method-call
 * chain in a fan position ({@code MethodCallChainPrinter}'s {@code ChainFanRequest}: the chain expression plus the
 * caller-appended final-segment suffix and positional context). Carrying construct-specific inputs on the candidate is
 * what lets this one abstraction host constructs whose layout needs more than a bare node without a leaky signature.
 *
 * <p>Keeping the rule a pure function of the candidate is what makes the chosen shape a fixpoint: the same candidate
 * yields the same verdict on every pass, so the model cannot reintroduce the source-shape oscillations the End-state A
 * cutover removed. {@link #matches} should therefore read only the candidate (AST-derived facts and positional context)
 * and not re-measure width or consult the author's source layout; {@link #layout} emits one source-neutral shape whose
 * flat-vs-broken fit is ranked by the renderer, not decided here.
 *
 * <p>The {@link #name()} identifies the rule for provenance — a later {@code --explain} slice attributes each break to
 * the rule that produced it — and for the rule's golden fixtures.
 */
interface BreakRule<C> {

    /** A stable identifier for this rule, used for provenance and fixtures. */
    String name();

    /** Whether this rule owns the layout of the given candidate. Must be a pure function of the candidate. */
    boolean matches(C candidate);

    /** The single source-neutral {@link Doc} shape this rule emits for a candidate it {@link #matches}. */
    Doc layout(C candidate);

    /**
     * Builds a rule from a name, a match predicate, and a layout function — the common case where the predicate and the
     * shape are each an expression lifted from an imperative decision branch.
     */
    static <C> BreakRule<C> of(String name, Predicate<C> matches, Function<C, Doc> layout) {
        return new BreakRule<>() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public boolean matches(C candidate) {
                return matches.test(candidate);
            }

            @Override
            public Doc layout(C candidate) {
                return layout.apply(candidate);
            }
        };
    }
}
