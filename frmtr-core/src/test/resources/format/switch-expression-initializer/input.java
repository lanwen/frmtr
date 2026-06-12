class SwitchExpressionInitializer {
    void route(Throwable problem) {
        var result = switch (problem) {
            case MissingRemoteSubjectException _ -> Signal.NOT_FOUND;
            case UnsupportedRemoteModeException e -> Signal.UNIMPLEMENTED.withDescription(e.getMessage());
            case DeferredHandshakeAuthorizationFailureException _ -> Signal.INTERNAL.withDescription("token_exchange");
            default -> Signal.UNKNOWN.withDescription(describe(problem));
        };
        publish(result);
    }
}


 class YieldStatementCases {
    enum Day {
        MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY,
	SATURDAY, SUNDAY
    }

    public int calculate(Day day) {
        switch (day) {
	    case SATURDAY, SUNDAY -> day.ordinal();
            default -> {
                int nameLength = day.toString().length();
                yield nameLength * nameLength;
            }
        };

        return;
    }

    public int calculate(Day day) {
        return switch (day) {
	    case SATURDAY, SUNDAY -> day.ordinal();
            default -> {
                int nameLength = day.toString().length();
                yield nameLength * nameLength;
            }
        };
    }

    void shouldKeepYieldStaticImportCalls() {
        Thread.yield ();
        yield();
        yield(signal);
    }
}
