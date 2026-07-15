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

    private RawSource rawSource;

    /** A typical builder chain with trailing whitespace on each broken line. */
    private String multilineChain;

    /** Annotation arguments mixing string literals, {@code =} spacing, and line/block comments. */
    private String commentedArguments;

    /** A long method body, sized to expose how trailing-whitespace stripping scales with line count. */
    private String largeMethodBody;

    @Setup
    public void setUp() {
        rawSource = new RawSource(FormatterOptions.defaults());
        multilineChain =
            "HttpClient.newBuilder()   \n"
            + "        .version(HttpClient.Version.HTTP_2)   \n"
            + "        .connectTimeout(Duration.ofSeconds(30))   \n"
            + "        .followRedirects(HttpClient.Redirect.NORMAL)   \n"
            + "        .proxy(ProxySelector.getDefault())   \n"
            + "        .build()";
        commentedArguments =
            "@RequestMapping(\n"
            + "        path = \"/orders/{id}\",   // primary lookup route\n"
            + "        method = RequestMethod.GET,\n"
            + "        produces = \"application/json\",\n"
            + "        /* content negotiation */ consumes = \"application/json\")";
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
