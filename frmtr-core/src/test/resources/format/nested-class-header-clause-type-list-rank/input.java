class CatalogWarehouse {

    interface AuditLedgerWithExtendedComplianceAndRetentionMetadata extends AuditableLedgerEntryOne, AuditableLedgerEntryTwo permits ConcreteAuditLedgerImplementation, LegacyAuditLedgerImplementation {
        void audit();
    }

    interface WideAuditLedger extends AuditableLedgerEntryOne, AuditableLedgerEntryTwo, AuditableLedgerEntryThree, AuditableLedgerEntryFour, AuditableLedgerEntryFive permits ConcreteLedger {
        void audit();
    }
}
