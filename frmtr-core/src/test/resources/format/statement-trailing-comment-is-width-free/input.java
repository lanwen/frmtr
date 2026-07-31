class Repro {

    void staleEpochAppendFailsRecoverably() {
        InvalidProducerEpochException exception =
            assertThrows(InvalidProducerEpochException.class, () -> {
                VerificationGuard staleGuard = log.maybeStartTransactionVerification(
                    producerId,
                    0,
                    originalEpoch,
                    true
                ); // TV2 = supportsEpochBump = true
                log.appendAsLeader(
                    staleEpochRecords,
                    0,
                    AppendOrigin.CLIENT,
                    RequestLocal.noCaching(),
                    staleGuard,
                    TransactionVersion.TV_2.featureLevel()
                );
            });
    }
}
