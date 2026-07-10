package sample;

final class LambdaBodyChainHugIdempotent {

    void waitForReplicaPresent(Map<Integer, StorageNode> brokersById, TopicPartition topicPartition) {
        ReplicationTestUtils.waitForCondition(
            () -> brokersById.values().stream().allMatch(
                broker -> broker.logManager().getLog(topicPartition, false).isPresent()
            ),
            replicaCreationFailureMessage
        );
    }

    void waitForReplicaAbsent(Map<Integer, StorageNode> brokersById, TopicPartition removedPartition) {
        ReplicationTestUtils.waitForCondition(
            () -> brokersById.values().stream().allMatch(
                broker -> broker.logManager().getLog(removedPartition, false).isEmpty()
            ),
            replicaDeletionFailureMessage
        );
    }
}
