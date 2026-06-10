class MultilineIfConditionSample {
    boolean isOpenUnsupported(Throwable error) {
        var cause = error;
        while (cause != null) {
            if (
                cause instanceof StatusReply.ErrorMessage message &&
                StatusReplies.OPEN_UNAVAILABLE.equals(message.toString())
            ) {
                return true;
            }
            if (
                cause instanceof UnsupportedOperationException unsupported &&
                "open".equals(unsupported.getMessage())
            ) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    void settle(CaseState state, CaseEvent event, Interactions interactions) {
        if (ResultLedgerTransitionBindingsWithVerboseFixturePrefix.cleanupCompleteAfter(state.snapshot(), event.key(), event.binding())) {
            ResultLedgerInteractions.PendingTransitionWithExtremelyLongFixtureName transitionToComplete = interactions.drainPendingTransitions();
            complete(transitionToComplete);
        }
    }
}
