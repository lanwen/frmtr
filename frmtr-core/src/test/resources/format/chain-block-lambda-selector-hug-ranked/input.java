class AclChangePublisher {
    void applyAclDelta(MetadataDelta delta, MetadataImage newImage, LoaderManifest manifest) {
        Optional.ofNullable(delta.aclsDelta()).ifPresent(aclsDelta -> {
            // Snapshot loads replace every binding at once, so order does not matter here.
            ClusterMetadataAuthorizer clusterMetadataAuthorizer = (ClusterMetadataAuthorizer) resolvedAuthorizer.get();
            clusterMetadataAuthorizer.loadSnapshot(newImage.acls().acls());
        });
    }

    void applyAclDeltaWithoutNotes(MetadataDelta delta, MetadataImage newImage) {
        Optional.ofNullable(delta.aclsDelta()).ifPresent(aclsDelta -> {
            authorizer.loadSnapshot(newImage.acls().acls());
        });
    }

    void registerBindingListener(AuthorizerRegistry authorizerRegistry, MetadataImage newImage) {
        authorizerRegistry.resolveClusterMetadataAuthorizer(newImage.cluster()).onEveryAclBindingChange(aclBindingChange -> {
            // The listener runs on the loader thread, so it must not block.
            authorizerRegistry.recordBindingChange(aclBindingChange);
        });
    }
}
