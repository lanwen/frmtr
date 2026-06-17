class SourceMultilineObjectChainInitializer {

    public CatalogContainer containerWithMultipleLabels = new CatalogContainer(ArtifactName.parse("runner:3.17"))
        .withLabels(labelMap);

    public CatalogContainer firstPassContainerWithMultipleLabels =
        new CatalogContainer(ArtifactName.parse("runner:3.17")).withLabels(
            labelMap
        );

    public CatalogContainer sourceContainerWithFile = new CatalogContainer(
        ArtifactName.parse("runner:3.17")
    )
        .withFileInHomeFolder(
            MountableFile.forHostPath("src/test/resources/additionalFile.txt"),
            "/path/in/home/folder"
        );

    public CatalogContainer firstPassContainerWithFile = new CatalogContainer(
        ArtifactName.parse("runner:3.17")
    ).withFileInHomeFolder(
        MountableFile.forHostPath("src/test/resources/additionalFile.txt"),
        "/path/in/home/folder"
    );

    Client sourceDetachedBuild(ContainerProbe container) {
        Client client = new HttpClientBuilder(
            "http://" + container.host() + ":" + container.controlPort() + "/api"
        )
            .build();
        return client;
    }

    Client firstPassDetachedBuild(ContainerProbe container) {
        if (client == null) {
            client = new HttpClientBuilder(
                "http://" + container.host() + ":" + container.controlPort() + "/api"
            )
                    .build();
        }
        return client;
    }

    void sourceLongDatabaseName(ConnectionOptions options) {
        CatalogContainer container = new CatalogContainer(image)
            .withDatabaseName((String) options.getRequiredValue(ConnectionFactoryOptions.DATABASE));
        sink(container);
    }

    void firstPassLongDatabaseName(ConnectionOptions options) {
        CatalogContainer container = new CatalogContainer(image).withDatabaseName((String) options.getRequiredValue(ConnectionFactoryOptions.DATABASE));
        sink(container);
    }

    void sourceLongCondition() {
        ConditionResult result = new TestEnabledIfCatalogAvailableCondition(true)
            .evaluateExecutionCondition(extensionContext(DisabledWithoutCatalog.class));
        sink(result);
    }

    void firstPassLongCondition() {
        ConditionResult result = new TestEnabledIfCatalogAvailableCondition(true).evaluateExecutionCondition(extensionContext(DisabledWithoutCatalog.class));
        sink(result);
    }

    // sampleRule {
    public BrowserClient sourceBrowser = new BrowserClient("browser/standalone-stable:4.13.0")
        // marker }
        .withNetwork(NETWORK);

    // sampleRule {
    public BrowserClient firstPassBrowser = new BrowserClient("browser/standalone-stable:4.13.0")// marker }
    .withNetwork(NETWORK);
}
