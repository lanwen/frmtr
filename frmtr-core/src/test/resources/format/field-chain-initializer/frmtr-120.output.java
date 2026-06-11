class FieldChainInitializerSample {

    private static final SampleAdapter SAMPLE_ADAPTER = new SampleAdapter()
        .activate(SampleOption.PRIMARY_EXPANDED_CASE)
        .activate(SampleOption.SECONDARY_EXPANDED_CASE);

    private final AsyncBox<RemoteRecord.LeaseSummary, MeterDecision.CanProceedResponse> decisionCache =
        CacheFactory.newBuilder()
            .maximumSize(10_000)
            .expireAfterWrite(Duration.ofMinutes(1))
            .buildAsync();
}
