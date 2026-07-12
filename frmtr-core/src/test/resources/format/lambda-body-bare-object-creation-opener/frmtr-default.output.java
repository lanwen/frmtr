package sample;

import java.util.List;
import java.util.stream.Collectors;

final class ListenerPartitionFinder {

    List<TopicPartition> missingListenerPartitions(MetadataResponse metadataResponse) {
        return metadataResponse.topicMetadata()
                .stream()
                .flatMap(topicMetadata -> topicMetadata.partitionMetadata()
                        .stream()
                        .filter(partitionMetadata -> partitionMetadata.error() == Errors.LISTENER_NOT_FOUND)
                        .map(partitionMetadata -> new TopicPartition(
                            topicMetadata.topic(),
                            partitionMetadata.partition()
                        ))
                )
                .collect(Collectors.toList());
    }
}
