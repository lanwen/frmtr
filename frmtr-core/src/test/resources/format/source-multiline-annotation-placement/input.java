package sample;

class SourceMultilineAnnotationPlacement {
    record RouteSnapshot(@RouteKey(value = "primary-zone-audit-index-that-is-intentionally-long-for-formatter-pass-coverage-and-record-placement") String routeKey, Map<
        @EncodedKeys(
            { "alpha-coordinate-key", "beta-coordinate-key", "gamma-coordinate-key", "delta-coordinate-key" }
        ) String,
        @MergeFormula(
            value = Parts.ACTIVE_REGION + "-with-" + Parts.ARCHIVE_REGION + "-and-" + Parts.FALLBACK_REGION
        ) List<String>
    > labels) {}

    @PlanNames({ "northbound-scheduler-window", "southbound-scheduler-window", "overnight-backfill-window", "manual-repair-window" })
    private String selectedWindow;

    @AuditLabel("ticket-" + Labels.ACTIVE_REGION + "-catalog-" + Labels.ARCHIVE_REGION + "-fallback-" + Labels.REPLAY_REGION)
    void refreshCatalog() {}
}
