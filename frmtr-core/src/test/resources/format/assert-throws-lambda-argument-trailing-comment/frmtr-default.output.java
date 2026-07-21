class RecordCollectorTest {

    public void shouldThrowInformativeStreamsExceptionOnValueClassCastException() {
        final StreamsException expected = assertThrows(
            StreamsException.class,
            () -> this.collector.send(
                "topic",
                "key",
                "value",
                new RecordHeaders(),
                0,
                0L,
                new StringSerializer(),
                (Serializer) new LongSerializer(),
                null,
                null
            ) // need to add cast to trigger `ClassCastException`
        );
        assertThat(expected.getCause(), instanceOf(ClassCastException.class));
    }
}
