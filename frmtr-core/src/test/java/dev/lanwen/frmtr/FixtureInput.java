package dev.lanwen.frmtr;

/**
 * A formatter fixture's input source, discovered by {@link ResourceFixtureSource#inputs(String)} without requiring any
 * {@code frmtr-<variant>.output.java} companion.
 *
 * <p>Use this (instead of {@link FormatFixture}) for tests that only consume fixture inputs — e.g. property tests that
 * perturb the source and assert input-derived invariants (idempotence, semantic preservation) rather than comparing
 * against a checked-in golden output. Output discovery and per-variant options stay on the {@link FormatFixture} path.
 */
public record FixtureInput(String name, String source) {
    @Override
    public String toString() {
        return name;
    }
}
