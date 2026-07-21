class SignatureRouteConfigTest {

    void configureParentXpath() {
        try {
            signatureEndpoint.getConfiguration()
                    .setParentXpath(
                        XmlSignatureHelper.getXpathFilter(
                            "/document:root/@identifier",
                            Collections.singletonMap("document", "urn:example")
                        )
                    ); // no matching element in payload
        } finally {
            signatureEndpoint.stop();
        }
    }
}
