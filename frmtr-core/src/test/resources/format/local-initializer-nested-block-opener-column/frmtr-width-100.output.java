class SettlementProcessor {

    void process(java.util.List<BillingCycle> cycles) {
        for (BillingCycle cycle : cycles) {
            if (cycle.isReadyForSettlement()) {
                var reconciledIntradaySettlementResultSnapshotForCurrentBillingCycle =
                    billing.reconcile(cyclePositions);
            }
        }
    }
}
