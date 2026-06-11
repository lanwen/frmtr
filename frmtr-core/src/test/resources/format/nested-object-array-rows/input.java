package sample;

final class NestedObjectArrayRows {

    static Object[] matrix() {
        return new Object[][] {
            { SignalStatus.NOT_FOUND.withDescription("missing_record"), ResponseStatus.GONE },
            { SignalStatus.RESOURCE_BUSY.withDescription("capacity_exhausted"), ResponseStatus.SERVICE_UNAVAILABLE },
            { SignalStatus.UNKNOWN, ResponseStatus.SERVICE_UNAVAILABLE },
        };
    }

    static Object[] wideMatrix() {
        return new Object[][] {
            { SignalStatus.NOT_FOUND.withDescription("missing_record_with_a_long_reason_code"), ResponseStatus.SERVICE_UNAVAILABLE },
            { SignalStatus.UNKNOWN, ResponseStatus.GONE },
        };
    }
}
