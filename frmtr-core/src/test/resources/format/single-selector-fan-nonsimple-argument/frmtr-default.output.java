class RegistryCredentialResolver {

    private AuthBinding resolveAuthBinding(final JsonNode settings, final String sectionName) throws Exception {
        final Map.Entry<String, JsonNode> section = locateBindingSection(settings, sectionName);

        if (section != null && section.getValue() != null && section.getValue().size() > 0) {
            final AuthBinding resolvedBinding = CONFIG_MAPPER.readValue(section.getValue(), AuthBinding.class)
                    .withSourceKey(section.getKey());
            return resolvedBinding;
        }
        return null;
    }
}
