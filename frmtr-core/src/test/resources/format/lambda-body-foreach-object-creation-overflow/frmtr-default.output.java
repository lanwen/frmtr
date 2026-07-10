package sample;

final class LambdaBodyForEachOverflow {

    void collectPartitionAssignments(List<TopicPartition> assignedPartitions, List<PartitionRecord> collectedRecords) {
        assignedPartitions.forEach(topicPartition -> collectedRecords.add(
                new PartitionRecord()
                        .setTopicName(topicPartition.topic())
                        .setPartitionIndex(topicPartition.partition())
        ));
    }
}
