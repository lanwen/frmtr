class SourceMultilineConditionalMethodCallBranch {

    RoutePlan initializerBranch(
            RouteContext routeContext,
            RouteBuilder routeBuilder,
            RouteFallback routeFallback
    ) {
        RoutePlan selectedRoute = routeContext.primaryReady()
            ? routeBuilder.composeRoute(
                routeContext.primaryCluster(),
                routeContext.backupCluster(),
                routeContext.deliveryWindow(),
                routeContext.auditTrail()
            )
            : routeFallback.basicRoute(routeContext.queueName());
        return selectedRoute;
    }

    RoutePlan returnBranch(
            RouteContext routeContext,
            RouteBuilder routeBuilder,
            RouteFallback routeFallback
    ) {
        return routeContext.secondaryReady()
            ? routeFallback.basicRoute(routeContext.queueName())
            : routeBuilder.composeRoute(
                routeContext.primaryCluster(),
                routeContext.backupCluster(),
                routeContext.deliveryWindow(),
                routeContext.auditTrail()
            );
    }

    RoutePlan thenMethodCallBranch(
            RouteContext routeContext,
            RouteBuilder routeBuilder,
            RouteFallback routeFallback
    ) {
        return routeContext.primaryReady()
            ? routeBuilder.composeRoute(
                routeContext.primaryCluster(),
                routeContext.backupCluster(),
                routeContext.deliveryWindow(),
                routeContext.auditTrail()
            )
            : routeFallback.basicRoute(routeContext.queueName());
    }

    RoutePlan elseMethodCallBranch(
            RouteContext routeContext,
            RouteBuilder routeBuilder,
            RouteFallback routeFallback
    ) {
        return routeContext.primaryReady()
            ? routeFallback.basicRoute(routeContext.queueName())
            : routeBuilder.composeRoute(
                routeContext.primaryCluster(),
                routeContext.backupCluster(),
                routeContext.deliveryWindow(),
                routeContext.auditTrail()
            );
    }

    RoutePlan nestedMethodCallBranch(
            RouteContext routeContext,
            RouteBuilder routeBuilder,
            RouteFallback routeFallback
    ) {
        return routeContext.primaryReady()
            ? routeBuilder.composeRoute(
                routeContext.primaryCluster(),
                routeContext.backupCluster(),
                routeContext.deliveryWindow(),
                routeContext.auditTrail()
            )
            : routeContext.secondaryReady()
                ? routeFallback.basicRoute(routeContext.queueName())
                : routeBuilder.composeRoute(
                    routeContext.primaryCluster(),
                    routeContext.backupCluster(),
                    routeContext.deliveryWindow(),
                    routeContext.auditTrail()
                );
    }
}
