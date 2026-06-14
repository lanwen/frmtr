class BinaryMethodCallOperandSample {

    String encodedName(String serial) {
        return (
            "group-"
            + EncoderFactory.getUrlEncoder()
                .withoutPadding()
                .encodeToString(serial.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        );
    }
}
