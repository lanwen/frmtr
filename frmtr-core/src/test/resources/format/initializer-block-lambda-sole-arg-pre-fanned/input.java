class AclPublisherSample {
    void collectBindings(AclBindingFilter filter, AclStore store) {
        var bindings = store
            .bindingsMatchingFilter(filter)
            .collectByPattern(binding -> {
                process(binding);
            });
        publish(bindings);
    }

    void collectLongNamedBindings(AclBindingFilter filter, AclStore store) {
        var resourcePatternsByPrincipalAndPatternType = store
            .bindingsMatchingFilter(filter)
            .collectByPattern(binding -> {
                process(binding);
            });
        publish(resourcePatternsByPrincipalAndPatternType);
    }
}
