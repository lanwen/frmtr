package sample;

final class NestedGenericTypeBreaks {
    void connect(Source source) {
        final OperationWithVeryLongFormatterFixtureName<
            FirstSubject,
            SubjectCollection<FirstSubject>,
            SubjectResource<FirstSubject>
        > operation = source.operation();
        sink(operation);
    }

    private static OperationWithVeryLongFormatterFixtureName<
        FirstSubject,
        SubjectCollection<FirstSubject>,
        SubjectResource<FirstSubject>
    > resource() {
        return sourceOperation();
    }
}
