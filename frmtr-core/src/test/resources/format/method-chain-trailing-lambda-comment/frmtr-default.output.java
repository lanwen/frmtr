class MethodChainTrailingLambdaComment {

    void verifySignals(Source source) {
        Probe.create(source)
                .prepare(signal -> {
                    return signal.withState(State.WAITING);
                })
                .thenConsumeWhile(items -> items.stream().allMatch(
                        candidate -> candidate.remoteProviderState() == ExternalProviderState.TRANSITIONING
                )) // keep polling while external state settles
                .assertNext(items -> {
                    assertThat(items)
                            .extracting(Item::state)
                            .containsOnly(State.READY);
                });
    }
}
