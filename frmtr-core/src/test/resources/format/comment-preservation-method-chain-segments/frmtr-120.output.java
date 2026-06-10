class MethodChainSegmentCommentSample {

    void configure(Container container) {
        container
            .withName("demo")
            .withCommand("one", "two")
            // keep segment note one
            // keep segment note two
            .waitingFor(new WaitStrategy())
            .withLabel("k", "v");
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
