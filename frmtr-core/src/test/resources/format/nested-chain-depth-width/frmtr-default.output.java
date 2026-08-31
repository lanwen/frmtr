package dev.example.pipeline;

class NestedChainDepthWidth {

    void retryArgumentOverflowsAtDepth(PipelineRunner runner) {
        PipelineStage.defer(() -> {
            return PipelineStage.just("connected");
        }).retryWhen(
            RetryPolicy.create(
                new BackoffWindow("primary-shard", BackoffWindow.between(15, 90)),
                failoverTarget,
                4
            ).toSchedule()
        );
    }

    void shortRetryArgumentFitsAtDepth(PipelineRunner runner) {
        PipelineStage.defer(() -> {
            return PipelineStage.just("connected");
        }).retryWhen(
            RetryPolicy.create(new BackoffWindow("shard", BackoffWindow.between(5, 9)), localTarget).toSchedule()
        );
    }

    void nestedInitializerOverflowsAtDepth(ServiceContainerProbe container) {
        ManagedTransport transport = TransportFactory.create(
            ChannelSettings.from(
                ChannelBuilder.forControlPlane(
                    container.resolveCanonicalHostName(),
                    container.controlPlanePort(),
                    4
                ).build()
            )
        );
    }

    void shortNestedInitializerFitsAtDepth(ServiceContainerProbe container) {
        ManagedTransport transport = TransportFactory.create(
            ChannelSettings.from(ChannelBuilder.forControlPlane(container.host(), container.port(), 4).build())
        );
    }
}
