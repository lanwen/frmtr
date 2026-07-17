class RecordCollectorTest {

    void sendWithCastTriggersClassCastException() {
        assertThrows(ClassCastException.class, () -> collector.send(topicPartition, serializedKey, (Integer) serializedValue, // need to add cast to trigger ClassCastException
                recordHeaders, sinkNodeName, context));
    }
}
