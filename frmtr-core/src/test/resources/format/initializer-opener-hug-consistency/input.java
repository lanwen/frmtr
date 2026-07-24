class WorkerPoolInitializers {

    void provision(String targetHost, int port, PoolMeta meta) {
        var worker = new WorkerHandle("session-%s".formatted(UUID.randomUUID()), WorkerHandle.resolveEndpoints("gateway.internal", port));
        var lease = new LeasePlan(meta.owner(), meta.pool(), meta.concurrency(), meta.retention(), LeaseWindow.empty());
    }
}
