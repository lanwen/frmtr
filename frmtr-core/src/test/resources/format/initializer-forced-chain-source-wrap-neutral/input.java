package sample;

final class InternalTopicNaming {

    private void addInternalResourceName(final StoreFactory windowStore) {
        final InternalResourcesNaming.Builder thisInternalResourcesNaming = InternalResourcesNaming.builder().withStateStore(
            windowStore.storeName()
        );
        if (windowStore.loggingEnabled()) {
            thisInternalResourcesNaming.withChangelogTopic(windowStore.storeName() + "-changelog");
        }
        builder.internalTopologyBuilder().addImplicitInternalNames(thisInternalResourcesNaming.build());
    }
}
