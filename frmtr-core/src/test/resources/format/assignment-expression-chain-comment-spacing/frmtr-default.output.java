public class AssignmentExpressionChainCommentSpacing {

    private RouteTable routeTable;

    public void rebuildFromCreation() {
        routeTable = new RouteTable()
                .withGateway(PRIMARY_GATEWAY, ACTIVE)
                /** Javadoc link interspersed before the empty-argument selector. */
                .seal()
                .withGateway(STANDBY_GATEWAY, DRAINING)
                /* Block link interspersed before the empty-argument selector. */
                .commit();
    }

    public void rebuildFromCall() {
        routeTable = tableFactory
                .create()
                .withGateway(PRIMARY_GATEWAY, ACTIVE)
                /* Block link interspersed in a method-call-rooted chain value. */
                .seal()
                .commit();
    }

    public void rebuildWithoutComment() {
        routeTable = new RouteTable()
                .withGateway(PRIMARY_GATEWAY, ACTIVE)
                .seal()
                .withGateway(STANDBY_GATEWAY, DRAINING)
                .commit();
    }
}
