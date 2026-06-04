class T {
    // Fix for https://github.com/jhipster/prettier-java/issues/453
    SomeClass.@Valid SomeInnerClass someInnerClass = someClass.getInteractions().get(0);

    // Fix for https://github.com/jhipster/prettier-java/issues/444
    void process(Map.@NonNull Entry<String, SkeletonConfiguration> entry, @NonNull Map<String, Object> context) {}
}

// Fix for https://github.com/jhipster/prettier-java/issues/607
class Currency {
    Currency() {}

    Currency(Currency other) {}

    Currency(Currency other) {}

    Currency(String aaaaaaaaaa, String bbbbbbbbbb) {}

    String getCode() {}

    int compareTo(Currency other) {}

    int compareTo(Currency other) {}

    int compareTo(String aaaaaaaaaa, String bbbbbbbbbb) {}

    class Inner {
        Inner() {}

        String getCode() {}
    }
}
