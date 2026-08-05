class MethodChainTwoSelectorCallRootWidthSample {
    void verify(RegistryService service, Coordinator coordinator, String sessionId, VerificationSpec spec) {
        // Two-selector call-rooted chain that fits flat.
        expectThat(result).as("round-trip for %s", spec.label()).isNotNull();

        // Two-selector call-rooted chain whose flat form overflows — fans.
        expectThat(service.resolveRegistration(buildSnapshotRequest(coordinator, sessionId))).as("snapshot for session %s", sessionId).isPresent();

        // Block-lambda selector forces the chain off the width-driven path.
        getPayload(message).entries().forEachRemaining(entry -> {
            registry.put(entry.getKey(), normalize(entry.getValue()));
        });
    }
}
