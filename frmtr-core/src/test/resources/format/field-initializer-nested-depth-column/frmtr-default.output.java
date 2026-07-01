class RoutingRegistry {

    static final class RegionShard {

        private final Set<ReasonablyLongProviderDescriptorTypeName> seenRouteProviders = Collections.newSetFromMap(
            new ConcurrentHashMap<>(64)
        );

        private final Set<ReasonablyLongProviderDescriptorTypeName> seenRouteReplicas = /* replica set */
            Collections.newSetFromMap(new ConcurrentHashMap<>(64));
    }
}
