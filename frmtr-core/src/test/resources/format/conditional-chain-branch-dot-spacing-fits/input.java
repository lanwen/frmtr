class ConditionalChainDotSpacingSample {
    Result map(Source source) {
        return new Result(
            source.id(),
            source.count() > 0
                ? source
                      .entries()
                      .stream()
                      .map(entry -> new Target(entry.value()))
                      .toList()
                : List.of()
        );
    }
}
