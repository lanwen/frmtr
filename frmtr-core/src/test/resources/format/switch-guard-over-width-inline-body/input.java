public class SettlementDispatcher {

  static class RegulatoryArchiveStage {

    void dispatch(SettlementSignal signal) {
      switch (signal) {
        case PaymentSettlementEvent settlementEvent when settlementEvent.reconciledAmount() > threshold -> escalateSettlementReconciliationCompletionToRegulatoryComplianceArchiveAndDownstreamComplianceAuditImmediately();
        default -> ignore();
      }
    }

    void ignore() {}
  }

  record PaymentSettlementEvent(long reconciledAmount) {}

  record SettlementSignal() {}
}
