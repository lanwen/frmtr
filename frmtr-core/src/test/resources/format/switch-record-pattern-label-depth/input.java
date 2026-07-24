public class SettlementRouter {

  SettlementResult settle(ClearingPhase phase, SettlementBatch batch) {
    return switch (phase) {
      case CLEARING -> {
        yield switch (batch) {
          case SettlementBatch(LedgerAccount buyerDebitLedgerAccount, LedgerAccount sellerCreditLedgerAccount) -> {
            yield SettlementResult.cleared(buyerDebitLedgerAccount, sellerCreditLedgerAccount);
          }
          default -> SettlementResult.rejected();
        };
      }
      default -> SettlementResult.rejected();
    };
  }

  record SettlementBatch(LedgerAccount debitAccount, LedgerAccount creditAccount) {}

  record LedgerAccount(String id) {}

  enum ClearingPhase { PENDING, CLEARING, SETTLED }

  record SettlementResult(String label) {
    static SettlementResult cleared(LedgerAccount debit, LedgerAccount credit) {
      return new SettlementResult("cleared");
    }

    static SettlementResult rejected() {
      return new SettlementResult("rejected");
    }
  }
}
