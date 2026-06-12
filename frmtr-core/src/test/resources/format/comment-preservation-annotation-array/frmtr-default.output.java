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
}

@interface ParameterizedTest {}

@interface CsvSource {
    String[] value();
}
