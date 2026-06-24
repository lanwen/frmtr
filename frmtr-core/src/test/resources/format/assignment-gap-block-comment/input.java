class TokenScanner {

    private String cachedName;

    String resolveName(char[] tokenBuffer, int tokenLength) {
        String resolvedName = /* fast path */ internName(tokenBuffer, 1, tokenLength);
        return resolvedName;
    }

    void rememberName(char[] tokenBuffer, int tokenLength) {
        cachedName = /* fast path */ internName(tokenBuffer, 1, tokenLength);
    }

    int countFrom(int seed) {
        int total = /* seeded */ seed;
        total = /* reset */ seed;
        return total;
    }
}
