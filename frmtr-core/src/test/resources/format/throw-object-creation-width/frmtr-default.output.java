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
}
