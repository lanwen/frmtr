@Bundle(routeProperties = {
    @RouteConfigProperty(name = "replication.factor", value = "3"), // keep replicas intact
    @RouteConfigProperty(name = "partition.count", value = "1"),
    @RouteConfigProperty(name = "session.timeout.ms", value = "10"), // small enough to expire fast
    @RouteConfigProperty(name = "rebalance.delay.ms", value = "0"),
})
class DispatchPolicySample {
    int[] retryBudgets = {
        4, // first attempt window
        9, // widened retry window
    };
}
