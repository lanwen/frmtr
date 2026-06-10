class MethodCallBinaryArgumentSample {

    void configure(OpaqueContainer<?> container) {
        container
            .withCommand("run")
            .withEnv(
                "JAVA_TOOL_OPTIONS",
                "-Dalpha.beta.gamma.trustStore=/var/lib/example/client-truststore.p12 " +
                    "-Dalpha.beta.gamma.trustStorePassword=changeit " +
                    "-Dalpha.beta.gamma.trustStoreType=PKCS12"
            );
    }
}
