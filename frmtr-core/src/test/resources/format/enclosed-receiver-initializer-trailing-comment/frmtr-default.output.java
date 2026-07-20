class DefaultUuidGenerator {

    private final char[] hostPrefixCharacters = (
        resolveHostName(networkInterfaceCandidate).substring(1)
        + "-"
    ).toCharArray(); // NOSONAR
}
