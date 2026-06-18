class SourceMultilineObjectCreationArgumentsSample {
    private final RouteAdmissionCatalog admissionCatalog = new RouteAdmissionCatalog(activeTenant.regionCode(), policyRegistry.defaultPolicy(), telemetryWriter.boundaryClock(), auditLedger.retryWindow());

    Object select(ShipmentRequest request, RouteSelection selection, AuditTrail auditTrail) {
        var ledger = new RegionalRouteSelectionLedger(request.accountBoundary(), selection.primaryCandidate(), selection.secondaryCandidate(), auditTrail.currentWindow());
        return new ReconciledRouteSnapshot(request.envelope(), selection.primaryCandidate(), selection.fallbackCandidate(), auditTrail.currentWindow());
    }

    void publish(RouteSelection selection, DecisionSink decisionSink, AuditTrail auditTrail) {
        decisionSink.record(new AuditedRouteDecision(selection.primaryCandidate(), selection.fallbackCandidate(), auditTrail.currentWindow(), decisionSink.deliveryMode()));
    }

    void fail(ShipmentRequest request, RouteSelection selection, Throwable cause) {
        throw new RouteSelectionFailedException(request.traceId(), request.regionCode(), selection.primaryCandidate(), cause);
    }

    void reject(PhraseRequest request, Throwable cause) {
        throw new PhrasePolicyException(
            "Access phrase must include entries from three of the following four groups:\n" +
                " - Latin uppercase letters (A through Z)\n" +
                " - Latin lowercase letters (a through z)\n" +
                " - Base ten digits (0 through 9)\n" +
                " - Non alphabetic marks (!, $, #, %, and similar)",
            request.accountBoundary(),
            request.traceId(),
            cause
        );
    }

    Object listener(LeaseMonitor monitor, LeaseCallbacks callbacks, RetryBudget retryBudget) {
        return new LeaseLifecycleCoordinator(monitor.primaryQueue(), monitor.secondaryQueue(), callbacks.auditSink(), retryBudget.currentWindow()) {
            @Override
            void started(LeaseContext context) {
                context.markReady();
            }
        };
    }

    Object conciseListener(LeaseMonitor monitor) {
        return new LeaseLifecycleCoordinator("ready:" +
            monitor.primaryQueue()) {
            @Override
            void started(LeaseContext context) {
                context.markReady();
            }
        };
    }
}
