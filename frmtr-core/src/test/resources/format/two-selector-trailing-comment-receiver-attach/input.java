package dev.example;

class HeartbeatRegistry {

    void track(String sessionToken, ConnectionState connectionState) {
        activeSessionsByToken
            .computeIfAbsent(sessionToken)
            .recordHeartbeat(connectionState); // refresh liveness on every ping
    }
}
