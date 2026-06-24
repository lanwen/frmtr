class ChainLinkDuplicateLineComments {
    PipelineConfig duplicateTrailingText(PipelineBuilder builder, int batchCount) {
        return builder
            .maxBatches(batchCount) // tuned per partition
            .maxBatches(batchCount) // tuned per partition
            .flushInterval(batchCount)
            .build();
    }

    PipelineConfig emptyContinuationMarkers(PipelineBuilder builder) {
        return builder
            .retries(3) //
            .timeoutSeconds(30) //
            .compression(true) //
            .checksum(false) //
            .build();
    }
}
