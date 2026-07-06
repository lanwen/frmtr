class BinaryChainWrapConverge {

    boolean binaryFlatSource(RemoteRef rx, LocalRef item) {
        boolean found;
        if (item.ready()) {
            if (item.tracked()) {
                if (item.fastLookup()) {
                    found = rx.fileLabelName().equals(item.absoluteItemPath()) || rx.fileLabelName().equals(
                        item.shortLabelName()
                    );
                } else {
                    found = rx.fileLabelName().equals(item.shortLabelName());
                }
            }
        }
        return found;
    }

    boolean binaryBrokenArgumentSource(RemoteRef rx, LocalRef item) {
        boolean found;
        if (item.ready()) {
            if (item.tracked()) {
                if (item.fastLookup()) {
                    found = rx.fileLabelName().equals(item.absoluteItemPath()) || rx.fileLabelName().equals(
                        item.shortLabelName()
                    );
                } else {
                    found = rx.fileLabelName().equals(item.shortLabelName());
                }
            }
        }
        return found;
    }

    void chainFlatSource(SessionStore sessionStore) {
        sessionProvider = sessionKey -> SlidingWindowSessionCache.builder()
                .key(sessionKey)
                .maxEntries(10)
                .sessionStore(sessionStore)
                .build();
    }

    void chainBrokenSource(SessionStore sessionStore) {
        sessionProvider = sessionKey -> SlidingWindowSessionCache.builder()
                .key(sessionKey)
                .maxEntries(10)
                .sessionStore(sessionStore)
                .build();
    }
}
