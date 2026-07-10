package sample;

final class OngoingReassignmentCollector {

    void collectPartitions(List<OngoingTopicReassignment> ongoingTopicReassignments, ListPartitionReassignmentsRequestData data) {
        if (data.topics() != null) {
            for (ListPartitionReassignmentsTopics topic : data.topics()) {
                ongoingTopicReassignments.add(new OngoingTopicReassignment().setName(topic.name()).setPartitions(topic.partitionIndexes().stream().map(partitionIndex -> new OngoingPartitionReassignment().setPartitionIndex(partitionIndex)).collect(Collectors.toList())));
            }
        }
    }
}
