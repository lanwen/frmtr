class AnnotationArrayCommentSample {
    @ParameterizedTest
    @CsvSource(
        {
            // first branch
            "STOP, 2",
            // second branch
            "FAIL, 1",
            "SELF, 0",
        }
    )
    void checks(String command, int count) {}

    @ClusterTest(serverProperties = {
        // Must be greater than 1MB per cleaner thread, so 2M+2 leaves room for two threads.
        @ClusterConfigProperty(key = "log.cleaner.dedupe.buffer.size", value = "2097154"),
    })
    void keepsPairCommentWhenTheFlatArrayWouldFit() {}
}

@interface ParameterizedTest {}

@interface CsvSource {
    String[] value();
}

@interface ClusterConfigProperty {
    String key();

    String value();
}

@interface ClusterTest {
    ClusterConfigProperty[] serverProperties();
}
