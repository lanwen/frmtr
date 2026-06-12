package sample;

final class MethodChainTrailingEmptyCallComment {

    void choose() {
        var selected = use(
            SubjectFactory.create() // primary subject
                .named("first")
                .enabled()
        );
        sink(selected);
    }

    void verify() {
        verifyResult(source())
            // expected branch
            .expect(IllegalStateException.class)
            .complete();
    }
}
