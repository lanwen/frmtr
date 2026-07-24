class FetchResponseHandler {

    void applyFetchedData(FetchResponse fetchResponse) {
        while (isRunning()) {
            if (fetchResponse != null) {
                fetchResponse.responseData(topicNames, ApiKeys.FETCH.latestVersion())
                        .forEach((tp, partitionData) -> replicaBuffer.addFetchedData(
                            tp,
                            sourceBroker.id(),
                            partitionData
                        ));
            }
        }
    }
}
