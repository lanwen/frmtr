class EndpointResolver {

    Endpoint resolve(boolean overrideActive, Endpoint override, Registry registry) {
        return overrideActive ? override : firstHealthy(/* fallback */ registry);
    }
}
