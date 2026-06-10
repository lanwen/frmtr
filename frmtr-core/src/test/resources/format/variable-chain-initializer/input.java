class VariableChainInitializerSample {
    Disposable open(Source source) {
        var request = Flow.defer(source::open)
            .retryWhen(
                RetryPolicy.fixedDelay(Long.MAX_VALUE, OPEN_RETRY_DELAY)
                    .filter(error -> !isOpenUnsupported(error))
                    .doBeforeRetry(signal -> log.warn("Unable to open", signal.failure()))
            );
        Flux<Item> logging = Flux.merge(
            source.distinctUntilChanged(SampleService::signature), // log on change
            source.sample(LOG_PERIOD) // or every X seconds
        )
            .doOnNext(items -> {
                log.info("Streaming items", items);
            })
            .thenMany(Flux.empty()); // emit nothing downstream
        return request.subscribe();
    }
}
