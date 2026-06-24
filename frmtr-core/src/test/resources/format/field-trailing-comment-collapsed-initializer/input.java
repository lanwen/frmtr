class IdentifierCodec {

    private final char[] hexDigits =
        "0123456789abcdef".toCharArray(); // NOSONAR

    private final byte[] seedBytes =
        buildSeed(16, 32); // NOSONAR

    private final char[] flatDigits = "0123456789abcdef".toCharArray(); // NOSONAR
}
