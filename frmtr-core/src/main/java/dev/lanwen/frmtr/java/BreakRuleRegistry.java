package dev.lanwen.frmtr.java;

import java.util.List;
import java.util.Optional;

/**
 * An ordered, first-match-wins set of {@link BreakRule}s for one construct.
 *
 * <p>Rules are tried in declaration order; the first whose {@link BreakRule#matches} accepts the candidate owns the
 * layout. The order therefore encodes precedence exactly as the imperative {@code if}/{@code else} cascade a registry
 * replaces did, and a terminal always-matching rule stands in for the cascade's {@code else}. When a construct's fan
 * position may legitimately decline to produce a shape (there is no terminal rule), {@link #select} returns empty and
 * the caller falls back — mirroring an {@code Optional}-returning decision that returned empty before.
 *
 * <p>Because every rule is a pure function of the candidate and only the matching rule's {@link BreakRule#layout} runs,
 * resolving the registry is side-effect-free up to the one shape that wins. An extraction from an existing cascade is
 * therefore byte-identical when the rules preserve the branch conditions, their order, and the emitted
 * {@link dev.lanwen.frmtr.doc.Doc}.
 *
 * <p>Immutable and native-image safe (a plain list, no reflection). It is built once and holds no per-run state; a rule
 * that needs run-scoped collaborators closes over them where it is constructed.
 */
final class BreakRuleRegistry<C> {

    private final List<BreakRule<C>> rules;

    private BreakRuleRegistry(List<BreakRule<C>> rules) {
        this.rules = List.copyOf(rules);
    }

    static <C> BreakRuleRegistry<C> of(List<BreakRule<C>> rules) {
        return new BreakRuleRegistry<>(rules);
    }

    /**
     * Returns the first rule whose predicate accepts the candidate, or empty when none match. A registry whose last
     * rule always matches can treat the result as always present; one modelling an optional decision uses the empty
     * result as the "no shape here" signal.
     */
    Optional<BreakRule<C>> select(C candidate) {
        for (BreakRule<C> rule : rules) {
            if (rule.matches(candidate)) {
                return Optional.of(rule);
            }
        }
        return Optional.empty();
    }

    /** The rules in precedence order, for enumeration (for example provenance or diagnostics). */
    List<BreakRule<C>> rules() {
        return rules;
    }
}
