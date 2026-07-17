package dev.example.admin;

class StreamsGroupOffsetsResetTask {

    void resetOffsets() {
        final AlterStreamsGroupOffsetsResult firstResult = clusterEnvironment.adminClient()
                .alterStreamsGroupOffsets(streamsGroupId, requestedOffsets);
        final AlterStreamsGroupOffsetsResult secondResult = clusterEnvironment.adminClient().alterStreamsGroupOffsets(streamsGroupId, requestedOffsets);
    }
}
