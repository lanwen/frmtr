class SharePartitionInitializationTest {

    void initializesGapBatchesFromPersistedState() {
        Mockito.when(readShareGroupStateResult.topicsData()).thenReturn(List.of(new TopicData<>(
            TOPIC_ID_PARTITION.topicId(),
            List.of(PartitionFactory.newPartitionAllData(
                0,
                3,
                15L,
                Errors.NONE.code(),
                Errors.NONE.message(),
                List.of(
                    new PersisterStateBatch(15L, 20L, RecordState.ACKNOWLEDGED.id, (short) 2),
                    new PersisterStateBatch(30L, 40L, RecordState.ARCHIVED.id, (short) 3)
                )
            ))
        ))); // There is a gap from 21 to 29
    }
}
