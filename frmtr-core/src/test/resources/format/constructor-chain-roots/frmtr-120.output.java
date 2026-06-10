class ConstructorChainRoots {

    void examples() {
        var three = new EndpointFactory(alpha, beta, gamma)
            .generate("vm-1234", Instance.builder().privateDnsName("very-private-1.internal").build())
            .blockFirst(Duration.ofSeconds(1));

        var four = new EndpointFactory(
            alpha,
            beta,
            gamma,
            delta
        )
            .generate("vm-5678", Instance.builder().privateDnsName("very-private-2.internal").build())
            .blockFirst(Duration.ofSeconds(1));

        var nested = registry.attach(
            new ChannelActor(
                alpha,
                beta,
                gamma,
                delta,
                epsilon,
                zeta,
                eta
            ).create(),
            () -> {
                return monitor.ready();
            }
        );

        var fromSourceMultiline = new EndpointFactory(
            alpha,
            beta,
            gamma
        ).connect(certificate);

        ManagedEndpointWithVerboseType fromSourceMultilineWithLongDeclarationPrefix = new EndpointFactory(
            alpha,
            beta,
            gamma
        ).connect(certificate);

        try (
            ManagedEndpoint endpoint = new EndpointFactory(
                alpha,
                beta,
                gamma
            ).connect(certificate)
        ) {
            registry.attach(endpoint);
        }
    }
}
