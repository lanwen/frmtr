class LeaseClientTest {

    void fansInsteadOfHoistingLeafArgument() {
        if (ready) {
            stub.get(GetRequest.newBuilder().setLeaseId(UUID.randomUUID().toString()).build())
                    .block(Duration.ofSeconds(5));
        }
    }

    void fansInsideNestedGuardClauses() {
        if (ready) {
            if (retryable) {
                stub.get(GetRequest.newBuilder().setLeaseId(UUID.randomUUID().toString()).build())
                        .block(Duration.ofSeconds(5));
            }
        }
    }

    void explodesInsteadOfHoistingLeafArgument() {
        someModeratelyLongDescriptiveMethodNameForAwaitingLeaseReleaseAcrossTheEntireClusterRightNow(
            Duration.ofSeconds(5)
        );
    }

    void hugsSubstantialAcquireArguments(SharePartitionSlot partitionSlot) {
        when(partitionSlot.acquire(
            anyString(),
            any(AcquireMode.class),
            anyInt(),
            anyLong(),
            any(FetchPartitionData.class)
        )).thenReturn(cachedRecords);
    }

    void staysFlatWhenShort() {
        stub.get(GetRequest.newBuilder().build()).block(Duration.ofSeconds(5));
    }
}
