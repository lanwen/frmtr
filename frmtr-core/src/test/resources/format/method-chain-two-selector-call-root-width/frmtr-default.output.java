class MethodChainTwoSelectorCallRootWidthSample {

    void verify(RegistryService service, Coordinator coordinator, String sessionId, VerificationSpec spec) {
        // Two-selector call-rooted chain that fits flat.
        expectThat(result).as("round-trip for %s", spec.label()).isNotNull();

        // Two-selector call-rooted chain whose flat form overflows — fans.
        expectThat(service.resolveRegistration(buildSnapshotRequest(coordinator, sessionId)))
                .as("snapshot for session %s", sessionId)
                .isPresent();

        // Block-lambda selector on a call-root chain — stays flat when the compact form fits.
        getPayload(jobId).fields().forEach(field -> { index.add(field.key(), field.value()); });
    }
}
