package demo;

class ConnectionRetryScheduler {

    void drainWhileCapacityAvailable(ConnectionRetryQueue retryDispatchQueue, ConnectionPool activeConnectionPool) {
        while (retryDispatchQueue.hasPendingRetryAttemptsForExhaustedConnectionBackoffWindow(activeConnectionPool)) {
            retryDispatchQueue.drainNext();
        }
    }

    void drainWhileCapacityAvailableNow(ConnectionRetryQueue retryDispatchQueue, ConnectionPool activeConnectionPool) {
        while (retryDispatchQueue.hasPendingRetryAttemptsForExhaustedConnectionBackoffWindowNow(activeConnectionPool)) {
            retryDispatchQueue.drainNext();
        }
    }

    void classifyPendingRetryOutcome(ConnectionRetryQueue retryDispatchQueue, ConnectionPool activeConnectionPool) {
        switch (retryDispatchQueue.classifyPendingRetryOutcomeForFullyExhaustedBackoffWindow(activeConnectionPool)) {
            case READY -> retryDispatchQueue.drainNext();
            default -> retryDispatchQueue.parkNext();
        }
    }

    void classifyPendingRetryOutcomeNow(ConnectionRetryQueue retryDispatchQueue, ConnectionPool activeConnectionPool) {
        switch (retryDispatchQueue.classifyPendingRetryOutcomeForFullyExhaustedBackoffWindowNow(activeConnectionPool)) {
            case READY -> retryDispatchQueue.drainNext();
            default -> retryDispatchQueue.parkNext();
        }
    }
}
