class CastRootFanningChain {

    void castRootChainFirstSelectorFansSourceNeutrally(boolean shouldUseTopicIds) {
        ((OffsetFetchRequestData) response.unsentRequests.get(0)
                .requestBuilder()
                .build()
                .data())
                .groups()
                .forEach(group -> group.topics().forEach(
                        topic -> assertEquals(shouldUseTopicIds, !topic.topicId().equals(Uuid.ZERO_UUID))
                ));
    }
}
