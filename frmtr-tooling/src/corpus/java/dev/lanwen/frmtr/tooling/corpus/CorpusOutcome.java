package dev.lanwen.frmtr.tooling.corpus;

import java.util.Objects;
import java.util.Optional;

/**
 * Result of running the per-file invariant pipeline against one corpus source file.
 *
 * <p>This is a pure data record: it classifies one file's outcome via {@link Invariant} and, for failures, carries a
 * one-line {@code detail} (e.g. the AST-divergence minimized diff or a first-divergence snippet). It deliberately holds
 * no formatting or assertion logic; aggregation and reporting live in {@link CorpusCheckRunner} and the JUnit entry.
 *
 * <p>{@code relativePath} is relative to the corpus source root so reports are stable across machines and work
 * directories.
 */
public record CorpusOutcome(String relativePath, Invariant invariant, Optional<String> detail) {

    public CorpusOutcome {
        Objects.requireNonNull(relativePath, "relativePath");
        Objects.requireNonNull(invariant, "invariant");
        Objects.requireNonNull(detail, "detail");
    }

    public static CorpusOutcome pass(String relativePath) {
        return new CorpusOutcome(relativePath, Invariant.PASSED, Optional.empty());
    }

    public static CorpusOutcome of(String relativePath, Invariant invariant) {
        return new CorpusOutcome(relativePath, invariant, Optional.empty());
    }

    public static CorpusOutcome of(String relativePath, Invariant invariant, String detail) {
        return new CorpusOutcome(relativePath, invariant, Optional.ofNullable(detail));
    }

    public boolean isFailure() {
        return invariant.failure;
    }

    public boolean isSkip() {
        return invariant == Invariant.SKIPPED_NON_PARSING;
    }

    public boolean isPass() {
        return invariant == Invariant.PASSED;
    }

    /**
     * Classification of one file's pipeline outcome.
     *
     * <p>Non-failure terminals are {@link #PASSED} (all invariants held) and {@link #SKIPPED_NON_PARSING} (input did not
     * parse cleanly as a compilation unit, so AST-equivalence is ill-defined — a legitimate skip, not a defect). Every
     * other value is a failure that the harness surfaces; none is allowlisted or quarantined.
     */
    public enum Invariant {
        /** All asserted invariants held for this file. */
        PASSED(false),
        /** Input did not parse cleanly as a compilation unit with the formatter-matching parser; legitimate skip. */
        SKIPPED_NON_PARSING(false),
        /** The file could not be read as UTF-8 text. */
        READ_ERROR(true),
        /** {@code formatVerified} threw an internal formatter error — a genuine frmtr/parser crash. */
        FORMAT_INTERNAL_ERROR(true),
        /** Formatter output did not re-parse cleanly (parse-stability violated). */
        OUTPUT_DID_NOT_REPARSE(true),
        /** Formatter output parsed but was not AST-equivalent to the input (semantics changed). */
        NOT_AST_EQUIVALENT(true),
        /** The formatter refused input that parsed cleanly in step 2 — an unexpected non-internal refusal. */
        FORMAT_REJECTED_PARSEABLE_INPUT(true),
        /** Formatting the already-formatted output changed it (one-pass idempotence violated). */
        NOT_IDEMPOTENT(true);

        final boolean failure;

        Invariant(boolean failure) {
            this.failure = failure;
        }
    }
}
