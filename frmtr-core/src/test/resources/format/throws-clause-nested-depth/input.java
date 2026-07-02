public class PaymentGateway {

    void reconcileSettlement(String transactionId) throws SettlementException, LedgerWriteException, ReconcileError {
        throw new SettlementException();
    }

    PaymentGateway(String merchantReferenceIdentifier, String acquirerBatchReference, String region) {
        audit(merchantReferenceIdentifier);
    }

    class SettlementProcessor {

        void reconcileSettlement(String transactionId) throws SettlementException, LedgerWriteException, ReconcileError {
            throw new SettlementException();
        }

        SettlementProcessor(String merchantReferenceIdentifier, String acquirerBatchReference, String settlementRegion) {
            audit(merchantReferenceIdentifier);
        }
    }
}
