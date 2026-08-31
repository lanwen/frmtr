class RoutingGuard {

    void routesToFallbackZone(RequestContext ctx) {
        if (deploymentPlan.currentRolloutStage().targetRegion().identifier().equals(FALLBACK_ROUTING_ZONE_IDENTIFIER)) {
            useFallback();
        }
    }

    void awaitsFallbackRollout(RequestContext ctx) {
        while (deploymentPlan.currentRolloutStage().targetRegion().identifier().equals(FALLBACK_ROUTING_ZONE_IDENTIFIER)) {
            useFallback();
        }
    }

    void staysFlatForShortChain(RequestContext ctx) {
        if (deploymentPlan.currentRolloutStage().equals(FALLBACK_STAGE)) {
            useFallback();
        }
    }

    void skipsFallbackWhenZoneUnset(RequestContext ctx) {
        if (!deploymentPlan.currentRolloutStage().targetRegion().identifier().equals(FALLBACK_ROUTING_ZONE_IDENTIFIER)) {
            useFallback();
        }
    }

    void rejectsMismatchedRolloutRequest(RequestContext ctx) {
        if (routingPolicy.matchesFallbackZoneAcrossDeploymentRegions(currentRegionIdentifier, requestedZoneIdentifier, overrideFlag)) {
            useFallback();
        }
    }
}
