class SingleArgumentWidthDeterministicSample {

    void attachedHugFitsAtItsColumn(EnvironmentResolver environmentResolver) {
        var resolved = environmentResolver.resolveAll(buildEnvironmentConfiguration(
            primaryName,
            secondaryName
        ));
    }

    void attachedMethodCallHugOverflowsAtAssignmentColumn(ConfigurationResolver configurationResolver) {
        java.util.List<String> resolvedRuntimeConfiguration = configurationResolver.resolveAll(
            buildEnvironmentConfiguration(
                primaryEnvironmentName,
                secondaryEnvironmentName
            )
        );
    }

    void attachedObjectCreationHugOverflowsAtAssignmentColumn(ConfigurationFactory configurationFactory) {
        ResolvedConfiguration resolvedRuntimeConfiguration = configurationFactory.createConfiguration(
            new ConfigurationDescriptor(primaryEnvironmentName, secondaryEnvironmentName)
        );
    }
}
