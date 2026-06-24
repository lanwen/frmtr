class ArgListBlockCommentSample {

    void castTrailingBlock(Digest digest, long payload) {
        digest.accumulate(
            (byte) payload /* >> 0 */
        );
    }

    void twoArgsTrailingBlocks(Dispatcher dispatcher, long stamp) {
        dispatcher.deliver(
            stamp, /* createdTimeMs */
            null /* versionMismatch */
        );
    }

    void betweenArgsLeadingBlock(Verifier verifier, Ledger ledger) {
        verifier.confirm(
            true, /* validate batch checksum */
            ledger
        );
    }

    void plainCall(Reporter reporter, long total) {
        reporter.publish(total, null);
    }
}
