class RetryPipelineWiring {

    void wireSingleParamStep(RetryContext context) {
        Object handle = RetryPipelineFactory
                .start(RetryPolicy.class).next(step -> {
            step.attempt().record(context.correlationId());
            return RetryPipelineFactory.keep();
        });
    }

    void wireTwoParamStep(RetryContext context) {
        Object handle = RetryPipelineFactory
                .start(RetryPolicy.class)
                .next((step, ctx) -> {
                    step.attempt()
                            .record(ctx.correlationId());
                    return RetryPipelineFactory.keep();
                });
    }
}
