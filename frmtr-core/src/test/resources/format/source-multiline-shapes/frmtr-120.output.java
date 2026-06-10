class MultilineShapes {

    Object chain() {
        await()
            .atMost(Duration.ofSeconds(5))
            .untilAsserted(() -> verify(worker, times(2)).run());
        Key key = new KeyMaker(Curve.P_256)
            .algorithm(Algo.ES)
            .build();
        value = input == null || input.isEmpty()
            ? List.of()
            : List.copyOf(input);
        var routed = keys.isEmpty()
            ? trimmedValues(source.entries())
            : List.<String>of();
        return pkg.sample.Widget.builder()
            .name("demo")
            .build();
    }

    Object wrapper() {
        return Fixture.strict(
            "alpha",
            Surface.REMOTE,
            Event.Type.class,
            ref -> {
                ref.accept("x");
                return new Wrapped("id", new Done());
            }
        );
    }

    public void serialize(
        Predicate value,
        JsonGenerator gen,
        SerializerProvider serializers
    )
        throws IOException {
        consume(value, gen, serializers);
    }

    @Route("throws(value)")
    public Result annotated(
        @Named(")") Predicate value,
        Mapper mapper
    )
        throws IOException {
        return mapper.map(value);
    }

    void resources() {
        try (
            Resource handle = new Resource(
                network,
                auth,
                service,
                null
            )
        ) {
            use(handle);
        }
        try (
            @Pinned
            Resource annotated = new Resource(
                registry.resolve("throws("),
                new Initializer() {
                    String value() {
                        return "}";
                    }
                }
            );
        ) {
            use(annotated);
        }
    }
}
