class RetryPolicy {
    boolean shouldRetry(int attempts, int maxAttempts, boolean circuitOpen, boolean fatalError) {
        if (attempts < maxAttempts
                // only retry while the breaker is closed
                && !circuitOpen
                // but give up immediately on a fatal error
                || fatalError) {
            return recordDecision();
        }
        return false;
    }
}
