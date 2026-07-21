class ReadShareGroupStateRequestBuilderTest {

    void buildsRequestWithNestedPartitionData() {
        ReadShareGroupStateRequestData request = new ReadShareGroupStateRequestData()
            .setGroupId(GROUP_ID)
            .setTopics(List.of(new ReadShareGroupStateRequestData.ReadStateData()
                .setTopicId(TOPIC_ID)
                .setPartitions(List.of(new ReadShareGroupStateRequestData.PartitionData()
                    .setPartition(PARTITION)
                    .setLeaderEpoch(3))))); // lower leaderEpoch than the one stored in leaderMap
    }
}
