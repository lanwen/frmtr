class NativeCompatibility {

    int first, second;

    Object map(int value) {
        return switch (value) {
            case 1 -> {
                yield "one";
            }
            default -> "other";
        };
    }
}
