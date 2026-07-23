package dev.example.scheduling;

import java.util.List;
import java.util.Map;

class ShardPlacementView {

    /**
     * Extract the ready replicas from the current placement.
     */
    private static List<ShardPlacementReplica> readyReplicas(Map<ShardPartition, List<WorkerNode>> placement) {
        return placement.entrySet()
                .stream()
                .flatMap(entry -> entry.getValue()
                    .stream()
                    .filter(node -> !node.isDraining())
                    .map(
                        node -> new ShardPlacementReplica(entry.getKey().topic(), entry.getKey().partition(), node.id())
                    )
                )
                .toList();
    }
}
