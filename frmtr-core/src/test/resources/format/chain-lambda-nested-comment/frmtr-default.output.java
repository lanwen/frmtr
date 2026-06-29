class ThreadPoolResolver {

    Optional<ThreadPool> resolvePool(Registry registry, String poolName, PoolConfig config) {
        ThreadPool configuredPool = config.getConfiguredPool();
        String failureMessage = "ThreadPoolRef '" + poolName + "' not found in registry as a ThreadPool instance";
        // The first option is to use an explicitly-configured pool when the configuration provides one
        return Optional.ofNullable(configuredPool)
                // The second option is to look up a referenced pool by its name
                .or(() -> Optional.ofNullable(poolName)
                        // Try to fetch the referenced thread pool from the registry
                        .map(ref -> lookupPoolByReference(registry, poolName, config, ref)
                                // But if the reference is configured yet cannot be resolved,
                                // the caller asked for a pool that does not exist, which is an error
                                .orElseThrow(() -> new IllegalArgumentException(failureMessage))))
                // The third option is to fall back to a brand new default pool for this resolver
                .orElseGet(() -> createDefaultPool(config));
    }
}
