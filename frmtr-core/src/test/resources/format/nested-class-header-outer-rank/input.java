class CatalogWarehouse {

    static class InventoryLedger extends AbstractLedger implements Auditable, Serializable {
        void reconcile() {}

        static class NestedRecordArchiveWithRetention<EntryRecord, RetentionPolicy> extends ArchiveFrame<EntryRecord, RetentionPolicy> permits SealedArchive {
            void archive() {}
        }
    }

    static class ShipmentTrackerWithExtendedAuditTrail extends AbstractShipmentTracker implements Auditable, Serializable {
        void audit() {}
    }

    interface RestockPolicy {
        static class DefaultRestockPolicy implements RestockPolicy, Serializable {
            void restock() {}
        }
    }
}
