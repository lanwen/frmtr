class StreamsProducerTest {

    private void testThrowOnEosSendOffset(final RuntimeException exception) {
        eosMockProducer.sendOffsetsToTransactionException = exception;

        final TaskMigratedException thrown = assertThrows(
            TaskMigratedException.class,
            // we pass in `null` to verify that `sendOffsetsToTransaction()` fails instead of `commitTransaction()`
            // `sendOffsetsToTransaction()` would throw an NPE on `null` offsets
            () -> eosStreamsProducer.commitTransaction(null, new ConsumerGroupMetadata("appId"))
        );
    }
}
