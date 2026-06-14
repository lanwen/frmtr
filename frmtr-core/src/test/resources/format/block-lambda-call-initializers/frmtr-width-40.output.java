class BlockLambdaCallInitializersSample {

    void selectEntries(
            Request request,
            EntryList entries,
            Subject subject
    ) {
        var partitioned =
            entries.partition(entry -> {
                return (
                    request.mode() == SelectionMode.ANY
                    && entry
                            .state()
                            .shouldPrioritize(
                                subject.owner()
                            )
                );
            });
        var selected =
            partitioned.getSelected();
        publishSelection(
            subject,
            request.mode(),
            true,
            selected
        );
    }

    void selectEntryPairs(
            Request request,
            EntryList entries,
            Subject subject
    ) {
        var partitioned =
            entries.partition(
                (_, entry) -> {
                    return (
                        request.mode() == SelectionMode.ANY
                        && entry
                                .state()
                                .shouldPrioritize(
                                    subject.owner()
                                )
                    );
                }
            );
        publishSelection(
            subject,
            request.mode(),
            true,
            partitioned.getSelected()
        );
    }

    void selectEntryTriples(
            Request request,
            EntryList entries,
            Subject subject
    ) {
        var partitioned =
            entries.partition(
                (_, entry, cursor) -> {
                    return (
                        request.mode() == SelectionMode.ANY
                        && entry
                                .state()
                                .shouldPrioritize(
                                    subject.owner(
                                        cursor
                                    )
                                )
                    );
                }
            );
        publishSelection(
            subject,
            request.mode(),
            true,
            partitioned.getSelected()
        );
    }

    void selectEntriesWithLongCallPrefix(
            Request request,
            EntryList entries,
            Subject subject
    ) {
        var partitioned =
            entryCollectionWithExtremelyLongFormatterFixturePrefixBeforeLambdaArgumentFallback
                    .partition(entry -> {
                        return (
                            request.mode() == SelectionMode.ANY
                            && entry
                                    .state()
                                    .shouldPrioritize(
                                        subject.owner()
                                    )
                        );
                    });
        publishSelection(
            subject,
            request.mode(),
            true,
            partitioned.getSelected()
        );
    }
}
