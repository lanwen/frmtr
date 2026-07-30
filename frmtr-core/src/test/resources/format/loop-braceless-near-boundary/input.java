package demo;

class ConnectionPoolCoordinator {
    private final ConnectionPool connectionPool;
    private final java.util.Queue<ConnectionRequest> pendingConnectionRequests;
    private final RetryBudget connectionRetryBudget;
    private final int maxRetryAttempts;

    void drainReadyConnections() {
        while (!pendingConnectionRequests.isEmpty()) connectionPool.acquireNextSlot(pendingConnectionRequests.poll());
    }

    void drainAvailableConnections() {
        while (!pendingConnectionRequests.isEmpty()) connectionPool.acquireAvailableSlot(pendingConnectionRequests.poll());
    }

    void recordRetryAttempts() {
        for (int attempt = 0; attempt < maxRetryAttempts; attempt++) connectionRetryBudget.recordFailedAttempt(attempt);
    }

    void recordRetryOutcomes() {
        for (int attempt = 0; attempt < maxRetryAttempts; attempt++) connectionRetryBudget.recordAttemptOutcome(attempt);
    }
}
