class BinaryParenthesizedOrCondition {

    boolean shouldRetry(Throwable signal) {
        return (
            (signal instanceof RemoteFailureEnvelope remote &&
                remote.statusDescriptor().isRecoverableTransientServerFailure()) ||
            signal instanceof LocalTransportFailure
        );
    }
}
