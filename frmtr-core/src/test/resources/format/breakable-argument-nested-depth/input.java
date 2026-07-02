class BillingReport {

    void summarizeAtTopLevel() {
        appendLineItem(baseMonthlyPlatformSubscriptionCharges + accumulatedMeteredComputeOverage + prioritySupportPlanSurcharge, currentBillingCycleReference);
    }

    class RegionalLedger {
        class QuarterlyRollup {
            void summarizeDeeplyNested() {
                appendLineItem(baseMonthlyPlatformSubscriptionCharges + accumulatedMeteredComputeOverage + prioritySupportPlanSurcharge, currentBillingCycleReference);
            }
        }
    }
}
