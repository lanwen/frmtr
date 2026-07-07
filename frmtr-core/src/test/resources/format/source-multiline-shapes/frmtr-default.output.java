class MultilineShapes {

    MultilineShapes(
            @Lookup(
                sample.platform.transport.ConfigurationKeys.PRIMARY_STREAM_PROCESSING_CHANNEL
            ) StreamProcessingDefinition definition,
            Clock clock
    ) {
        this.definition = definition;
        this.clock = clock;
    }

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
        int calendarShift = (
            CalendarWindow.from(clock.instant(), ZoneOffset.UTC).dayIndex()
            == CalendarWindow.from(clock.instant(), tenantOffset).dayIndex()
        )
            ? 1
            : 2;
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

    public void serialize(Predicate value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        consume(value, gen, serializers);
    }

    @Route("throws(value)")
    public Result annotated(@Named(")") Predicate value, Mapper mapper) throws IOException {
        return mapper.map(value);
    }

    private Function<
        Collection<StoredBundleSignalProjection>,
        Publisher<? extends BundleSignal.SignalEnvelopeWithRoutingState>
    > decodeSignalEnvelope() {
        return value;
    }

    private Mono<
        sample.platform.identity.DirectoryLookup.ProxyRoute.RouteKey
    > resolveRoute(sample.security.ActorRef actor, String handle) {
        return routes.resolve(actor, handle);
    }

    Object anonymousMap(PortalProps props, RecordEnvelope record, List<String> entries) {
        var map = new HashMap<String, Object>(
            Map.of(
                "short",
                "%s/card#code=%s".formatted(
                    props.baseUrl(),
                    record.shortCode()
                ),
                "long",
                "%s/card#code=%s".formatted(
                    props.baseUrl(),
                    TokenCodec.HASH.encode(Long.parseLong(record.id()))
                )
            )
        ) {
            {
                if (!entries.isEmpty()) {
                    put("entries", entries);
                }
            }
        };

        return map;
    }

    SignalEmitter eventEmitter(RoutingConfig routingConfig, EventPublisher eventPublisher) {
        return event -> eventPublisher.send(
            routingConfig.topics().get(WorkspaceLifecycleProjectionEventMessage.class).name(),
            event
        ).then();
    }

    void resources() {
        try (Resource handle = new Resource(network, auth, service, null)) {
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

    void registry(Scanner scanner) {
        for (CandidateDescriptor candidate : scanner.findCandidateComponents(
            ComponentNames.packageName(DefaultBillingClient.class)
        )) {
            register(candidate);
        }
    }

    void registryNearLimit(Scanner scanner) {
        for (UnitDefinition candidate : scanner.findCandidateComponents(
            ClassNames.getPackageName(DefaultClient.class)
        )) {
            register(candidate);
        }
    }

    void combine(Environment environment, List<PropertySource> sources) {
        Iterable<ConfigurationPropertySource> combinedSources = () -> Stream.concat(
            StreamSupport.stream(environment.sources().spliterator(), false),
            StreamSupport.stream(ConfigurationPropertySources.from(sources).spliterator(), false)
        ).iterator();
    }
}

record VisibleRoute(
    String id,
    String name /* visible route label */,
    RouteSource source /*, List<RouteResource> resources */
) {
    record RouteSource(String id, String name) {}
}

@interface ClientBinding {
    Class<? extends BindingRegistrar.BindingCustomizer> customizer()
        default BindingRegistrar.BindingCustomizer.NoopCustomizer.class;
}
