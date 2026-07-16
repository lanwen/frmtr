class NestedSingleCallArgumentOpenerHug {

    void stubAcquire(SharePartition sharePartition) {
        when(sharePartition.acquire(anyString(), any(ShareAcquireMode.class), anyInt(), anyLong(), any(FetchPartitionData.class))).thenReturn(cachedRecords);
    }

    void registerParcel(ParcelRepository repository) {
        repository.persist(parcelRecord(ACTIVE_STAGE_KEY, route.primaryShardKey(), owner.activePrincipalKey(), timestampForStep(20)));
    }
}
