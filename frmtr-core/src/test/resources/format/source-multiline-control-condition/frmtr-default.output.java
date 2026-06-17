class SourceMultilineControlConditionSample {

    RoutePolicy routePolicy;

    RouteContext routeContext;

    SegmentPlan segmentPlan;

    DispatchWindow dispatchWindow;

    void selectIf() {
        if (
            routePolicy.accepts(
                routeContext.primaryStop(),
                routeContext.backupStop(),
                segmentPlan.candidateWindow(),
                dispatchWindow.retryBudget()
            )
            && segmentPlan.hasOpenSegment()
        ) {
            segmentPlan.reserve();
        }

        if (
            segmentPlan.hasOpenSegment()
            || routePolicy.accepts(
                routeContext.primaryStop(),
                routeContext.backupStop(),
                segmentPlan.candidateWindow(),
                dispatchWindow.retryBudget()
            )
        ) {
            segmentPlan.reserve();
        }
    }

    void drainWhile() {
        while (
            routePolicy.accepts(
                routeContext.primaryStop(),
                routeContext.backupStop(),
                segmentPlan.candidateWindow(),
                dispatchWindow.retryBudget()
            )
            && segmentPlan.hasOpenSegment()
        ) {
            segmentPlan.advance();
        }

        while (
            segmentPlan.hasOpenSegment()
            || routePolicy.accepts(
                routeContext.primaryStop(),
                routeContext.backupStop(),
                segmentPlan.candidateWindow(),
                dispatchWindow.retryBudget()
            )
        ) {
            segmentPlan.advance();
        }
    }
}
