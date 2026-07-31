class ResourceCheckoutSection {

    void checkoutSingleResourceNearFlatBoundary() {
        try (ManagedResourceHandle resourceHandle = resourceFactory.openResourceWithConfiguration(
                configuration,
                opts
        )) {
            body();
        }
    }

    void checkoutSingleResourceNearAttachedBoundary() {
        try (
            ManagedResourceHandle resourceHandle =
                resourceFactoryForDistributedConnections.openResourceWithConfiguration(configuration, opts)
        ) {
            body();
        }
    }

    void checkoutTwoResourcesNearFlatBoundary() {
        try (
            ResourceA firstHandle = openFirstResourceWithConfiguration();
            ResourceB secondHandle = openSecondResource()
        ) {
            body();
        }
    }
}
