class MethodArgumentCommentSample {
    void configure(Source source) {
        var selectedValue = Defaults.withVeryLongFallbackSelectionName(
            source.primaryValue(),
            // keep fallback reason
            Defaults.FALLBACK_VALUE
        );
    }

    void saveRows(RowRepository repository, ParcelRoute route, ParcelOwner owner) {
        repository.saveAll(
            Entries.of(
                // First projected row
                parcelRecord(
                    "parcel-window-alpha-control-segment-0001",
                    route.primaryShardKey(),
                    owner.activePrincipalKey(),
                    timestampForStep(1),
                    timestampForStep(2),
                    TimeSlice.ofSeconds(100),
                    1
                ),
                // Second projected row
                parcelRecord(
                    "parcel-window-alpha-control-segment-0002",
                    route.primaryShardKey(),
                    owner.activePrincipalKey(),
                    timestampForStep(3),
                    timestampForStep(4).plusSeconds(50),
                    TimeSlice.ofSeconds(200),
                    2
                ),
                // Final projected row
                parcelRecord(
                    "parcel-window-alpha-control-segment-0003",
                    route.primaryShardKey(),
                    owner.activePrincipalKey(),
                    timestampForStep(5),
                    null,
                    TimeSlice.ofSeconds(300),
                    3
                )
            )
        );
    }

    void seedStages(StageRepository repository, StageCache cache, ParcelRoute route, ParcelOwner owner) {
        StageJoin.when(
            // Persist source state before projection checks
            repository.persist(
                parcelRecord(
                    ACTIVE_STAGE_KEY,
                    route.primaryShardKey(),
                    owner.activePrincipalKey(),
                    timestampForStep(20),
                    null,
                    TimeSlice.ofSeconds(120),
                    5
                )
            ),
            // Seed transient projection state
            cache.deleteByKey(ACTIVE_STAGE_KEY)
                .then(cache.recordValue(ACTIVE_STAGE_KEY, "alpha:latest"))
                .then(cache.recordValue(ACTIVE_STAGE_KEY, "beta:stable"))
        ).await(MaxWait.seconds(10));
    }
}

class Source {
    String primaryValue() {
        return "primary";
    }
}

class Defaults {
    static final String FALLBACK_VALUE = "fallback";

    static String withVeryLongFallbackSelectionName(String primary, String fallback) {
        return primary == null ? fallback : primary;
    }
}
