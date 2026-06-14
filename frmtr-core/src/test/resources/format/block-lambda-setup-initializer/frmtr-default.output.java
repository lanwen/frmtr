class BlockLambdaSetupInitializerSample {

    Result<Command> create(
            String name,
            Parser parser,
            Renderer renderer,
            Reference<InboxCommand> inbox,
            Collector collector,
            Clock clock,
            boolean featureEnabled,
            Factory factory
    ) {
        var pipeline = Pipelines.<Command>setup(context -> {
            return new InitializedPipeline(
                name,
                context,
                parser,
                renderer,
                inbox,
                collector,
                clock,
                featureEnabled,
                factory
            );
        });
        return pipeline;
    }
}
