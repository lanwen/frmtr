package sample;

final class FieldInitializerArrayCreationEarlyOuts {

    private static final Marker[] preallocatedEmptyMarkerSlotTableForExhaustiveDeferredBootstrapValidation =
        new Marker[] {};

    private static final Marker[] registeredZeroArgumentMarkerInstancesForExhaustiveDeferredValidationSequencing =
        new Marker[] {
            new Marker(),
            new Marker(),
            new Marker(),
            new Marker(),
            new Marker(),
            new Marker(),
            new Marker(),
            new Marker(),
            new Marker(),
        };

    void allocateBuckets() {
        ConfigurationDescriptor[] reservedDescriptorBuckets =
            new ConfigurationDescriptor[computedInitialReservedDescriptorBucketCapacityForBootstrap];
        Registration<String>[] parameterizedHandlerRegistrations = new Registration<String>[] {
            primaryRegistration,
            secondaryRegistration,
        };
        Descriptor[] annotatedDescriptorSequenceWithInlineProvenance = new Descriptor[] {
            primaryDescriptor,
            /* fallback */ secondaryDescriptor,
            tertiaryDescriptor,
        };
    }
}
