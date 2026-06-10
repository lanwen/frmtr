package sample;

final class NestedGenericTypeBreaks {
    private ProcessorHarnessWithVeryLongFormatterFixtureName<FirstSignal, SignalResult<FirstSignal>, SignalState<FirstSignal>> runner;

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
