class MethodChainSegmentCommentSample {
    @TestMarker // see note about codec 2.18.1

    // https://example.invalid/project/pull/4790

    // can be removed once runtime upgrades codec
    void parsesSyntheticClaims() {
        parse();
    }
}

@interface TestMarker {}
