class MethodChainBrokenRootUniformSelectorsSample {
    List<NormalizedEntry> normalizeAll(Entry primaryEntry, Entry secondaryEntry, Entry tertiaryEntry, Entry quaternaryEntry) {
        return RegistryPipelineFactory.buildFromEntries(primaryEntry, secondaryEntry, tertiaryEntry, quaternaryEntry)
            .map(entry -> {
                return entry.normalized();
            })
            .filter(Objects::nonNull)
            .collect(Collectors.toList()); // testing
    }
}
