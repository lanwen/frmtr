public class ReconciliationReport {

    Outcome classify(ReconciliationEntry entry) {
        return switch (entry) {
            case ReconciliationEntry(
                LedgerPosting unreconciledSourcePostings,
                LedgerPosting unreconciledTargetPostings
            ) -> {
                yield Outcome.balanced(unreconciledSourcePostings, unreconciledTargetPostings);
            }
            default -> Outcome.unbalanced();
        };
    }

    record ReconciliationEntry(LedgerPosting source, LedgerPosting target) {}

    record LedgerPosting(String reference) {}

    record Outcome(String label) {
        static Outcome balanced(LedgerPosting source, LedgerPosting target) {
            return new Outcome("balanced");
        }

        static Outcome unbalanced() {
            return new Outcome("unbalanced");
        }
    }
}
