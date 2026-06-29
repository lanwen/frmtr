class RoutingTablePrefixWidth {

    RouteTable routeTable;

    void overWide() {
        routeTable = new RouteTableConfigBuilder()
                .setName("primaryRoutingDomainHandler1")
                .seal()
                .commit()
                .materialize();
    }

    void fitting() {
        routeTable = new RouteTableConfigBuilder().setName("primaryRoutingDomainHandler").seal().commit().materialize();
    }
}
