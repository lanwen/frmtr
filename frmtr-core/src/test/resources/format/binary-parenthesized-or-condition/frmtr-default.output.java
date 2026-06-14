class BinaryParenthesizedOrCondition {

    boolean shouldRetry(Throwable signal) {
        return (
            (signal instanceof RemoteFailureEnvelope remote
                && remote.statusDescriptor().isRecoverableTransientServerFailure())
            || signal instanceof LocalTransportFailure
        );
    }

    boolean shouldWrapGuard(Guard guard, Entry entry, Layout layout, String label, String flat) {
        return (
            guard instanceof ParenthesizedGuard
            || (measuredEntryWidth(label + flat + " -> {}") >= layout.lineWidth()
                && !singleLineSource(entry).isPresent())
        );
    }
}
