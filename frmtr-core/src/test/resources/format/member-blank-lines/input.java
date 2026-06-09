interface MemberSpacing {
    int ALPHA = 1;

    int BETA = 2;
    int GAMMA = 3;
    Item first();

    Item second();

    Item compactOne();
    Item compactTwo();
}

final class FieldSpacing {
    private static final int LIMIT = 10;
    private String one;

    private String two;
    private String three;
    Item compute() {
        return new Item();
    }
    Item describe() {
        return compute();
    }
}

final class Item {}
