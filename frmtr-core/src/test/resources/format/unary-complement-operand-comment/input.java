class FreshnessGuard {

    boolean stale(long lastSeenMillis, long thresholdMillis) {
        boolean expired = !(lastSeenMillis /* epoch */ > thresholdMillis);
        return expired;
    }
}
