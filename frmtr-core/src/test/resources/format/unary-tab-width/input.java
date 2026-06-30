class HandshakeGate {
	void registerHandlers(EventBus eventBus, SessionState sessionState) {
		eventBus.onConnection(connection -> {
			boolean handshakeAccepted = !(connection.isAuthenticated() && sessionState.hasActiveLease() && connection.protocolVersionMatches());
			connection.acknowledge(handshakeAccepted);
		});
	}

	void evaluate(Connection connection) {
		boolean rejected = !(connection.isAuthenticated() && connection.protocolVersionMatches());
		connection.acknowledge(rejected);
	}
}
