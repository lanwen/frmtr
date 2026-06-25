package sample;

final class FieldInitializerBrokenArrayCreation {

    void wireDeferredBindings() {
        ScheduledTask[] pendingScheduledTasks = new ScheduledTask[] { resolvePrimaryTask(), resolveSecondaryTask(), resolveTertiaryTask(), resolveQuaternaryTask(), resolveQuinaryTask() };
        ConfigurationBindingDescriptor[] resolvedConfigurationBindingDescriptorsForDeferredInitializationPhase = new ConfigurationBindingDescriptor[] { resolvePrimaryBinding(), resolveSecondaryBinding(), resolveTertiaryBinding() };
    }
}
