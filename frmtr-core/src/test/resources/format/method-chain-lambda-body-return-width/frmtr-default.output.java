package sample;

import java.util.List;
import java.util.stream.Collectors;

final class MethodChainLambdaBodyReturnWidth {

    List<ReplicaLeadershipTransitionSummary> summarizeTransitions(List<PartitionReplicaState> replicaStates) {
        return replicaStates.stream()
                .filter(replicaState -> replicaState.isReadyForLeadership())
                .map(state -> {
                    validateReplicaState(state);
                    return new ReplicaLeadershipTransitionSummary(
                        state.currentLeaderEpoch(),
                        state.currentPartitionId()
                    );
                })
                .collect(Collectors.toList());
    }

    List<String> describeTransitions(List<PartitionReplicaState> replicaStates) {
        return replicaStates.stream()
                .filter(replicaState -> replicaState.isReadyForLeadership())
                .map(state -> {
                    validateReplicaState(state);
                    return state.leadershipTransitionBuilder()
                            .withCurrentLeaderEpoch(state.epoch())
                            .withPartitionId(state.id())
                            .build();
                })
                .collect(Collectors.toList());
    }

    List<ReplicaLeadershipTransitionSummary> summarizeShortTransitions(List<PartitionReplicaState> replicaStates) {
        return replicaStates.stream()
                .filter(replicaState -> replicaState.isReadyForLeadership())
                .map(state -> {
                    validateReplicaState(state);
                    return new ReplicaLeadershipTransitionSummary(state.epoch(), state.id());
                })
                .collect(Collectors.toList());
    }
}
