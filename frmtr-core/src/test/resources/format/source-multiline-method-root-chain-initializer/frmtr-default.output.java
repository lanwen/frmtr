class SourceMultilineMethodRootChainInitializer {

    private static final Map<String, String> LABELS = Stream.of(
        primaryLabels.entrySet().stream(),
        secondaryLabels.entrySet().stream()
    )
            .flatMap(Function.identity())
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

    private static final Map<String, String> SOURCE_LABELS = Stream.of(
        PrimaryLabelFactory.DEFAULT_LABELS.entrySet().stream(),
        SecondaryLabelRegistry.instance().getLabels().entrySet().stream()
    )
            .flatMap(Function.identity())
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

    private static final Map<String, String> DEFAULT_LABELS = Stream.of(
        PrimaryLabelFactory.DEFAULT_LABELS.entrySet().stream(),
        SecondaryLabelRegistry.instance().getLabels().entrySet().stream()
    )
            .flatMap(Function.identity())
            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

    void sourceState(ConnectionFactory connectionFactory) {
        Number result = Flux.usingWhen(
            connectionFactory.create(),
            connection -> connection.createStatement(createProbeQuery(expected)).execute(),
            Connection::close
        )
                .flatMap(rows -> rows.map((row, meta) -> (Number) row.get(0)))
                .blockFirst();
    }

    void firstPassFlatRootState(ConnectionFactory connectionFactory) {
        Number result = Flux.usingWhen(
            connectionFactory.create(),
            connection -> connection.createStatement(createTestQuery(expected)).execute(),
            Connection::close
        )
                .flatMap(rows -> rows.map((row, meta) -> (Number) row.get(0)))
                .blockFirst();
    }

    void nestedFirstPassFlatRootState(ConnectionFactory connectionFactory) {
        try {
            Number result = Flux.usingWhen(
                connectionFactory.create(),
                connection -> connection.createStatement(createTestQuery(expected)).execute(),
                Connection::close
            )
                    .flatMap(rows -> rows.map((row, meta) -> (Number) row.get(0)))
                    .blockFirst();
        } finally {
            close();
        }
    }

    void firstPassState(ConnectionFactory connectionFactory) {
        Number result = Flux.usingWhen(
            connectionFactory.create(),
            connection -> connection.createStatement(createProbeQuery(expected)).execute(),
            Connection::close
        )
                .flatMap(rows -> rows.map((row, meta) -> (Number) row.get(0)))
                .blockFirst();
    }

    void sourceNestedBuilderRootState(ContainerProbe container) {
        Client client = Client.create(
            Settings.create(
                ManagedChannelBuilder.forAddress(
                    container.getHost(),
                    container.getExtremelyLongEmulatorGrpcControlPlanePortForFixture()
                )
                        .usePlaintext()
                        .build()
            )
        );
    }

    void firstPassNestedBuilderRootState(ContainerProbe container) {
        Client client = Client.create(
            Settings.create(
                ManagedChannelBuilder.forAddress(
                    container.getHost(),
                    container.getExtremelyLongEmulatorGrpcControlPlanePortForFixture()
                )
                        .usePlaintext()
                        .build()
            )
        );
    }

    void sourceNestedBuilderShortRootState(ContainerProbe container) {
        Client client = Client.create(
            Settings.create(
                ManagedChannelBuilder.forAddress(container.getHost(), container.getEmulatorGrpcPort())
                        .usePlaintext()
                        .build()
            )
        );
    }

    void firstPassNestedBuilderShortRootState(ContainerProbe container) {
        Client client = Client.create(
            Settings.create(
                ManagedChannelBuilder.forAddress(container.getHost(), container.getEmulatorGrpcPort())
                        .usePlaintext()
                        .build()
            )
        );
    }

    Stream<StoreAdapter> sourceStaticRootState(Object testInstance) {
        return ReflectionSupport.findFields(
            testInstance.getClass(),
            isRestartContainer(),
            HierarchyTraversalMode.TOP_DOWN
        )
                .stream()
                .map(field -> getContainerInstance(testInstance, field));
    }

    Stream<StoreAdapter> firstPassStaticRootState(Object testInstance) {
        return ReflectionSupport.findFields(
            testInstance.getClass(),
            isRestartContainer(),
            HierarchyTraversalMode.TOP_DOWN
        )
                .stream()
                .map(field -> getContainerInstance(testInstance, field));
    }

    void sourceInitializerStaticRootState(ConnectionFactoryOptions options) {
        containerProvider = StreamSupport.stream(
            ServiceLoader.load(PrimaryDatabaseContainerProvider.class).spliterator(),
            false
        )
                .filter(provider -> provider.supports(options))
                .findAny()
                .orElseThrow(() -> new IllegalArgumentException("Missing provider for " + options));
    }

    void firstPassInitializerStaticRootState(ConnectionFactoryOptions options) {
        containerProvider = StreamSupport.stream(
            ServiceLoader.load(PrimaryDatabaseContainerProvider.class).spliterator(),
            false
        )
                .filter(provider -> provider.supports(options))
                .findAny()
                .orElseThrow(() -> new IllegalArgumentException("Missing provider for " + options));
    }
}
