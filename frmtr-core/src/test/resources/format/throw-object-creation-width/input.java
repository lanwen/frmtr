class ThrowObjectCreationWidthSample {
    void waitForPort(int mappedPort) {
        try {
            poll();
        } catch (InterruptedException e) {
            throw new ContainerLaunchExceptionForMappedPortNow(
                "Interrupted while waiting for mapped port " + mappedPort,
                e
            );
        }
    }

    void rejectRecoveredRoute(RouteNode routeNode) {
        // TODO: Publish rejected route member through diagnostics once recovery reporting exists.
        throw new RouteRecoveryException(
            "Unsupported route parse-error recovery reached member formatter: " + routeNode.getClass().getSimpleName()
        );
    }

    void failWhenRoutesAreMissing(Set<String> missingRoutes) {
        if (!missingRoutes.isEmpty()) {
            throw new IllegalStateException(
                "Routes named " +
                missingRoutes +
                " are not present, but checks were registered " +
                "for them. Please verify that the scenario " +
                "contains those route declarations."
            );
        }
    }
}
