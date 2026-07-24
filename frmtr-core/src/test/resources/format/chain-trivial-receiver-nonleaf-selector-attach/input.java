package dev.example.streams;

class WordCountTopology {

    void build(StreamsBuilder builder) {
        builder.stream("input", Consumed.as("source"))
                .groupByKey()
                .count(Named.as("count"))
                .toStream()
                .to("output", Produced.as("sink"));
    }
}
