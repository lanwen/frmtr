class BinaryMethodCallOperandSample {
    String encodedName(String serial) {
        return (
            "group-" +
            EncoderFactory.getUrlEncoder()
                .withoutPadding()
                .encodeToString(serial.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        );
    }

    boolean any(Items<Item> items, String declarationPrefix, Options options) {
        return items
            .stream()
            .anyMatch(item -> currentIndentedWidth.applyAsInt(
                declarationPrefix + item.getNameAsString()
            ) > options.lineWidth());
    }
}
