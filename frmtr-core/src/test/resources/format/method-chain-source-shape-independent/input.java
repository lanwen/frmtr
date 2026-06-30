class RouteChainShapes {

    void callRootedFlatSource() {
        from("inbound.queue").routeId("primaryRoute").setBody(payload);
    }

    void callRootedBrokenSource() {
        from("inbound.queue")
            .routeId("primaryRoute")
            .setBody(payload);
    }

    void plainRootedTwoLinks(SessionRegistry registry) {
        var lookup = registry.activeSessions().firstEntry();
    }

    void plainRootedThreeLinks(LabelSettings settings, RoutingMode mode) {
        var configured = settings.withMinimumWeight(12).withMaximumWeight(40).withMode(mode);
    }

    void factoryRootedOneLink() {
        var batch = CompletableFuture.allOf(firstStage, secondStage).get(5, TimeUnit.SECONDS);
    }

    void plainRootedTwoLinksOverflowsOnWidth() {
        var endpoint = primaryRoutingDomainConnectionCoordinator.resolveActivePrimaryEndpoint().awaitEstablishedConnection();
    }
}
