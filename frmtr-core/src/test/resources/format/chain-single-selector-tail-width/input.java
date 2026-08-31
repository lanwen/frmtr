class SessionOwnershipAssertionSample {
    void tailWithNoArgumentsOverflows() {
        assertThat(session.isOwnedBy(new SessionToken("svc-registry", "a-tenant-identifier-that-is-quite-long"))).isFalse();
    }

    void fieldAccessReceiverOverflows() {
        assertThat(registry.session.isOwnedBy(new SessionToken("svc-registry", "a-tenant-identifier-quite-long"))).isFalse();
    }

    void tailCarryingItsOwnArgumentOverflows() {
        assertThat(session.isOwnedBy(new SessionToken("svc-registry", "a-tenant-identifier-that-is-quite-long"))).isNotEqualTo(expectedOwnershipVerdict);
    }

    void tailFitsAlreadyStaysAttached() {
        assertThat(session.isOwnedBy(token)).isFalse();
    }
}
