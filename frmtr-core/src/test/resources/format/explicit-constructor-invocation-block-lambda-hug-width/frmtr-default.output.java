class RetryingDispatchScheduler extends BaseDispatchScheduler {

    RetryingDispatchScheduler(
            DistributedRetryPolicyConfig distributedRetryPolicyConfig,
            ExponentialBackoffCalculator exponentialBackoffCalculator,
            RetryNotificationDispatcher retryNotificationDispatcher
    ) {
        super(distributedRetryPolicyConfig, exponentialBackoffCalculator, retryNotificationDispatcher, attempt -> {
            retryNotificationDispatcher.notifyRetry(attempt);
            return exponentialBackoffCalculator.nextDelay(attempt);
        });
    }

    RetryingDispatchScheduler(
            DistributedRetryPolicyConfig distributedRetryPolicyConfig,
            ExponentialBackoffCalculator exponentialBackoffCalculator,
            RetryFailureNotificationDispatcher retryFailureNotificationDispatcher
    ) {
        super(
            distributedRetryPolicyConfig,
            exponentialBackoffCalculator,
            retryFailureNotificationDispatcher,
            attempt -> {
                retryFailureNotificationDispatcher.notifyRetry(attempt);
                return exponentialBackoffCalculator.nextDelay(attempt);
            }
        );
    }
}
