class QualifiedStaticChainRootSample {

    void verify(Object actual, Object expected) {
        alpha.beta.gamma.SampleChecks.check(actual)
            .matches(expected.getClass())
            .hasText(expected.toString());
    }

    void configure(Client client, Settings settings) {
        var proxy = Signal.create(
            client.resources(Route.class).withName(settings.routeName())::get
        )
            .subscribeOn(Schedulers.worker())
            .mapNotNull(value -> value.status().host())
            .timeout(Duration.ofSeconds(5));
    }
}
