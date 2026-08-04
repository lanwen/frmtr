class SingleSelectorArgumentFanSample {
    void singleSelectorFansInArgument() {
        assertNull(service.resolve(registryRequest(SnapshotRegistryService.SNAPSHOT_MISMATCH_REGION_KEY)).await(Duration.ofSeconds(30)));
    }

    void singleSelectorFitsAttached() {
        assertNull(service.resolve(config.snapshotRequest()).await(Duration.ofSeconds(30)));
    }
}
