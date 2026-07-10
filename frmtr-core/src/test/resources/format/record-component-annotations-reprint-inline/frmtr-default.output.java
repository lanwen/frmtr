class AuditRegistry {

    record AuditEntry(
        @Indexed @Column("actor") String performedBy,
        @Indexed @Column("resource") String targetResource
    ) {}
}
