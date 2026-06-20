class LineWidthOvercollapseGuards {
    void recordNestedMessage(RouteRecorder recorder, RoutePlan plan, RouteContext context, Throwable cause) {
        recorder.recordCheckpoint(
            "route-selection",
            MessageEnvelope.describe(
                "selected route "
                    + plan.routeName()
                    + " for request "
                    + context.requestIdentifier()
                    + " after fallback "
                    + context.recoveryWindow().displayName()
                    + " using catalog "
                    + context.catalogSnapshot().friendlyName()
            ),
            cause
        );
    }

    void configureFluentRoute(RoutePlanner planner, RouteCatalog catalog, RetryWindow retryWindow) {
        planner
            .startCandidate(catalog.primaryRouteIdentifier())
            .withWindow(retryWindow.limitFor("standard-recovery-path")) // normalized before fallback
            .withFallback(catalog.secondaryRouteIdentifier())
            .withDecisionLabel("manual-review-before-dispatch")
            .commitTo(catalog.currentAuditTrail());
    }

    void recover(SecureRouteGateway gateway, RouteJournal journal) {
        try {
            gateway.openForJournal(journal);
        } catch (
            CatalogSnapshotUnavailableException
                | RetryWindowExpiredException
                | RouteRecoveryRejectedException
                | OperatorReviewRequiredException failure
        ) {
            journal.recordRecoveryFailure(failure);
        }
    }

    void keepLongPredicateCallGrouped(Call root, Call firstCall, Tail suffix, Width lineBudget) {
        if (compactRootFinalSegmentLineOverflows(root, firstCall, suffix, lineBudget)) {
            sink(root);
        }
    }

    boolean keepLongSimpleConjunction(RouteCall expression, String compactSegment, WidthBudget compactSegmentWidth) {
        return overwideTypeLikeScopeSegment(expression)
            && compactSegmentWidth.applyAsInt(compactSegment) > options.lineWidth();
    }
}
