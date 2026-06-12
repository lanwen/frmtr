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
        Mono<Void> refresh = Mono.defer(() -> {
            snapshot.emit(new BatchStarted(batchId, requestedAt));
            return segmentService.getSegments(snapshot.scope());
        })
            .flatMapMany(Flux::fromIterable)
            .doOnNext(segment -> snapshot.emit(new SegmentObserved(batchId, requestedAt, segment)))
            .doOnComplete(() -> snapshot.emit(new BatchCompleted(batchId, requestedAt)))
            .then()
            .doOnError(error -> snapshot.emit(new BatchFailed(batchId, requestedAt, error)))
            .doFinally(_ -> snapshot.clearInFlight(current.get()))
            .cache();
        return request.subscribe();
    }
}
