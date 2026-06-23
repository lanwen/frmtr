package dev.lanwen.frmtr.tooling.corpus;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Opt-in Layer-3 corpus correctness harness entry point.
 *
 * <p>This is the JUnit seam for the {@code corpusCheck} Gradle task only. It is NOT run by {@code ./gradlew test} or
 * {@code build}: it lives in the dedicated {@code corpus} source set, and even when invoked directly it short-circuits
 * to SKIPPED unless the {@code frmtr.corpus.enabled} system property is {@code true} (set only by {@code corpusCheck}
 * with {@code -Pcorpus=true}). A fetch failure (e.g. offline) also reports SKIPPED rather than failing.
 *
 * <p>When enabled it fetches the pinned {@link CorpusPin} corpus, runs the per-file pipeline (parse-stability,
 * idempotence, AST-equivalence) in parallel, prints an aggregate report, and fails iff any file violated an invariant.
 * It does not fix or allowlist any failure — surfacing them is the harness doing its job.
 */
class CorpusCheckTest {

    private static final int MAX_FULL_FAILURE_LINES = 50;

    @Test
    void corpusInvariantsHold() throws IOException, InterruptedException {
        Assumptions.assumeTrue(
                Boolean.getBoolean("frmtr.corpus.enabled"),
                "Corpus harness is opt-in; run with -Pcorpus=true to enable (frmtr.corpus.enabled).");

        String workDirProperty = System.getProperty("frmtr.corpus.workDir");
        assertThat(workDirProperty)
                .as("frmtr.corpus.workDir system property must be set by the corpusCheck task")
                .isNotNull();
        Path workDir = Path.of(workDirProperty);

        Path corpusRoot;
        try {
            corpusRoot = new CorpusFetcher(workDir).ensureCorpus();
        } catch (IOException | InterruptedException e) {
            // No network / fetch failure is a legitimate SKIP, never a harness failure.
            Assumptions.abort("Could not fetch pinned corpus " + CorpusPin.REPO + "@" + CorpusPin.SHA + ": "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            throw new AssertionError("unreachable");
        }

        CorpusCheckRunner runner = new CorpusCheckRunner(corpusRoot);
        List<Path> files = runner.discover();
        System.out.println("[corpus] " + CorpusPin.REPO + "@" + CorpusPin.SHA);
        System.out.println("[corpus] discovered " + files.size() + " in-scope .java files under " + corpusRoot);

        List<CorpusOutcome> outcomes = runner.runAll(files);
        String report = buildReport(outcomes);
        System.out.println(report);

        List<CorpusOutcome> failures = outcomes.stream()
                .filter(CorpusOutcome::isFailure)
                .collect(Collectors.toList());

        assertThat(failures).as(report).isEmpty();
    }

    private static String buildReport(List<CorpusOutcome> outcomes) {
        long discovered = outcomes.size();
        long passed = outcomes.stream().filter(CorpusOutcome::isPass).count();
        long skipped = outcomes.stream().filter(CorpusOutcome::isSkip).count();
        long failed = outcomes.stream().filter(CorpusOutcome::isFailure).count();
        long parsed = discovered - skipped;

        StringBuilder sb = new StringBuilder();
        sb.append("\n[corpus] SUMMARY: discovered=").append(discovered)
                .append(" parsed=").append(parsed)
                .append(" skipped(non-parsing)=").append(skipped)
                .append(" passed=").append(passed)
                .append(" failed=").append(failed)
                .append('\n');

        List<CorpusOutcome> failures = outcomes.stream()
                .filter(CorpusOutcome::isFailure)
                .sorted((a, b) -> {
                    int byInvariant = a.invariant().name().compareTo(b.invariant().name());
                    return byInvariant != 0 ? byInvariant : a.relativePath().compareTo(b.relativePath());
                })
                .collect(Collectors.toList());

        if (failures.isEmpty()) {
            sb.append("[corpus] no invariant violations.\n");
            return sb.toString();
        }

        // Per-invariant histogram.
        Map<CorpusOutcome.Invariant, Long> histogram = new TreeMap<>();
        for (CorpusOutcome failure : failures) {
            histogram.merge(failure.invariant(), 1L, Long::sum);
        }
        sb.append("[corpus] FAILURES BY INVARIANT:\n");
        histogram.forEach((invariant, count) -> sb.append("  ").append(invariant).append(" : ").append(count)
                .append('\n'));

        sb.append("[corpus] FAILURE DETAIL (first ").append(MAX_FULL_FAILURE_LINES).append("):\n");
        int shown = Math.min(MAX_FULL_FAILURE_LINES, failures.size());
        for (int i = 0; i < shown; i++) {
            CorpusOutcome failure = failures.get(i);
            sb.append("  ").append(failure.relativePath())
                    .append(" : ").append(failure.invariant())
                    .append(" : ").append(failure.detail().orElse("")).append('\n');
        }
        if (failures.size() > shown) {
            sb.append("[corpus] ... ").append(failures.size() - shown)
                    .append(" more failures (see histogram above); sample paths per invariant:\n");
            Map<CorpusOutcome.Invariant, StringJoiner> samples = new TreeMap<>();
            for (CorpusOutcome failure : failures) {
                samples.computeIfAbsent(failure.invariant(), k -> new StringJoiner(", "));
                StringJoiner joiner = samples.get(failure.invariant());
                if (joiner.length() < 200) {
                    joiner.add(failure.relativePath());
                }
            }
            samples.forEach((invariant, joiner) -> sb.append("  ").append(invariant).append(" -> ")
                    .append(joiner).append('\n'));
        }
        return sb.toString();
    }
}
