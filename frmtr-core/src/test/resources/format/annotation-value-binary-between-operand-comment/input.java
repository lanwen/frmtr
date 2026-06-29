@interface Ann {
    String value();
}

class Min {
    @Ann(value = "a" + // keep me
                 "b")
    int x;
}
