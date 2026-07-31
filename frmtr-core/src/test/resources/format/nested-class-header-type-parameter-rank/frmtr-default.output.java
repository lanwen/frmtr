class CatalogWarehouse {

    static class ArchivePage<EntryRecord, RetentionPolicy> implements Serializable {

        void archive() {}
    }

    static class SealedShelf<EntryRecord, RetentionPolicy, AuditTrailEntry> permits LockedShelf {

        void seal() {}
    }

    static class ShipmentAuditTrail<EntryRecord, RetentionPolicy>
        extends AbstractShipmentTrackerWithExtendedComplianceHistoryAndRetentionMetadata
        implements Auditable, Serializable {

        void audit() {}
    }

    static class ShipmentAuditTrailWithExtendedComplianceMetadataRecordKeeping<
        EntryRecord,
        RetentionPolicy,
        AuditTrailEntry,
        ComplianceFlag
    >
        extends AbstractShipmentTrackerWithExtendedComplianceHistoryAndRetentionMetadata
        implements Auditable, Serializable {

        void audit() {}
    }
}
