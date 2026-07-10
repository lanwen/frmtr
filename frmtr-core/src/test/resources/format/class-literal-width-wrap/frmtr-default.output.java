class TelemetryPipelineRegistry {

    void registerCompact(BeanRegistry registry) {
        registry.register(TelemetryPipeline.StageContainer.class);
    }

    void registerQualified(BeanRegistry registry) {
        registry.register(
            TelemetryPipelineConfigurationForDistributedTracing.AggregationStageWithBackpressureControl
                .RecordProcessorsContainer.class
        );
    }
}
