class QualifiedClassLiteralSample {

    void verify(Context context) {
        assertThat(context).hasSingleBean(
            FixtureAutoConfigurationForClassLiteralRegression.ProcessingConfigurationWithLongFixtureSegment
                .RecordProcessorsContainer.class
        );
    }

    void verifyGenericSegment(Context context) {
        assertThat(context).hasSingleBean(
            FixtureAutoConfigurationForClassLiteralRegression
                .ProcessingConfigurationWithLongFixtureSegment<RouteProjectionSnapshot, SegmentMetadata>
                .RecordProcessorsContainer.class
        );
    }

    void verifyFlat(Context context) {
        assertThat(context).hasSingleBean(FixtureAutoConfiguration.FlatContainer.class);
    }
}
