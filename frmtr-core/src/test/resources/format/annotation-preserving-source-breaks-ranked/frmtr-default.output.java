class ConnectionConfigurationEndpoint {

    void configureConnection(
            @NotNull(
                message = "identifierMustNotBeBlankForThisSpecificConnectionOperation"
            ) @Size(min = 1, max = 256) String connectionIdentifierValueExtended
    ) {}

    record ConnectionSettings(
        @NotNull(
            message = "connectionIdentifierMustNotBeBlankForThisOperationRightNowPleaseCheckAgainCarefullyAndThoroughly"
        ) String connectionIdentifier
    ) {}
}
