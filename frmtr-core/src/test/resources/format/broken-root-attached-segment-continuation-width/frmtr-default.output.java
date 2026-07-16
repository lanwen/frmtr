class BrokenRootAttachedSegmentContinuationWidth {

    void stubAcquire(SharePartition sharePartition) {
        when(
            sharePartition.acquire(
                anyString(),
                any(ShareAcquireMode.class),
                anyInt(),
                anyLong(),
                any(FetchPartitionData.class)
            )
        ).thenReturn(ShareAcquiredRecords.empty());
    }
}
