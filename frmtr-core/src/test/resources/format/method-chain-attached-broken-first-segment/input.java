class MethodChainAttachedBrokenFirstSegmentSample {
    void addRouteClause(RouteTypeClause routeClause, List<RouteDoc> header, RouteDeclaration declaration, boolean breakClauses) {
        routeClause
                .print(
                    "primary-routing-policy-with-fallbacks",
                    declaration.primarySegmentsForNetworkGateway(),
                    breakClauses,
                    text -> declaration.segmentClauseWidth(text)
                )
                .ifPresent(header::add);
    }
}
