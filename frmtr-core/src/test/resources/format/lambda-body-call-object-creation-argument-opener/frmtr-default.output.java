package sample;

import java.util.LinkedHashMap;
import java.util.Map;

final class PartitionRouteRegistrar {

    LinkedHashMap<TopicPartition, PartitionRoute> indexRoutes(
            RouteResponse routeResponse,
            Map<Uuid, String> topicNames
    ) {
        final LinkedHashMap<TopicPartition, PartitionRoute> routeIndex = new LinkedHashMap<>();
        routeResponse.topics()
                .forEach(topicRoute -> {
                    String name = topicNames.get(topicRoute.topicId());
                    if (name != null) {
                        topicRoute.partitions()
                                .forEach(partition -> routeIndex.put(
                                    new TopicPartition(name, partition.partitionIndex()),
                                    partition
                                ));
                    }
                });
        return routeIndex;
    }
}
