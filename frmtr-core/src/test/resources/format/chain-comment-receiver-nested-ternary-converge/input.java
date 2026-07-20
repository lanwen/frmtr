class OptimisticLockRetryPolicy {
    long delayFromAuthorHuggedSource(int retryCounter) {
        long sleepFor = 0;
        if (retryDelay > 0 || randomBackOff) {
            sleepFor = exponentialBackOff
                    ? (retryDelay << retryCounter)
                    : (randomBackOff
                            ? ThreadLocalRandom.current() // NOSONAR
                                    .nextInt((int) (maximumRetryDelay > 0 ? maximumRetryDelay : DEFAULT_MAXIMUM_RETRY_DELAY))
                            : retryDelay);
        }
        return sleepFor;
    }

    long delayFromPreExplodedSource(int retryCounter) {
        long sleepFor = 0;
        if (retryDelay > 0 || randomBackOff) {
            sleepFor = exponentialBackOff
                    ? (retryDelay << retryCounter)
                    : (randomBackOff
                            ? ThreadLocalRandom.current() // NOSONAR
                                    .nextInt(
                                        (int) (maximumRetryDelay > 0 ? maximumRetryDelay : DEFAULT_MAXIMUM_RETRY_DELAY)
                                    )
                            : retryDelay);
        }
        return sleepFor;
    }
}
