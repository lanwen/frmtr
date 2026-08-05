class SchedulerLog {

    void refusedConnection(Logger log, Enrollment enrollment) {
        // fits flat: inner 3-selector chain stays on one line
        log.atError()
            .addKeyValue(
                "tenant.key",
                enrollment.provider()
                        .key()
                        .memberEntityID()
            )
            .addKeyValue("tenant.name", enrollment.label())
            .log("Tenant scheduler refused connection");
        // over-width: inner 3-selector chain fans because flat form overflows
        log.atError()
            .addKeyValue(
                "tenant.key",
                enrollment.providerService()
                        .organizationalUnitKeyForTenantRegistrySystemAccess()
                        .memberEntityIdentifierForComplianceAudit()
            )
            .log("Tenant scheduler refused connection");
    }
}
