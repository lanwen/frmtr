class AclPublisherSample {
    void publishMatchingBindings(AclBindingFilter filter, AclStore store, MetadataImage image) {
        var resourcePatternsByPrincipalAndPatternType = store.bindingsMatchingFilter(filter).collectByPattern(binding -> {
            return image.acls().resolvePattern(binding.pattern(), binding.entry().principal());
        });
        publish(resourcePatternsByPrincipalAndPatternType);
    }

    void publishShortNamedBindings(AclBindingFilter filter, AclStore store, MetadataImage image) {
        var patterns = store.bindingsMatchingFilter(filter).collectByPattern(binding -> {
            return image.acls().resolvePattern(binding.pattern(), binding.entry().principal());
        });
        publish(patterns);
    }

    void publishFromPlainReceiver(AclBindingFilter filter, AclStore store, MetadataImage image) {
        var resourcePatternsByPrincipalAndPatternType = bindingRegistryForCurrentMetadataImage.collectByPattern(binding -> {
            return image.acls().resolvePattern(binding.pattern(), binding.entry().principal());
        });
        publish(resourcePatternsByPrincipalAndPatternType);
    }
}
