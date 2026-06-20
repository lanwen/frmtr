class SourceMultilineControlConditionSample {

    RoutePolicy routePolicy;

    RouteContext routeContext;

    SegmentPlan segmentPlan;

    DispatchWindow dispatchWindow;

    Registry registry;

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

    void keepWideControlPredicate(
            Expr expression,
            String flat,
            String opening,
            String closing,
            WidthBudget conditionLineWidth
    ) {
        if (logicalConditionWithControlContextOverflows(expression, flat, opening, closing, conditionLineWidth)) {
            segmentPlan.reserve();
        }
    }

    void preserveSourceMultilineStreamPredicate(List<String> lines) {
        if (
            lines.size() < 3
            || lines.stream()
                    .skip(1)
                    .limit(lines.size() - 2)
                    .map(String::stripLeading)
                    .anyMatch(line -> !line.startsWith("*"))
        ) {
            segmentPlan.reserve();
        }
    }

    void preserveParenthesizedRegistryPredicate(String routeName) {
        if (
            ((this.registry != null
                && (!this.registry.containsRoute(routeName) || !this.registry.isSingletonRoute(routeName)))
            || (this.registry instanceof SingletonRouteRegistry singletonRoutes
                && singletonRoutes.containsSingletonRoute(routeName)))
        ) {
            segmentPlan.reserve();
        }
    }
}
