public class ReconciliationLedger {

  static class SettlementReconciler {

    ReconciliationOutcome reconcile(ReconciliationRecord record) {
      return switch (record) {
        case OuterReconciliationRecord(InnerLedgerPostingRecord(String firstLongReconciliationFieldName, String secondReconciliationFieldName), Totals totalsSummaryValue) -> {
          yield ReconciliationOutcome.matched(totalsSummaryValue);
        }
        default -> ReconciliationOutcome.unmatched();
      };
    }
  }

  record OuterReconciliationRecord(InnerLedgerPostingRecord posting, Totals totals) {}

  record InnerLedgerPostingRecord(String source, String target) {}

  record Totals(String label) {}

  record ReconciliationOutcome(String label) {
    static ReconciliationOutcome matched(Totals totals) {
      return new ReconciliationOutcome("matched");
    }

    static ReconciliationOutcome unmatched() {
      return new ReconciliationOutcome("unmatched");
    }
  }
}
