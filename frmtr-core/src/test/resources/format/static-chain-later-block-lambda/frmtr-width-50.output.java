class Demo {

    void method() {
        Object handle = coordinator.attach(
            AbstractChainFactory
                    .start(AbstractEvent.class)
                    .next(AbstractEvent.Step.class, item -> {
                        item
                                .target()
                                .send(
                                    Result.failure(
                                        problem
                                    )
                                );
                        return AbstractChainFactory.keep();
                    })
        );
    }
}
