class EndpointConfigurationLoader {

    void load() {
        HttpClientConfiguration clientConfiguration = resolveHttpClientConfigurationForEndpoint(endpointName, // primary
                fallbackEndpointName); // NOSONAR
    }
}
