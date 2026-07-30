package demo;

class RequestValidator {
    int totalAcceptedConnectionRequestsAcrossAllActiveRetryWindowsNow;
    int totalAcceptedConnectionRequestsAcrossAllActiveRetryWindowsToday;
    int rejectedConnectionRequestCount;

    boolean isSlotAvailable(ConnectionRequest pendingConnectionRequest) {
        return true;
    }

    void acquireIfAvailableFits(ConnectionRequest pendingConnectionRequest) {
        if (isSlotAvailable(pendingConnectionRequest))
            totalAcceptedConnectionRequestsAcrossAllActiveRetryWindowsNow++;
    }

    void acquireIfAvailableOverflows(ConnectionRequest pendingConnectionRequest) {
        if (isSlotAvailable(pendingConnectionRequest))
            totalAcceptedConnectionRequestsAcrossAllActiveRetryWindowsToday++;
    }

    void acquireOrRejectFits(ConnectionRequest pendingConnectionRequest) {
        if (isSlotAvailable(pendingConnectionRequest))
            totalAcceptedConnectionRequestsAcrossAllActiveRetryWindowsNow++;
        else {
            rejectedConnectionRequestCount++;
        }
    }

    void acquireOrRejectOverflows(ConnectionRequest pendingConnectionRequest) {
        if (isSlotAvailable(pendingConnectionRequest))
            totalAcceptedConnectionRequestsAcrossAllActiveRetryWindowsToday++;
        else {
            rejectedConnectionRequestCount++;
        }
    }
}
