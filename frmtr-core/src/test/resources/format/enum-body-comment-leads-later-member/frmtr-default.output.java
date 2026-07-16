public enum Feature {
    UNIT_TEST_VERSION_1,
    UNIT_TEST_VERSION_2;

    public static final Feature[] FEATURES;

    private final String name;

    // The latest production version of the feature, owned and updated by the feature owner
    // in the respective feature definition. The value should not be smaller than the default
    // value calculated from the metadata version.
    public final FeatureVersion latestProduction;

    static {
        FEATURES = Feature.values();
    }
}
