class ChainHostedBuilderArgumentWidth {

    void createConfiguredNamespace(KubernetesClient client, String namespace) {
        client.namespaces().create(
            new NamespaceBuilder()
                    .withNewMetadata()
                    .withName(namespace)
                    .endMetadata()
                    .build()
        );
    }

    void registerCreatableTopic(CreateTopicsRequestData request, String topicName, short replicationFactor) {
        request.topics()
                .add(
                    new CreatableTopic()
                            .setName(topicName)
                            .setNumPartitions(12)
                            .setReplicationFactor(replicationFactor)
                );
    }
}
