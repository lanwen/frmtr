class PromotedRootSegmentContinuationWidth {

    void deserializeRoundTrip(byte[] serialized) {
        final ValueAndTimestamp<String> deserialized = STRING_SERDE.deserializer().deserialize(TOPIC, HEADERS, serialized);
    }
}
