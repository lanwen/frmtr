class ChannelSubscriptionSetup {

    void setup() {
        var subscriptionHandle = centralPartitionAwareChannelRegistryLocatorService.fetchChannel(tenantId).routingTable.subscribe();
    }
}
