class SettlementProcessor {

    void process(java.util.List<BillingCycle> cycles) {
        for (BillingCycle cycle : cycles) {
            if (cycle.isReadyForSettlement()) {
                var reconciledSettlementResultSnapshots = new ReconciliationResultSnapshotAggregator(
                    firstPosition,
                    secondPosition
                );
            }
        }
    }
}
