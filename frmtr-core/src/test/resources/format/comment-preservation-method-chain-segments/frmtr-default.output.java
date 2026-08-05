class MethodChainSegmentCommentSample {

    static {
        // Removed until selector context is available.
        // ROUTES.put("aaaa-bbbb-cccc-dddd", "42"); // Example Workspace
        ROUTES.put("eeee-ffff-gggg-hhhh", "11"); // Example Team
    }

    @TestMarker
    // see note about codec 2.18.1
    // https://example.invalid/project/pull/4790
    // can be removed once runtime upgrades codec
    void parsesSyntheticClaims() {
        parse();
    }

    void configure(Container container) {
        container
                .withName("demo")
                .withCommand("one", "two")
                // keep segment note one
                // keep segment note two
                .waitingFor(new WaitStrategy())
                .withLabel("k", "v");
    }

    void waitForSignal() {
        StepRunner.begin().interval(tick)
                // keep later segment note
                .deadline(limit)
                .verify(() -> {
                    assertReady();
                });
    }

    void returnsSorted(MockServer server, List<Member> members) {
        server.reply(Response.listUsers(
            members.reversed().subList(0, MemberService.DEFAULT_PAGE_SIZE).toArray(new Member[0])
        )); // entries are returned newest first
    }
}

class Container {

    Container withName(String name) {
        return this;
    }

    Container withCommand(String first, String second) {
        return this;
    }

    Container waitingFor(WaitStrategy strategy) {
        return this;
    }

    Container withLabel(String key, String value) {
        return this;
    }
}

class WaitStrategy {}
