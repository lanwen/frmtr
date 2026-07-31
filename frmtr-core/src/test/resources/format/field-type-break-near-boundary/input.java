package demo;

class ConnectionPoolRegistry {

    private final Map<PoolIdentifier, ConnectionPoolLeaseRegistry> activeConnectionLeasesByPoolIdentifierAcrossRegions = new ConcurrentHashMap<>();

    private final Map<PoolIdentifier, ConnectionPoolLeaseRegistry> activeConnectionLeasesByPoolIdentifierAcrossRegionNow = new ConcurrentHashMap<>();

    private final Registry<Subject> registryWithArguments = new Registry<>(initialSubjects(), initialMode(), initialOwner());
}
