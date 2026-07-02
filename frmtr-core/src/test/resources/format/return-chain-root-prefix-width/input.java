class ReturnChainRootPrefixWidthSample {

    WaitStrategy buildAggregateWaitStrategy(Duration configuredStartupTimeout) {
return new AggregateWaitAllStrategy(AggregateWaitOuterConfiguration.WITH_MAXIMUM_TOTAL_LIMIT).withStartupTimeout(configuredStartupTimeout);
    }

    WaitStrategy buildBoundedWaitStrategy(Duration startupTimeout) {
        return new AggregateWaitAllStrategy(AggregateWaitOuterConfiguration.WITH_LIMIT).withStartupTimeout(startupTimeout);
    }

    WaitStrategy buildMultiTimeoutWaitStrategy(Duration configuredStartupTimeout, Duration configuredShutdownTimeout) {
        return new AggregateWaitAllStrategy(AggregateWaitOuterConfiguration.WITH_LIMIT).withTimeouts(configuredStartupTimeout, configuredShutdownTimeout);
    }

    WaitStrategy buildConditionalWaitStrategy() {
        return new AggregateWaitAllStrategy(AggregateWaitOuterConfiguration.WITH_LIMIT).withStartupCondition(startupContext -> startupContext.isReady());
    }
}
