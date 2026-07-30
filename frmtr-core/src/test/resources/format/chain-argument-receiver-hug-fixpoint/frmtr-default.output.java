package dev.example.admin;

class ConfigResourceInspector {

    Optional<Set<ConfigResource>> groupConfigResources() {
        return Optional.of(
            adminClient.listConfigResources(Set.of(ConfigResource.Type.GROUP), new ListConfigResourcesOptions())
                    .all()
                    .get()
        );
    }
}
