class C {

    void chainCallWithLambda() {
        Stream.of(1, 2)
            .map(n -> {
                return n * 2;
            })
            .collect(Collectors.toList()); // testing method
    }
}
