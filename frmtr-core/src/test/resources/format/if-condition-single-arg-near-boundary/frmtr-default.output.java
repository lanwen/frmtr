package demo;

class ConnectionPoolCoordinator {

    private final ConnectionPool connectionPoolCoordinator;

    void acquireIfSlotAvailable(ConnectionRequest pendingConnectionRequest) {
        if (connectionPoolCoordinator.hasFreeSlotAvailableForPendingConnectionRetryAttempt(pendingConnectionRequest)) {
            acquireConnection(pendingConnectionRequest);
        }
    }

    void acquireIfSlotAvailableNow(ConnectionRequest pendingConnectionRequest) {
        if (connectionPoolCoordinator
                .hasFreeSlotAvailableForPendingConnectionRetryAttemptNow(pendingConnectionRequest)) {
            acquireConnection(pendingConnectionRequest);
        }
    }
}
