package dev.lanwen.frmtr.java;

import dev.lanwen.frmtr.FormatterOptions;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Baseline for the raw-source whitespace helpers JFR flagged as the top formatter-owned CPU/allocation seam. Calls the
 * real {@link RawSource} transforms in isolation (this benchmark shares their package for access), so a single-pass
 * rewrite can be measured against this baseline for throughput and per-op allocation (run with {@code -prof gc}).
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
@State(Scope.Benchmark)
public class RawSourceNormalizationBenchmark {

    /** A typical multi-line builder chain with clean lines: exercises the split-and-join path with nothing to strip. */
    private final String multilineChain = """
            HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_2)
                    .connectTimeout(Duration.ofSeconds(30))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .proxy(ProxySelector.getDefault())
                    .build()""";

    /** Annotation arguments mixing string literals, {@code =} spacing, and line/block comments. */
    private final String commentedArguments = """
            @RequestMapping(
                    path = "/orders/{id}",   // primary lookup route
                    method = RequestMethod.GET,
                    produces = "application/json",
                    /* content negotiation */ consumes = "application/json")""";

    private RawSource rawSource;

    /** A long method body with trailing whitespace, sized to expose how stripping scales with line count. */
    private String largeMethodBody;

    @Setup
    public void setUp() {
        rawSource = new RawSource(FormatterOptions.defaults());
        StringBuilder body = new StringBuilder();
        for (int lineItem = 0; lineItem < 60; lineItem++) {
            body.append("        subtotal = subtotal.add(lineItems.get(")
                    .append(lineItem)
                    .append(").grossAmount().multiply(taxRate));   \n");
        }
        largeMethodBody = body.toString();
    }

    @Benchmark
    public String stripMultilineChain() {
        return rawSource.stripTrailingHorizontalWhitespace(multilineChain);
    }

    @Benchmark
    public String stripLargeMethodBody() {
        return rawSource.stripTrailingHorizontalWhitespace(largeMethodBody);
    }

    @Benchmark
    public String normalizeMultilineChain() {
        return rawSource.normalizeWhitespace(multilineChain);
    }

    @Benchmark
    public String normalizeCommentedArguments() {
        return rawSource.normalizeWhitespace(commentedArguments);
    }
}
