package demo;

class ConfigurationRegistrar {
    private long processedEntryCount;

    void registerEach(java.util.List<String> configurationEntries) {
        for (String configurationEntry : configurationEntries)
            registerConfigurationEntryWithTheGlobalConfigurationRegistry(configurationEntry, configurationEntries);
    }

    void registerIndexed(String[] configurationEntries) {
        for (int entryIndex = 0; entryIndex < configurationEntries.length; entryIndex++)
            registerConfigurationEntryWithTheGlobalConfigurationRegistry(configurationEntries[entryIndex], entryIndex);
    }

    void drainQueue(java.util.Queue<String> pendingConfigurationEntries) {
        while (!pendingConfigurationEntries.isEmpty())
            registerConfigurationEntryWithTheGlobalConfigurationRegistry(pendingConfigurationEntries.poll());
    }

    void countShort(int[] entryLengths) {
        for (int index = 0; index < entryLengths.length; index++) processedEntryCount += entryLengths[index];
    }
}
