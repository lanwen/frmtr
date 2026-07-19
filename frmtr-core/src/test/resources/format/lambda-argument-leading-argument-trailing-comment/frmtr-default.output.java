class RecordCollectorTest {

    void shouldNotCrashOnClassCastException() {
        assertThrows(
            StreamsException.class, // should not crash with NullPointerException
            () -> collector.send(
                topic,
                "key",
                value,
                headers,
                partition,
                timestamp,
                keySerializer,
                valueSerializer
            )
        );
    }
}
