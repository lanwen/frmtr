class MeteredWindowIterator extends BaseIterator {

    MeteredWindowIterator(
        WindowIterator delegateWindowIterator,
        OperationSensor operationSensorMetric,
        IteratorSensor iteratorSensorMetric,
        Time wallClockTimeSource,
        LongAdder openIteratorCounter,
        Set<Iterator> trackedOpenIterators
    ) {
        super(
            delegateWindowIterator,
            operationSensorMetric,
            iteratorSensorMetric,
            null, // should not be used in super-class
            wallClockTimeSource,
            openIteratorCounter,
            trackedOpenIterators);
    }

    MeteredWindowIterator(WindowIterator delegateWindowIterator) {
        this(
            delegateWindowIterator,
            null, // not provided by this entry point
            null, // not provided by this entry point
            Time.SYSTEM,
            new LongAdder(),
            Set.of());
    }

    MeteredWindowIterator(OperationSensor operationSensorMetric, Runnable cleanupCallback) {
        super(
            operationSensorMetric,
            // the cleanup runs once the iterator is closed
            () -> cleanupCallback.run());
    }

    static BaseIterator compact(WindowIterator delegateWindowIterator) {
        return new BaseIterator(delegateWindowIterator, null, Time.SYSTEM);
    }
}

class CompactConstructorChain extends BaseIterator {

    CompactConstructorChain(WindowIterator delegateWindowIterator) {
        super(delegateWindowIterator, null, Time.SYSTEM);
    }
}
