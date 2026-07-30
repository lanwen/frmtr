package sample;

final class Sample {

    private void addRoutingRule(String tenantId, String channelName, String policyName, String retentionDays) {
        RoutingRuleDefinition routingRuleDefinition = new RoutingRuleDefinition(
            tenantId,
            channelName,
            policyName,
            retentionDays
        ).activate(); // requires downstream ack
    }
}
