class ControlConditionComments {

    void commentInsideWhileCondition(RouteCursor cursor) {
        while (
            // keep polling until the route snapshot is visible
            cursor.hasPendingRoute()
        ) {
            cursor.refresh();
        }
    }

    void commentInsideSwitchStatementSelector(RouteCursor cursor) {
        switch (
            // read selector after cursor state is refreshed
            cursor.state()
        ) {
            case "ready" -> cursor.dispatch();
            default -> cursor.defer();
        }
    }

    void commentAfterIfCondition(RouteCursor cursor) {
        if (cursor.hasPendingRoute()) // keep the body delayed until route state is stable
        {
            cursor.refresh();
        }
    }

    void commentAfterIfConditionWithNonBlockElse(RouteCursor cursor) {
        if (cursor.hasPendingRoute()) // keep the body delayed until route state is stable
            cursor.refresh(); else cursor.defer();
    }

    void commentAfterSwitchStatementSelector(RouteCursor cursor) {
        switch (cursor.state()) // keep selector comment outside the condition
        {
            case "ready" -> cursor.dispatch();
            default -> cursor.defer();
        }
    }

    String commentInsideSwitchExpressionSelector(RouteEvent event) {
        return switch (
            // use the normalized event kind for routing
            event.normalizedKind()
        ) {
            case "created" -> "open";
            default -> "closed";
        };
    }
}
