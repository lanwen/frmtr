class PollScheduler {
    void evaluate() {
        if (backoffFactor > 0
                // either idle or failure budget may be disabled, so fall back to MAX_VALUE when a threshold is off
                && idleTicks.longValue() >= (idleBudgetThreshold > 0 ? idleBudgetThreshold : Integer.MAX_VALUE)
                || failureTicks.longValue() >= (failureBudgetThreshold > 0 ? failureBudgetThreshold : Integer.MAX_VALUE)) {
            triggerBackoff();
        }
    }
}
