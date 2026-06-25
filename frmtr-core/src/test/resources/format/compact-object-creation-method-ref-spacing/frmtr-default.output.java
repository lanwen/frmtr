class CompactObjectCreationMethodRefSpacing {

    Runnable ref = a.b(c)::d;

    java.util.Iterator iter = chain.map(fn)::iterator;

    Object kv = new KeyValue<>(k, v);

    Object commented = new Builder(
        /* keep */
        raw
    );

    Runnable commentedRef = a.b(/* keep */ c)::d;

    void m() {
        go(() -> new Builder(seed));
    }
}
