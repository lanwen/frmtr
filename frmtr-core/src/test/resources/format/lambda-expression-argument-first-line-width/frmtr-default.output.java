package sample;

final class SubscriptionRegistrar {

    private final SubscriptionCoordinator subscriptionCoordinatorRegistry;

    private final DispatchStrategy primaryDispatchStrategy;

    private final DispatchStrategy fallbackDispatchStrategy;

    void configure() {
        subscriptionCoordinatorRegistry.registerLifecycleSubscriptionHandler(subscriptionRequestContext ->
            subscriptionRequestContext.isPriorityChannelActive() ? primaryDispatchStrategy : fallbackDispatchStrategy
        );
    }
}
