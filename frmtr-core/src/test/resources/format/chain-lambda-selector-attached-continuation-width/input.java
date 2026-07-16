class ChainLambdaSelectorAttachedContinuationWidth {

    Stream<Arguments> supportedBrokerRegistrationVersions() {
        return IntStream.range(BrokerRegistrationRequestData.LOWEST_SUPPORTED_VERSION,
            BrokerRegistrationRequestData.HIGHEST_SUPPORTED_VERSION + 1).mapToObj(version -> Arguments.of((short) version));
    }
}
