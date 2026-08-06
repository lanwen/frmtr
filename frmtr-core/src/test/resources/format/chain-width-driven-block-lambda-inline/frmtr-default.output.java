class MetadataRecordIndexer {

    void indexFields(MetadataNode node, ActorRefNormalizer normalizer) {
        node.fields().forEachRemaining(entry -> {
            normalizer.set(entry.getKey(), normalizeActorRefs(entry.getValue()));
        });
    }

    void processEntries(RecordBatch batch, ResultSink sink) {
        batch.entries().forEach(entry -> {
            sink.record(entry.key(), transform(entry.value()));
        });
    }

    void replaySnapshots(SnapshotReader reader, ChangeApplier applier) {
        reader.snapshots().forEachRemaining(snapshot -> {
            applier.apply(snapshot.revision(), snapshot.records());
        });
    }

    void drainWithLongSubscriberName(SubscriberWithVerboseTypeName subscriber, Queue queue) {
        subscriber.toAsync().publishes(MessagePublishFilter.ALL, publish -> {
            queue.record(publish.payload());
        });
    }

    void publishesWhenOverWidth(SubscriptionRegistry subscriptionRegistry) {
        subscriptionRegistry.toAsync()
                .publishes(MessagePublishFilter.FORWARD_ONLY_AND_ACKNOWLEDGED_ONLY, publishEvent -> {
                    recordingBuffer.capture(publishEvent.payload(), publishEvent.routingKey());
                });
    }

    void verifiesCommittedBatch(CoordinatorService coordinatorService) {
        assertThat(coordinatorService.processedBatches()).singleElement().satisfies(batch -> {
            assertThat(batch.status()).isEqualTo(BatchStatus.COMMITTED);
            assertThat(batch.offset()).isGreaterThan(0L);
        });
    }
}
