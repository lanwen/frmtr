class ConnectionHandlerRegistry {

    void register() {
        var handlerForIncomingConnectionRequest = clientId ->
            connectionRegistry.lookupHandlerByClientIdentifier(clientId);
    }
}
