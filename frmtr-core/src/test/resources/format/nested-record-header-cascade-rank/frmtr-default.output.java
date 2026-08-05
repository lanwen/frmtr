class CatalogWarehouse {

    record ShipmentAuditRecord(ShipmentId shipmentId, LedgerEntry entry) implements Auditable {
        void audit() {}
    }

    record ShipmentAuditRecordWithExtendedRetentionMetadata(
        ShipmentId shipmentId,
        LedgerEntry entry
    ) implements Auditable {
        void audit() {}
    }

    record ShipmentAuditRecordWithExtendedRetentionMetadata(
        ShipmentId shipmentId,
        LedgerEntry entry
    ) implements
        VeryLongAuditableInterfaceForComplianceTrackingAndArchival,
        VeryLongSerializableInterfaceForRetentionAndReplay
    {
        void audit() {}
    }

    record SealedManifest<EntryRecord, RetentionPolicy, AuditTrailEntry>(
        EntryRecord entry
    ) {
        void seal() {}
    }
}
