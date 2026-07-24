class WorkerRegistrationScenarios {

    void registerWorker(ActorRef<RegistryActor.Command> registryRef, PoolSelection selectedPool, PoolConfig poolConfig) {
        registryRef.tell(new RegistryActor.Command.RegisterWorker("pool-b", spawnStubWorker(new WorkerHandle("session-%s".formatted(UUID.randomUUID()), WorkerHandle.resolveEndpoints(targetHost, 12345))), selectedPool, poolConfig));
    }

    void registerWorkerAtDeepIndent(ActorRef<RegistryActor.Command> registryRef, boolean shouldRegister, boolean isPrimaryRegion, boolean hasCapacity, String targetHost) {
        if (shouldRegister) {
            if (isPrimaryRegion) {
                if (hasCapacity) {
                    registryRef.tell(spawnAndRegisterStubWorkerForIntegrationTests(new WorkerHandle("session-%s".formatted(UUID.randomUUID()), WorkerHandle.resolveEndpoints(targetHost, 12345))));
                }
            }
        }
    }
}
