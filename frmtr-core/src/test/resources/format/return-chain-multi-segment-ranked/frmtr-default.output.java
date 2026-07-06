class ReturnChainMultiSegmentRankedSample {

    String describeActiveProfile(DeploymentTarget deploymentTarget) {
        return deploymentTarget.getConfigurationRegistry()
                .resolveActiveProfileSelector()
                .formatActiveProfileLabelForDisplay();
    }

    RetryController buildRetryController(RequestPipeline requestPipeline) {
        return PipelineFactory.wrap(requestPipeline).withFailureRecovery(
            RetryPolicy.restart().withResetChildrenEnabled(true)
        );
    }

    ExecutionResult submitForExecution(TaskScheduler taskScheduler, WorkloadDescriptor workloadDescriptor) {
        return taskScheduler.enqueuePending(workloadDescriptor)
                .awaitScheduledStart()
                .collectExecutionResult();
    }
}
