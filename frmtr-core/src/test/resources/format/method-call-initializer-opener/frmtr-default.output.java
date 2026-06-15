class MethodCallInitializerOpenerSample {

    void configure(Receiver receiver) {
        var sample = receiver.call(
            Builders.<Result<AlphaBetaGammaDelta>>wrap(item -> {
                return item.compute(alpha(), beta(), gamma(), delta());
            })
        );
        var commented = receiver.call(
            Builders.<Result<AlphaBetaGammaDelta>>wrap(item -> {
                // keep nested comments from forcing an outer assignment break
                return item.compute(alpha(), beta(), gamma(), delta());
            })
        );
        var scoped = provider().call(
            Builders.<Result<AlphaBetaGammaDelta>>wrap(item -> {
                return item.compute(alpha(), beta(), gamma(), delta());
            }),
            "sample-%s".formatted(alpha())
        );
        var handle = openFixtureEntry(
            new FixtureRequest(DEFAULT_GROUP, owner),
            FixtureSupport.KEY
        ).handle();
        SampleHealthIndicator indicator = indicator(
            cluster("cluster-alpha", primaryStub),
            cluster("cluster-beta", secondaryStub)
        );
        DispatchRecipe transformed = withAuditMode(
            "allow",
            () -> new RouteTransformPipeline(List.of(new SegmentSortTransform())).transform(
                plan
            )
        );
        withAuditMode("allow", () -> verifyNoFailure(() -> RouteLayout.render(
                """
                    import north.Backup;
                    import south.Primary;
                    enum Route { GREEN, AMBER, BLUE }
                    """
            )).hasNoRouteFailures()
        );
        var sampleNameWithEnoughLengthToForceTheReceiverCallOpenerOntoAContinuationLineWhenTheInitializerBreaks =
            receiver.call(
                Builders.<Result<AlphaBetaGammaDelta>>wrap(item -> {
                    return item.compute(alpha(), beta(), gamma(), delta());
                })
            );
    }
}
