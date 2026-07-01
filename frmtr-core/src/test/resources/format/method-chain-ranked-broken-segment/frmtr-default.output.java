class MethodChainRankedBrokenSegmentSample {

    void rankedCompactBrokenSegment(RouteRegistry registry) {
        ConnectionPlanner.between(primaryDataCenter, secondaryDataCenter).establishRoute(
            activeSessionToken,
            fallbackSessionToken
        );
    }

    void commentBearingChainStaysOnLadder(RouteRegistry registry) {
        ConnectionPlanner.between(primaryDataCenter, secondaryDataCenter) // preserve the planner receiver comment
                .establishRoute(activeSessionToken, fallbackSessionToken);
    }
}
