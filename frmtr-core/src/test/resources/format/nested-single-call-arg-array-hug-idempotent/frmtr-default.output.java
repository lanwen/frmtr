class ReplicationControlManager {

    void removeBroker(int brokerToRemove, int brokerWithUncleanShutdown) {
        for (PartitionRegistration partition : partitions) {
            builder.setTargetIsr(Replicas.toList(
                Replicas.copyWithout(
                    partition.isr,
                    new int[] {
                        brokerToRemove,
                        brokerWithUncleanShutdown,
                    }
                )
            ));
        }
    }
}
