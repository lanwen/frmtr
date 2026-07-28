package dev.example.pipeline;

class ChainBlockLambdaCloseSegmentColumn {

    void flatAuthoredRetrySegment(RetryTarget target) {
        PipelineStage.defer(() -> {
            return PipelineStage.just("connected");
        }).retryWhen(RetryPolicy.create(new BackoffWindow("shard", BackoffWindow.between(5, 9)), target).toSchedule());
    }

    void wrappedAuthoredRetrySegment(RetryTarget target) {
        PipelineStage.defer(() -> {
            return PipelineStage.just("connected");
        })
            .retryWhen(RetryPolicy.create(new BackoffWindow("shard", BackoffWindow.between(5, 9)), target).toSchedule());
    }
}
