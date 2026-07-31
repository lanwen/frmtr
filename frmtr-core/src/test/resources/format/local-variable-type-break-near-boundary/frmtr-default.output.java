package demo;

class ConnectionPoolRegistry {

    void trackActiveLeases() {
        Map<PoolIdentifier, ConnectionPoolLeaseRegistry> activeConnectionLeasesByPoolIdentifierAcrossAllRegionsNightly =
            new ConcurrentHashMap<>();
        Map<
            PoolIdentifier,
            ConnectionPoolLeaseRegistry
        > activeConnectionLeasesByPoolIdentifierAcrossAllRegionsNightlyNow = new ConcurrentHashMap<>();
        Registry<Subject> registryWithArgumentsAcrossAllAvailableConnectionPools = new Registry<>(
            initialSubjects(),
            initialMode(),
            initialOwner()
        );
    }
}
