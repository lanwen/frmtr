class ReturnChainFinalArgumentSample {
    Result<Command> wrap(Result<Command> pipeline) {
        return Pipelines.wrap(pipeline).onFailure(
            RetryPolicy.restart().withLoggingEnabled(true).withResetChildren(true)
        );
    }
}
