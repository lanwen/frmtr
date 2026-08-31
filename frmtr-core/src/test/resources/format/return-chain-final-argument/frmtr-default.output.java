class ReturnChainFinalArgumentSample {

    Result<Command> wrap(Result<Command> pipeline) {
        return Pipelines.wrap(pipeline).onFailure(
            RetryPolicy.restart().withLoggingEnabled(true).withResetChildren(true)
        );
    }

    void sourceNestedReturn(
            Map<String, AggregateWaitStrategy> waitStrategies,
            String serviceName,
            Duration startupTimeout
    ) {
        final AggregateWaitStrategy waitStrategy = waitStrategies.computeIfAbsent(serviceName, ignored -> {
            return new AggregateWaitAllStrategy(AggregateWaitAllStrategy.Mode.WITH_MAXIMUM_OUTER_TIMEOUT)
                .withStartupTimeout(startupTimeout);
        });
        sink(waitStrategy);
    }

    void firstPassNestedReturn(
            Map<String, AggregateWaitStrategy> waitStrategies,
            String serviceName,
            Duration startupTimeout
    ) {
        final AggregateWaitStrategy waitStrategy = waitStrategies.computeIfAbsent(serviceName, ignored -> {
            return new AggregateWaitAllStrategy(AggregateWaitAllStrategy.Mode.WITH_MAXIMUM_OUTER_TIMEOUT)
                .withStartupTimeout(startupTimeout);
        });
        sink(waitStrategy);
    }
}
