class SwitchExpressionInitializer {
    void route(Throwable problem) {
        var result = switch (problem) {
            case MissingRemoteSubjectException _ -> Signal.NOT_FOUND;
            case UnsupportedRemoteModeException e -> Signal.UNIMPLEMENTED.withDescription(e.getMessage());
            case DeferredHandshakeAuthorizationFailureException _ -> Signal.INTERNAL.withDescription("token_exchange");
            default -> Signal.UNKNOWN.withDescription(describe(problem));
        };
        publish(result);
    }
}
