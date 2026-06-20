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
            || (flags.primaryRouteReady()
                && flags.secondaryRouteReady())
            || flags.manualRouteOverride();
    }

    boolean binarySuffixAroundLongMethodCall(RouteFlags flags, RoutePlan plan) {
        return flags.primaryRouteReady()
            || (plan.hasAllowedTransitionBetweenSegments(
                plan.currentSegmentDescriptor(),
                plan.previousSegmentDescriptor(),
                plan.fallbackSegmentDescriptor()
            )
                && flags.secondaryRouteReady())
            || flags.manualRouteOverride();
    }

    boolean returnLongCallBinarySuffix(RouteFlags flags, RoutePlan plan) {
        return plan.hasAllowedTransitionBetweenSegments(
            plan.currentSegmentDescriptor(),
            plan.previousSegmentDescriptor(),
            plan.fallbackSegmentDescriptor()
        ) && flags.secondaryRouteReady();
    }

    boolean sourceMultilineLambdaCallBody(Optional<Call> body, Lambda lambda, Call call, Shape shape, Raw source) {
        return body.isPresent()
            && (lambda.bodyStartsAfterHeader()
                || shape.spansMultipleLines(call)
                || source.rawWithoutOwnComment(call).contains("\n")
                || body.filter(shape::methodCallArgumentsSpanMultipleLines).isPresent());
    }

    boolean chainRootUsesPromotedType(RouteAnalysis analysis) {
        return promotesFirstCall(analysis.root())
            || analysis.calls()
                    .stream()
                    .map(RouteCall::scope)
                    .flatMap(Optional::stream)
                    .anyMatch(this::promotesFirstCall);
    }

    boolean compactCallBodyOverflows(Call expression, String compact, Width width, Options options) {
        return width.currentIndented(compact) > options.lineWidth()
            || rootLineWidth(expression, compact) > options.lineWidth()
            || (expression.startsAfterScopeLine()
                && selectorLineWidth(expression, compact) > options.lineWidth())
            || ((sourceMultilineTypeLikeRoot(expression) || expression.startsAfterScopeLine())
                && width.firstLine(compact) > options.lineWidth());
    }
}
