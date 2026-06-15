class MethodChainSegmentArgumentsSample {
    Result waitForSelection(Context context, Duration shortDelay, Duration totalDelay) {
        return Waiter.await()
            .pollInterval(shortDelay)
            .atMost(totalDelay)
            .until(
                () -> {
                    var entries = context.entries();
                    return entries.isEmpty() ? null : entries.getFirst();
                },
                Objects::nonNull
            );
    }

    Receipt acknowledgeRouteAccess(AccessRequest request) {
        return Flow.just(request.primaryToken())
            .map(this::decodeRouteToken)
            .onErrorMap(IllegalArgumentException.class, CredentialEnvelopeException::new)
            .flatMap(token -> {
                return scheduleLedger.authorizeRouteKey(request.getWindowId(), new Allocation.Owner(ROUTE_LEDGER_SOURCE), token);
            })
            .thenReturn(Receipt.empty());
    }

    void reportWorkerFailures(Job job) {
        if (includeStackTrace) {
            job.failedEntries().forEach(entry -> entry.failureCause().ifPresent(cause -> recordFailure(entry.displayPath().toString(), cause)));
            return;
        }
    }

    void inspectDecisionPath(DecisionReport report) {
        assertThat(report.branchSelection().visibleNodes()).singleElement().satisfies(node -> assertThat(node.decision())
            .isPresent());
    }

    void inspectConstructedDecision(DecisionReport report) {
        assertThat(report.branchSelection().visibleNodes()).singleElement().satisfies(node -> new DecisionProbe(node.decision())
            .isPresent());
    }

    void inspectShipmentPolicy(RoutePlan routePlan) {
        assertThat(routePlan.deliveryWindows()).singleElement().satisfies(deliveryWindow -> {
            assertThat(deliveryWindow.routePolicy().scope()).isEqualTo(ShipmentPlacementScope.AVAILABILITY_ZONE);
            assertThat(deliveryWindow.routePolicy().selectors())
                .containsEntry("region", "eu-central-1")
                .containsEntry("delivery-zone", "eu-central-1b");
        });
    }
}
