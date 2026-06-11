package sample;

final class SignalAbortConfig {

    private static final StatusCode CLOSED_GATE = StatusCode.valueOf(499);

    @Factory
    @Rank(-2) // before default signal bridge
    Handler stopSignalHandler() {
        return (Context context, Throwable failure) -> {
            if (failure instanceof ClosedSignal) {
                context.response().setStatusCode(CLOSED_GATE);
                return context.response().complete();
            }
            return Flow.error(failure);
        };
    }
}

@interface Factory {}

@interface Rank {
    int value();
}
