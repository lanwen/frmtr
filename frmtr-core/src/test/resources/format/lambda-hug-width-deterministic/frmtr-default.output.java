package sample;

final class LambdaHugWidthDeterministic {

    private final BundleResolver bundleResolver;

    private final WindowBundleGateway regionalWindowBundleReadGateway;

    void breaksOverWideHugWhenNestedDeeply(LaunchSchedule launchSchedule) {
        if (launchSchedule.isActive()) {
            for (int attemptIndex = 0; attemptIndex < launchSchedule.attempts(); attemptIndex++) {
                bundleResolver.resolvePreparedWindowBundles(invocation -> regionalWindowBundleReadGateway
                            .findLaunchBundles(invocation.getArgument(0), invocation.getArgument(1))
                );
            }
        }
    }

    void keepsFittingHugWhenNestedDeeply(LaunchSchedule launchSchedule) {
        if (launchSchedule.isActive()) {
            for (int attemptIndex = 0; attemptIndex < launchSchedule.attempts(); attemptIndex++) {
                bundleResolver.resolveWindowBundles(invocation -> regionalWindowBundleReadGateway.findBundles(
                        invocation.getArgument(0),
                        invocation.getArgument(1)
                ));
            }
        }
    }
}
