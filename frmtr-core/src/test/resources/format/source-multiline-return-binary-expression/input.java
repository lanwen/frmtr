class SourceMultilineReturnBinaryExpression {
    boolean returnParenthesizedCallBinary(RouteFlags flags, RoutePlan plan) {
        return (
            flags.primaryRouteReady()
            && plan.hasAllowedTransitionBetweenSegments(
                plan.currentSegmentDescriptor(),
                plan.previousSegmentDescriptor(),
                plan.fallbackSegmentDescriptor()
            )
        );
    }

    boolean methodCallLeftBinaryWithSourceMultilineArgs(RouteFlags flags, RoutePlan plan) {
        return flags.primaryRouteReady()
            && plan.hasAllowedTransitionBetweenSegments(
                plan.currentSegmentDescriptor(),
                plan.previousSegmentDescriptor(),
                plan.fallbackSegmentDescriptor()
            )
            && flags.secondaryRouteReady();
    }

    boolean parenthesizedAndUnderOr(RouteFlags flags) {
        return flags.forceRouteApproval()
            || (
                flags.primaryRouteReady()
                && flags.secondaryRouteReady()
            )
            || flags.manualRouteOverride();
    }

    boolean binarySuffixAroundLongMethodCall(RouteFlags flags, RoutePlan plan) {
        return flags.primaryRouteReady()
            || plan.hasAllowedTransitionBetweenSegments(
                plan.currentSegmentDescriptor(),
                plan.previousSegmentDescriptor(),
                plan.fallbackSegmentDescriptor()
            ) && flags.secondaryRouteReady()
            || flags.manualRouteOverride();
    }

    boolean returnLongCallBinarySuffix(RouteFlags flags, RoutePlan plan) {
        return plan.hasAllowedTransitionBetweenSegments(
            plan.currentSegmentDescriptor(),
            plan.previousSegmentDescriptor(),
            plan.fallbackSegmentDescriptor()
        ) && flags.secondaryRouteReady();
    }
}
