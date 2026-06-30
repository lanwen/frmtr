class ConstructorChainRoots {

    void examples() {
        var three = new EndpointFactory(alpha, beta, gamma)
                .generate(
                    "vm-1234",
                    Instance.builder()
                            .privateDnsName("very-private-1.internal")
                            .build()
                )
                .blockFirst(Duration.ofSeconds(1));

        var four = new EndpointFactory(
            alpha,
            beta,
            gamma,
            delta
        )
                .generate(
                    "vm-5678",
                    Instance.builder()
                            .privateDnsName("very-private-2.internal")
                            .build()
                )
                .blockFirst(Duration.ofSeconds(1));

        var nested = registry.attach(
            new ChannelActor(alpha, beta, gamma, delta, epsilon, zeta, eta).create(),
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

        try (ManagedEndpoint endpoint = new EndpointFactory(alpha, beta, gamma).connect(certificate)) {
            registry.attach(endpoint);
        }
    }
}

public class ConstructorSamples {

    public ConstructorSamples() {
        this(true);
        System.out.println("empty constructor");
    }

    ConstructorSamples(boolean enabled) {
        super();
        System.out.println("constructor with boolean " + enabled);
    }

    ConstructorSamples(boolean enabled, boolean visible) {
        this();
        System.out.println("constructor with boolean " + enabled + " and " + visible);
    }

    ConstructorSamples(
            boolean enabled,
            boolean visible,
            boolean archived,
            boolean locked,
            boolean verified,
            boolean replicated
    ) {
        this();
        System.out.println("constructor with six parameters that should wrap");
    }

    ConstructorSamples() {
        super("primary", "secondary", "archival", "when capacity is constrained", "should wrap well");
        System.out.println("constructor with super that wraps");
    }

    ConstructorSamples() {
        super("compact parameter", "fits");
        System.out.println("constructor with super that does not wrap");
    }

    ConstructorSamples() {
        this("primary", "secondary", "archival", "when capacity is constrained", "should wrap well");
        System.out.println("constructor with this that wraps");
    }

    ConstructorSamples() {
        this("compact parameter", "fits");
        System.out.println("constructor with this that does not wrap");
    }

    public <T> GenericConstructor(T genericParameter) {}

    public <T> GenericConstructor(T genericParameter) {}

    FlexibleConstructorBody(int coordinate) {
        this.x = coordinate;
        super(coordinate);
    }

    FlexibleConstructorBody() {
        var defaultCoordinate = 42;
        this(defaultCoordinate);
    }
}
