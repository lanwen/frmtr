class ServiceDescriptorTest {

    void configuresBinding() {
        ServiceDescriptor descriptor = ServiceDescriptor.newBuilder()
                .setName("inventory")
                .setBinding(ConnectionPolicy.newBuilder().setEndpoint("grpc://broker:9090").setProtocol(Protocol.GRPC))
                .build();
    }

    void configuresOverflowingCluster() {
        RouteConfig config = RouteConfig.newBuilder()
                .setName("primary")
                .setCluster(UpstreamCluster.newBuilder().setHost("payments-gateway.internal.svc").setTimeoutMillis(2500))
                .build();
    }

    void logsBindingContext(Logger log) {
        log.atError()
            .addKeyValue("binding", ConnectionPolicy.newBuilder().setEndpoint("grpc://broker").setProtocol(GRPC))
            .log("refused");
    }
}
