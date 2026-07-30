package sample;

final class QueueRegistrations {

    void register(String topicName, int partitionCount, long retryBackoffMillis) {
        for (int attempt = 0; attempt < partitionCount; attempt++) {
            QueueSubscription queueSubscription = new QueueSubscription(topicName, attempt, partitionCount, retryBackoffMillis).activate();
        }
    }
}
