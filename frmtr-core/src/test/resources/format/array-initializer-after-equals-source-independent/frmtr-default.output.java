package sample;

final class ArrayInitializerAfterEqualsSourceIndependent {

    // Written on a single source line; overflows and must break by width.
    static final String[] SINGLE_LINE_SOURCE = {
        "connect.primary.example",
        "connect.secondary.example",
        "connect.tertiary.example",
    };

    // Written multiline in source; the canonical broken shape must be identical to the single-line case above.
    static final String[] MULTILINE_SOURCE = {
        "stream.primary.example",
        "stream.secondary.example",
        "stream.tertiary.example",
    };
}
