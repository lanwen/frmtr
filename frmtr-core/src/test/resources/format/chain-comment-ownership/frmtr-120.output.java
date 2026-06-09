class ChainCommentOwnershipSample {

    Set<Token> collectTokens(Collector collector, String label) {
        return SampleStage.ALL.stream()
            .map(stateClass -> {
                return Token.builder("sample_stage", this, self -> {
                    return self.entries.count(entry -> stateClass.isInstance(entry.state()));
                })
                    .tag("legacy.label", label) // old label, migrate to primary.label
                    .tag("primary.label", label)
                    .unit("items")
                    .register(collector);
            })
            .collect(toSet());
    }
}
