package sample;

final class SourceMultilineArrayRowInitializer {

    private final Object[][] directRoutes = {
        {
            new RouteStep(
                route("north-control-plane", "steady-retry"),
                scheduler.call("primary-ledger", window(15))
            ),
            Outcome.ACCEPTED,
        },
        {
            scheduler.call(
                "shadow-ledger",
                route("south-control-plane", "slow-retry"),
                window(30)
            ),
            new RouteStep(route("archive-plane", "steady-drain"), Outcome.RETRYABLE),
        },
    };

    void install(RouteBook book, RoutePlanner planner) {
        if (book.hasActivePlan()) {
            Object[][] createdRoutes = new Object[][] {
                {
                    planner.createStep(book.lookup("billing-ledger-primary"), throttle("burst-with-long-fuse")),
                    book.bind("ledger-primary", channel("orders")),
                },
                {
                    book.bind("ledger-shadow", channel("archive")),
                    planner.createStep(book.lookup("billing-ledger-shadow"), throttle("steady")),
                },
            };
            register(createdRoutes);
        }
    }
}
