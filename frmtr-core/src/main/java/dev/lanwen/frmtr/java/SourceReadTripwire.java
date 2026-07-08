package dev.lanwen.frmtr.java;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Diagnostic call-site counter for the {@code RETIREMENT_TARGET} source-shape reads, gated off by default.
 *
 * <p>The hub-canonicalization plan ({@code docs/proposals/hub-canonicalization-atomic-rewrite.md}) retires the
 * remaining "preserve the author's line breaks" reads on {@link SourceShapePolicy} by removing their gates and driving
 * layout from the AST alone. While that atomic flip is in progress the north-star signal is idempotence: a non-zero
 * idempotence delta means some source-shape read is still firing and flipping a decision between passes. This tripwire
 * turns that global signal into a local one — it counts, per retirement-target method, how many hub call-sites hit the
 * read during a format run, so "idempotence delta is non-zero" becomes "here is the exact read still firing, convert
 * it." It owns only the counting-and-dump concern; the reads themselves stay in {@link SourceShapePolicy}, which calls
 * {@link #record(Read)} at the top of each retirement-target method.
 *
 * <p>This is a diagnostic, not formatter behavior. It is <em>disabled by default</em> and must produce
 * <strong>byte-identical output and zero measurable overhead</strong> when off: {@link #record(Read)} is a single
 * volatile-boolean check that returns immediately unless the tripwire was explicitly enabled at class-load time via the
 * {@code FRMTR_SOURCE_READ_TRIPWIRE} environment variable or the {@code frmtr.sourceReadTripwire} system property (any
 * value other than {@code 0}/{@code false}/empty). When enabled, per-{@link Read} totals accumulate across the whole
 * JVM (so a single CLI invocation over a corpus aggregates every file) and are dumped once at JVM shutdown to stderr, or
 * to the file named by {@code FRMTR_SOURCE_READ_TRIPWIRE_FILE} when that is set. Counting never influences which layout
 * a printer chooses, so enabling the tripwire cannot change formatting — it only observes it.
 */
final class SourceReadTripwire {

    /**
     * The retirement-target source-shape reads the tripwire tracks — the six {@link SourceShapePolicy} methods carrying
     * a {@code RETIREMENT_TARGET} {@link SourceShapeException.Stability} that the hub flip must drive to zero live hits.
     */
    enum Read {
        WAS_MULTILINE,
        METHOD_CALL_ARGUMENTS_SPAN_MULTIPLE_LINES,
        EXPRESSION_LAMBDA_STARTS_ON_SELECTOR_LINE,
        OBJECT_CREATION_ARGUMENTS_SPAN_MULTIPLE_LINES,
        STARTS_ON_SAME_LINE,
        SELECTOR_BROKE_AFTER
    }

    private static final boolean ENABLED = resolveEnabled();

    private static final AtomicLong[] COUNTS = newCounts();

    private SourceReadTripwire() {}

    /**
     * Records one hit of the given retirement-target read; a no-op when the tripwire is disabled.
     *
     * <p>When disabled this is a single boolean test and immediate return, so it adds no measurable cost and cannot
     * change behavior. When enabled it increments a per-{@link Read} counter; the increment is a thread-safe atomic add
     * and never feeds back into any layout decision.
     */
    static void record(Read read) {
        if (!ENABLED) {
            return;
        }
        COUNTS[read.ordinal()].incrementAndGet();
    }

    /** Whether the tripwire is active for this JVM (resolved once at class load). */
    static boolean enabled() {
        return ENABLED;
    }

    private static boolean resolveEnabled() {
        boolean on = isTruthy(System.getProperty("frmtr.sourceReadTripwire"))
                || isTruthy(System.getenv("FRMTR_SOURCE_READ_TRIPWIRE"));
        if (on) {
            Runtime.getRuntime().addShutdownHook(new Thread(SourceReadTripwire::dump, "frmtr-source-read-tripwire"));
        }
        return on;
    }

    private static boolean isTruthy(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        return !trimmed.isEmpty()
                && !trimmed.equals("0")
                && !trimmed.equalsIgnoreCase("false");
    }

    private static AtomicLong[] newCounts() {
        Read[] reads = Read.values();
        AtomicLong[] counts = new AtomicLong[reads.length];
        for (int index = 0; index < counts.length; index++) {
            counts[index] = new AtomicLong();
        }
        return counts;
    }

    private static void dump() {
        StringBuilder report = new StringBuilder();
        report.append("== frmtr source-read tripwire (RETIREMENT_TARGET call-site hits) ==\n");
        long total = 0;
        for (Read read : Read.values()) {
            long count = COUNTS[read.ordinal()].get();
            total += count;
            report.append(String.format("%-46s %d%n", read.name(), count));
        }
        report.append(String.format("%-46s %d%n", "TOTAL", total));

        String target = System.getenv("FRMTR_SOURCE_READ_TRIPWIRE_FILE");
        if (target == null || target.trim().isEmpty()) {
            System.err.print(report);
            return;
        }
        try {
            Files.writeString(
                    Path.of(target.trim()),
                    report.toString(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (IOException e) {
            // Diagnostic dump must never break a format run; fall back to stderr on any write failure.
            System.err.println("frmtr source-read tripwire could not write " + target + ": " + e.getMessage());
            System.err.print(report);
        }
    }
}
