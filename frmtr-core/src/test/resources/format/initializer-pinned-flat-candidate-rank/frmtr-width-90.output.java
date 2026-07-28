class SurveyStationImport {

    private final Coordinates origin = new Coordinates(
        latitudeDegrees,
        longitudeDegrees,
        elevationMeters
    );

    private final Coordinates surveyed = new Coordinates(
        latitudeDegreesOfTheSurveyedStation,
        longitudeDegreesOfTheSurveyedStation,
        elevationMetersAboveSeaLevel
    );

    private final BoundingBox area = new BoundingBox(
        origin,
        surveyed.translatedBy(gridOffsetEast, gridOffsetNorth)
    );

    void rankedInitializers() {
        var registrations = Collections.newSetFromMap(
            new WeakHashMap<ParticipantRegistration, Boolean>(4)
        );
        var subscription = eventRouter.resolveDispatcher(dispatcherKey)
                .subscribeToLifecycleEvents(lifecycleEvent -> {
                    lifecycleHandler.accept(lifecycleEvent);
                });
        var summary = telemetryCollector.snapshot(collectionWindow)
                .summarizeByPercentile(percentileThresholds);
    }
}
