public class SettlementRouter {

  static class ReconciliationStage {

    void route(SettlementSignal signal) {
      switch (signal) {
        case PaymentSettlementEvent settlementEvent when settlementEvent.reconciledAmount() > limitAmt -> handle();
        case ReconciliationBatch(PaymentSettlementEvent settlementEvent) when settlementEvent.reconciledAmount() > outstandingLimitAmount -> reconcile();
        default -> ignore();
      }
    }

    void handle() {}

    void reconcile() {}

    void ignore() {}
  }

  record PaymentSettlementEvent(long reconciledAmount) {}

  record ReconciliationBatch(PaymentSettlementEvent event) {}

  record SettlementSignal() {}
}
