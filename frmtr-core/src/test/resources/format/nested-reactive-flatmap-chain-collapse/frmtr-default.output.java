package dev.example.reactive;

import reactor.core.publisher.Mono;

class HandleRegistry {

    private Catalog catalog;

    private Index index;

    private Store store;

    private AuditTrail resolutionAuditTrailService;

    Mono<Entry> resolveHandle(String requestedHandle) {
        return catalog.loadItems(requestedHandle)
                .flatMap(loadedItem -> index.filterMatching(loadedItem)
                        .annotateSource()
                        .dedupeByChecksum()
                        .collectList()
                        .flatMap(matchedList -> store.selectPrimaryCandidate(matchedList)
                                .validateChecksumAgainstSource()
                                .normalizeDisplayName()
                                .next()
                                .flatMap(primaryCandidate -> resolutionAuditTrailService.recordResolutionOutcomeEvent(primaryCandidate)
                                        .withResolvedTimestamp()
                                        .withRequestingActor()
                                        .persistReceipt()
                                        .flatMap(persistedReceipt -> Mono.just(
                                            new ResolvedRegistryEntry(primaryCandidate, persistedReceipt)
                                        ))
                                )
                        )
                );
    }
}
