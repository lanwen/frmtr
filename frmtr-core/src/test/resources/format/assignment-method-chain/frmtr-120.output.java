class AssignmentMethodChainSample {

    void tune(Config config) {
        config = config
            .withMinimum(12)
            .withDesired(12)
            .withMaximum(50)
            .withMode(SelectionMode.LEASED_PLUS_PENDING);
    }

    void enrich(LogSink sink, Report report) {
        if (report.enabled()) {
            sink = sink
                .withValue("sample.name", report.name())
                .withValue(
                    "sample.duration",
                    DurationBridge.from(report.veryLongMeasuredDurationForSelectedEntry()).serialize()
                );
        }
    }
}
