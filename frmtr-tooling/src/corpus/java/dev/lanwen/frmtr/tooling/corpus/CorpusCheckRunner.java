package dev.lanwen.frmtr.tooling.corpus;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseStart;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.Providers;
import dev.lanwen.frmtr.FormatterException;
import dev.lanwen.frmtr.FormatterOptions;
import dev.lanwen.frmtr.Frmtr;
import dev.lanwen.frmtr.FrmtrSession;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Runs the Layer-3 per-file invariant pipeline over a discovered corpus, in parallel.
 *
 * <p>This type owns the correctness logic of the harness: file discovery (scope filtering), a parse-first gate, and the
 * three asserted invariants — parse-stability and AST-equivalence (both covered by the formatter's own
 * {@code formatVerified}) plus one-pass idempotence. It deliberately reuses only the public {@code Frmtr} API and does
 * not touch formatter internals; if an invariant is violated it records the outcome and reports it — it never repairs
 * the formatter.
 *
 * <p>JavaParser is not thread-safe, so each worker thread gets its own {@link FrmtrSession} and its own parse-first
 * {@link JavaParser} via {@link ThreadLocal}; sessions are never shared across threads.
 */
public final class CorpusCheckRunner {

    private static final FormatterOptions OPTIONS = FormatterOptions.defaults();

    // One reusable formatter session per worker thread (JavaParser is stateful and not thread-safe).
    private static final ThreadLocal<FrmtrSession> SESSION = ThreadLocal.withInitial(() -> Frmtr.session(OPTIONS));

    // One parse-first parser per worker thread, configured IDENTICALLY to the default-options formatter so the
    // parse-stability/AST-equivalence comparison is on the same footing (LATEST_AVAILABLE -> BLEEDING_EDGE).
    private static final ThreadLocal<JavaParser> PARSER = ThreadLocal.withInitial(() -> new JavaParser(
            new ParserConfiguration()
                    .setLanguageLevel(ParserConfiguration.LanguageLevel.BLEEDING_EDGE)
                    .setStoreTokens(true)
                    .setAttributeComments(true)));

    private final Path corpusRoot;

    public CorpusCheckRunner(Path corpusRoot) {
        this.corpusRoot = corpusRoot;
    }

    /**
     * Discovers in-scope {@code .java} files under the corpus root: {@code **}/src/main/java/{@code **}, excluding any
     * path containing {@code build}, {@code generated}, {@code target}, or {@code src/test}.
     *
     * <p>Scope predicates are evaluated against the path <em>relative to the corpus root</em>, never the absolute path.
     * The corpus is extracted under the harness's own {@code build/corpus} work directory, so matching {@code /build/}
     * against the absolute path would wrongly exclude every file.
     */
    public List<Path> discover() throws IOException {
        try (Stream<Path> walk = Files.walk(corpusRoot)) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".java"))
                    .filter(p -> inScope(corpusRoot.relativize(p)))
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    private static boolean inScope(Path relativePath) {
        String rel = "/" + relativePath.toString().replace('\\', '/') + "/";
        if (!rel.contains("/src/main/java/")) {
            return false;
        }
        return !rel.contains("/build/")
                && !rel.contains("/generated/")
                && !rel.contains("/target/")
                && !rel.contains("/src/test/");
    }

    /** Runs the pipeline over all discovered files using a fixed pool sized to the available processors. */
    public List<CorpusOutcome> runAll(List<Path> files) throws InterruptedException {
        int workers = Math.max(1, Runtime.getRuntime().availableProcessors());
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            List<Callable<CorpusOutcome>> tasks = new ArrayList<>(files.size());
            for (Path file : files) {
                tasks.add(() -> check(file));
            }
            List<Future<CorpusOutcome>> futures = pool.invokeAll(tasks);
            List<CorpusOutcome> outcomes = new ArrayList<>(futures.size());
            for (Future<CorpusOutcome> future : futures) {
                try {
                    outcomes.add(future.get());
                } catch (ExecutionException e) {
                    // A bug in the harness itself (not the formatter contract) surfaces here; rethrow to fail loudly.
                    throw new IllegalStateException("Corpus worker threw an unexpected exception", e.getCause());
                }
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Runs the four-step pipeline on one file and classifies the outcome. */
    CorpusOutcome check(Path file) {
        String relativePath = corpusRoot.relativize(file).toString().replace('\\', '/');

        // Step 1: read UTF-8.
        String source;
        try {
            source = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | UncheckedIOException e) {
            return CorpusOutcome.of(relativePath, CorpusOutcome.Invariant.READ_ERROR, oneLine(e.getMessage()));
        }

        // Step 2: parse-first-or-skip with the formatter-matching parser. RECOVER-only inputs are out of scope.
        boolean parsesClean = PARSER.get()
                .parse(ParseStart.COMPILATION_UNIT, Providers.provider(source))
                .isSuccessful();
        if (!parsesClean) {
            return CorpusOutcome.of(relativePath, CorpusOutcome.Invariant.SKIPPED_NON_PARSING);
        }

        FrmtrSession session = SESSION.get();

        // Step 3: formatVerified -> covers invariant (1) parse-stability and (3) AST-equivalence.
        String formatted;
        try {
            formatted = session.formatVerified(source);
        } catch (FormatterException e) {
            if (e.internal()) {
                return CorpusOutcome.of(
                        relativePath, CorpusOutcome.Invariant.FORMAT_INTERNAL_ERROR, oneLine(e.getMessage()));
            }
            // formatVerified throws non-internal FormatterExceptions with these stable prefixes (JavaFormatter
            // #assertOutputEquivalentOrThrow): "frmtr verify: formatted output did not parse ..." for parse-stability
            // and "frmtr verify: formatted output is not AST-equivalent to the input — ..." for AST divergence.
            String message = e.getMessage() == null ? "" : e.getMessage();
            String lower = message.toLowerCase(java.util.Locale.ROOT);
            CorpusOutcome.Invariant invariant;
            if (lower.contains("did not parse") || lower.contains("reparse") || lower.contains("re-parse")) {
                invariant = CorpusOutcome.Invariant.OUTPUT_DID_NOT_REPARSE;
            } else if (lower.contains("not ast-equivalent") || lower.contains("ast-equivalent")
                    || lower.contains("equivalent")) {
                invariant = CorpusOutcome.Invariant.NOT_AST_EQUIVALENT;
            } else {
                // Step 2 said this input parses cleanly, yet the formatter refused it non-internally for some other
                // reason: a surprising contract gap worth surfacing distinctly.
                invariant = CorpusOutcome.Invariant.FORMAT_REJECTED_PARSEABLE_INPUT;
            }
            return CorpusOutcome.of(relativePath, invariant, oneLine(message));
        }

        // Step 4: one-pass idempotence.
        String reformatted;
        try {
            reformatted = session.format(formatted);
        } catch (FormatterException e) {
            // The formatter accepted+verified the first pass but rejected its own output on the second pass: an
            // idempotence/stability defect. Classify by internal vs not.
            CorpusOutcome.Invariant invariant = e.internal()
                    ? CorpusOutcome.Invariant.FORMAT_INTERNAL_ERROR
                    : CorpusOutcome.Invariant.NOT_IDEMPOTENT;
            return CorpusOutcome.of(relativePath, invariant, "second pass threw: " + oneLine(e.getMessage()));
        }
        if (!reformatted.equals(formatted)) {
            return CorpusOutcome.of(
                    relativePath, CorpusOutcome.Invariant.NOT_IDEMPOTENT, firstDivergence(formatted, reformatted));
        }

        return CorpusOutcome.pass(relativePath);
    }

    /** Builds a compact, single-line description of the first line where the two renderings diverge. */
    private static String firstDivergence(String first, String second) {
        String[] a = first.split("\n", -1);
        String[] b = second.split("\n", -1);
        int max = Math.min(a.length, b.length);
        for (int i = 0; i < max; i++) {
            if (!a[i].equals(b[i])) {
                return "line " + (i + 1) + ": pass1=" + truncate(a[i]) + " | pass2=" + truncate(b[i]);
            }
        }
        return "line count " + a.length + " -> " + b.length;
    }

    private static String truncate(String s) {
        String trimmed = s.strip();
        return trimmed.length() <= 60 ? trimmed : trimmed.substring(0, 57) + "...";
    }

    private static String oneLine(String message) {
        if (message == null) {
            return "";
        }
        String collapsed = message.replace('\n', ' ').replace('\r', ' ').strip();
        // Keep the detail long enough to preserve the formatter's "First structural divergence ..." trailer, which is
        // the actionable signal for whoever investigates a surfaced bug.
        return collapsed.length() <= 500 ? collapsed : collapsed.substring(0, 497) + "...";
    }
}
