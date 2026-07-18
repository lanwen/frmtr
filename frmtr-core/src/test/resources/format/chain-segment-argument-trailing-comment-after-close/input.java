package streams;

class SuppressionTopology {
    void build(StreamsBuilder builder) {
        builder.stream("input", Consumed.as("source")).groupByKey().count(Named.as("count"))
                .suppress(Suppressed.untilTimeLimit(Duration.ofSeconds(10), Suppressed.BufferConfig.unbounded()).withName("suppressed")) // keep only the final count per window
                .toStream(Named.as("toStream")).to("output", Produced.as("sink"));
    }
}
