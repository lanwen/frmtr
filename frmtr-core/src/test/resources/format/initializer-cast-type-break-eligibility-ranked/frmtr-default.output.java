package sample;

final class CachedSessionRegistryLookup {

    void resolveGroupedSessionSnapshot() {
        var registrationsByGroupIdentifier =
            (Map<String, List<RegistrationDescriptor>>) registryLookup.cachedSnapshotsMap;
    }

    void resolveWideGenericSnapshot() {
        var registrationsByGroupIdentifierForSubsystem = (Map<
            String,
            List<RegistrationRoutingDescriptorForSubsystemAndTenant>
        >) snapshot;
    }
}
