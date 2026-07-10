package dev.example;

class PartitionRegistry {

    void register(
            String topicPartitionKey,
            PartitionAssignmentState observedState,
            Map<String, PartitionAssignmentState> pendingStateByPartition
    ) {
        pendingStateByPartition
                .computeIfAbsent(topicPartitionKey, key -> new PartitionAssignmentState())
                .recordObservedAssignment(observedState);
    }

    DeliveryPlan plan(OrderEvent orderEvent) {
        return orderEvent.validateOrder().deliveryPlan();
    }
}
