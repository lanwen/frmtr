class VariableChainInitializerSample {
    Disposable open(Source source) {
        var request = Flow.defer(source::open)
            .retryWhen(
                RetryPolicy.fixedDelay(Long.MAX_VALUE, OPEN_RETRY_DELAY)
                    .filter(error -> !isOpenUnsupported(error))
                    .doBeforeRetry(signal -> log.warn("Unable to open", signal.failure()))
            );
        return request.subscribe();
    }
}
