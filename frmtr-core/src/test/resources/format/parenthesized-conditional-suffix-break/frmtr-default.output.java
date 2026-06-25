class ParenthesizedConditionalSuffixBreak {

    CatalogEntry resolvePrimary(
            boolean someConditionFlag,
            CatalogEntry firstChoiceValue,
            CatalogEntry secondChoiceValue
    ) {
        return (someConditionFlag
            ? firstChoiceValue
            : secondChoiceValue
        ).resolveSelectedCatalogEntryForActiveRegionScopeBinding();
    }

    ProcessingResult resolveNestedBinaryCondition(Token left, Token right, Object other, Outcome a, Outcome b) {
        return (left == right && other != null
            ? a
            : b
        ).processSelectedOutcomeWithRetryAndAuditTrailJournalLogEntryRowBatches();
    }
}
