class RecordSizeEstimator {

    int payloadSize;

    java.util.List<Object> recoveredCheckpoints;

    void accumulate() {
        payloadSize += (Integer.SIZE + Long.SIZE)
            * recoveredCheckpoints.size(); // recovered checkpoint entries
        payloadSize += Integer.SIZE; // fixed header field
    }
}
