class SourceMultilineObjectCreationArgumentsSample {

    private final RouteAdmissionCatalog admissionCatalog = new RouteAdmissionCatalog(
        activeTenant.regionCode(),
        policyRegistry.defaultPolicy(),
        telemetryWriter.boundaryClock(),
        auditLedger.retryWindow()
    );

    Object select(ShipmentRequest request, RouteSelection selection, AuditTrail auditTrail) {
        var ledger = new RegionalRouteSelectionLedger(
            request.accountBoundary(),
            selection.primaryCandidate(),
            selection.secondaryCandidate(),
            auditTrail.currentWindow()
        );
        return new ReconciledRouteSnapshot(
            request.envelope(),
            selection.primaryCandidate(),
            selection.fallbackCandidate(),
            auditTrail.currentWindow()
        );
    }

    void publish(RouteSelection selection, DecisionSink decisionSink, AuditTrail auditTrail) {
        decisionSink.record(
            new AuditedRouteDecision(
                selection.primaryCandidate(),
                selection.fallbackCandidate(),
                auditTrail.currentWindow(),
                decisionSink.deliveryMode()
            )
        );
    }

    void fail(ShipmentRequest request, RouteSelection selection, Throwable cause) {
        throw new RouteSelectionFailedException(
            request.traceId(),
            request.regionCode(),
            selection.primaryCandidate(),
            cause
        );
    }

    Object listener(LeaseMonitor monitor, LeaseCallbacks callbacks, RetryBudget retryBudget) {
        return new LeaseLifecycleCoordinator(
            monitor.primaryQueue(),
            monitor.secondaryQueue(),
            callbacks.auditSink(),
            retryBudget.currentWindow()
        ) {
            @Override
            void started(LeaseContext context) {
                context.markReady();
            }
        };
    }
}
