class ObjectCreationRootChainBreak {

    void multiSegmentConstructorChainBreaksOnePerLine() {
        var topic = new TopicConfigBuilder().setName(topicNameValue).setPartitions(partitionCount).setFactor(replicationFactor).setRetention(retentionWindow);
    }

    void emptyConstructorManyShortCallsBreakOnePerLine() {
        var report = new MetricsReportAssembler().alpha().beta().gamma().delta().epsilon().zeta().eta().theta().iota().kappa();
    }

    void constructorArgumentRootStaysCompactWithCallsOnePerLine() {
        var harness = new ContainerHarness(baseImageReference).withServices(serviceCatalog).andRegisterShutdownHookThatOverflows(closer);
    }

    void singleCallObjectRootKeepsArgumentBreak() {
        var probe = new SignalContainer(imageReferenceForSignalRouter).withSignalEndpoint(controlPlaneEndpointThatOverflows);
    }
}
