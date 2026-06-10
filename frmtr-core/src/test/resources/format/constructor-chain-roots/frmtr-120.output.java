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
    }
}
