class InitializerChainRootPrefixWidthSample {

    void buildLongConstructorStrategy(Duration configuredStartupTimeout) {
WaitStrategy strategy = new AggregateWaitAllStrategy(AggregateWaitOuterConfiguration.WITH_MAXIMUM_TOTAL_LIMIT).withStartupTimeout(configuredStartupTimeout);
    }

    void buildShortConstructorStrategy(Duration configuredConnectionTimeout) {
        RetryingConnectionStrategy strategy = new RetryingConnectionStrategy(baseConfig).withConfiguredConnectionTimeout(configuredConnectionTimeout);
    }

    void buildAttachedOpenerStrategy(Duration configuredTimeout) {
        RetryingConnectionStrategy strategy = new RetryingConnectionStrategy(config).withTimeout(configuredDefaultConnectionTimeoutValue);
    }

    void buildMultiTimeoutStrategy(Duration configuredStartupTimeout, Duration configuredShutdownTimeout) {
        WaitStrategy strategy = new AggregateWaitAllStrategy(AggregateWaitOuterConfiguration.WITH_LIMIT).withTimeouts(configuredStartupTimeout, configuredShutdownTimeout);
    }

    void buildConditionalStrategy() {
        WaitStrategy strategy = new AggregateWaitAllStrategy(AggregateWaitOuterConfiguration.WITH_LIMIT).withStartupCondition(startupContext -> startupContext.isReady());
    }
}
