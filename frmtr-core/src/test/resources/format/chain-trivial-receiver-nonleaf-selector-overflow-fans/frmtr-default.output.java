package dev.example.streams;

class ClickstreamTopology {

    void build(StreamsBuilder builder) {
        builder
                .stream(
                    "clickstream-events-raw-input-topic",
                    Consumed.with(Serdes.String(), Serdes.String()).withTimestampExtractor(
                        new EventTimestampExtractor()
                    )
                )
                .groupByKey()
                .count(Named.as("count"))
                .toStream();
    }
}
