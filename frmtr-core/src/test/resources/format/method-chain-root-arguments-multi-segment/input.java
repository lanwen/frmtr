class MethodChainRootArgumentsMultiSegmentSample {
    Publisher<Message.Response> dispatchWithRecovery(Sink sink, Message.Command request) {
        return RequestGateway.<Message.Command, Message.Response>ask(sink, replyTo -> new Message.Command.Open(replyTo, request), timeouts.next())
            .onErrorMap(TimeoutException.class, err -> new RoutingTimeoutFailure(err, request.owner(), request.target()))
            .doOnNext(response -> metrics.recordLatency(response.elapsed()));
    }
}
