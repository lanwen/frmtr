class HandshakeGate {

    void registerHandlers(EventBus eventBus, SessionState sessionState) {
        eventBus.onConnection(connection -> {
            boolean handshakeAccepted =
                !(connection.isAuthenticated() && sessionState.hasActiveLease() && connection.protocolVersionMatches());
            connection.acknowledge(handshakeAccepted);
        });
    }
}
