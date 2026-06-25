class ThrowsClauseBreakingParameters {

    void synchronizeRemoteCatalogEntries(
            CatalogConnection primaryConnection, RetryPolicy retryPolicy, ProgressListener progressListener
    ) throws CatalogSyncException, InterruptedException {
        primaryConnection.flush();
    }

    void replicateAccountSnapshot(
            AccountSnapshot accountSnapshot, ReplicationTarget replicationTarget, AuditContext auditContext
    ) throws ReplicationException {
        replicationTarget.accept(accountSnapshot);
    }

    ThrowsClauseBreakingParameters(
            CatalogConnection primaryConnection, RetryPolicy retryPolicy, ProgressListener progressListener
    ) throws CatalogSyncException {
        primaryConnection.flush();
    }
}
