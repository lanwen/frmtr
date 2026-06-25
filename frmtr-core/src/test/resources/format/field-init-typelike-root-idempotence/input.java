class DisruptorReference {
    private final Set<SomeReasonablyLongMarshallerProviderTypeNameHere> seenProviders = Collections
        .newSetFromMap(new WeakHashMap<>(4));

    private final Set<SomeReasonablyLongMarshallerProviderTypeNameHere> collapsedProviders = Collections.newSetFromMap(new WeakHashMap<>(4));

    private final Set<SomeReasonablyLongMarshallerProviderTypeNameHere> attachedProviders = Collections.newSetFromMap(
        new WeakHashMap<>(4));

    private final java.util.Set<SomeReasonablyLongMarshallerProviderType> qualifiedRootProviders = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>(4));

    private final java.util.Set<SomeReasonablyLongMarshallerProviderType> qualifiedRootBroken =
        java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>(4));
}
