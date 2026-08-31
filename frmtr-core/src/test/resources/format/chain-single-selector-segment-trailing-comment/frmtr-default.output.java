class SessionReplayFixtureTest {

    void loadsExpectedOwner() throws Exception {
        mockEndpoint("mock:sessions").expectedBodiesReceived(
            ResourceLoader
                    .loadText(
                        ResourceLoader.resolveMandatoryResourceAsInputStream(
                            context,
                            "org/example/registry/session/expected-owner.json"
                        )
                    )
                    .trim() // Remove the last newline added by loadText()
        );
    }

    void loadsExpectedOwnerWithoutTrailingComment() throws Exception {
        mockEndpoint("mock:sessions").expectedBodiesReceived(
            ResourceLoader.loadText(ResourceLoader.resolveMandatoryResourceAsInputStream(
                context,
                "org/example/registry/session/expected-owner.json"
            )).trim()
        );
    }

    void keepsCommentAboveTheArgument() throws Exception {
        mockEndpoint("mock:sessions").expectedBodiesReceived(
            // the fixture file ends with a newline the comparison does not expect
            ResourceLoader.loadText(ResourceLoader.resolveMandatoryResourceAsInputStream(
                context,
                "org/example/registry/session/expected-owner.json"
            )).trim()
        );
    }

    void keepsInlineCommentAfterTheArgument() throws Exception {
        mockEndpoint("mock:sessions").expectedBodiesReceived(
            ResourceLoader.loadText(ResourceLoader.resolveMandatoryResourceAsInputStream(
                context,
                "org/example/registry/session/expected-owner.json"
            )).trim() /* trailing newline removed */
        );
    }

    void keepsCommentTrailingTheReceiver() throws Exception {
        mockEndpoint("mock:sessions").expectedBodiesReceived(
            // the endpoint under test
            ResourceLoader.loadText(ResourceLoader.resolveMandatoryResourceAsInputStream(
                context,
                "org/example/registry/session/expected-owner.json"
            )).trim()
        );
    }
}
