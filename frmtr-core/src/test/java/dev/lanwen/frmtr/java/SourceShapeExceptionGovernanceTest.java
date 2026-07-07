package dev.lanwen.frmtr.java;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The ratchet for reprint-by-default Stage 2: pins the source-shape-read surface of {@link SourceShapePolicy} to the
 * closed set enumerated in {@link SourceShapeException}.
 *
 * <p>frmtr reprints from scratch and reads the author's source layout only through the enumerated exceptions. This test
 * is the forcing function: a new package-private method on {@link SourceShapePolicy} fails the suite until it is either
 * categorized in {@link SourceShapeException} (a reviewed decision, with an idempotence rationale) or added to the small
 * {@link #KNOWN_NON_READS} allow-list of structural helpers that read no source shape. It also pins the count of
 * still-fragile reads so the retirement roadmap's progress is visible and each retirement forces a deliberate update.
 */
class SourceShapeExceptionGovernanceTest {

    /**
     * Non-private {@link SourceShapePolicy} methods that are NOT source-shape reads: pure structural/AST predicates that
     * consult no source layout. Kept explicit so adding one is deliberate rather than silently widening the surface.
     */
    private static final Set<String> KNOWN_NON_READS = Set.of("logicalConditionExpression");

    @Test
    void everyEnumeratedReadNamesAnExistingPolicyMethod() {
        Set<String> policyMethods = nonPrivatePolicyMethodNames();
        for (SourceShapeException read : SourceShapeException.values()) {
            assertThat(policyMethods)
                .as("SourceShapeException.%s names methods that must exist on SourceShapePolicy", read)
                .containsAll(read.methods());
        }
    }

    @Test
    void everyNonPrivatePolicyMethodIsCategorizedOrAKnownNonRead() {
        Set<String> enumerated = enumeratedReadNames();
        Set<String> uncategorized = nonPrivatePolicyMethodNames().stream()
            .filter(name -> !enumerated.contains(name))
            .filter(name -> !KNOWN_NON_READS.contains(name))
            .collect(Collectors.toSet());
        assertThat(uncategorized)
            .as(
                "every SourceShapePolicy source-shape read must be categorized in SourceShapeException "
                    + "(or added to KNOWN_NON_READS if it reads no source shape) — reprint-by-default keeps the set closed"
            )
            .isEmpty();
    }

    @Test
    void noReadMethodIsCategorizedUnderTwoExceptions() {
        List<String> all = SourceShapeException.values().length == 0
            ? List.of()
            : Arrays.stream(SourceShapeException.values()).flatMap(read -> read.methods().stream()).toList();
        assertThat(all).doesNotHaveDuplicates();
    }

    @Test
    void everyExceptionCarriesAnIdempotenceRationale() {
        for (SourceShapeException read : SourceShapeException.values()) {
            assertThat(read.rationale()).as("SourceShapeException.%s rationale", read).isNotBlank();
        }
    }

    @Test
    void fragileReadCountIsPinnedForRetirementProgress() {
        long retirementTargets = Arrays.stream(SourceShapeException.values())
            .filter(read -> read.stability() == SourceShapeException.Stability.RETIREMENT_TARGET)
            .mapToLong(read -> read.methods().size())
            .sum();
        // Drop this number as fragile reads are replaced by structural BreakRules and deleted. It is the reprint-by-default
        // retirement metric; a change here must be a deliberate retirement (or a reviewed new fragile read), never silent.
        assertThat(retirementTargets)
            .as("fragile (RETIREMENT_TARGET) source-shape reads still to replace with structural rules")
            .isEqualTo(7L);
    }

    private static Set<String> enumeratedReadNames() {
        return Arrays.stream(SourceShapeException.values())
            .flatMap(read -> read.methods().stream())
            .collect(Collectors.toSet());
    }

    private static Set<String> nonPrivatePolicyMethodNames() {
        return Arrays.stream(SourceShapePolicy.class.getDeclaredMethods())
            .filter(method -> !method.isSynthetic())
            .filter(method -> !Modifier.isPrivate(method.getModifiers()))
            .map(Method::getName)
            .collect(Collectors.toSet());
    }
}
