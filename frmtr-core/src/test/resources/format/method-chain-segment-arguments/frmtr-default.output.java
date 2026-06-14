class MethodChainSegmentArgumentsSample {

    Result waitForSelection(Context context, Duration shortDelay, Duration totalDelay) {
        return Waiter.await()
                .pollInterval(shortDelay)
                .atMost(totalDelay)
                .until(
                    () -> {
                        var entries = context.entries();
                        return entries.isEmpty() ? null : entries.getFirst();
                    },
                    Objects::nonNull
                );
    }

    void reportWorkerFailures(Job job) {
        if (includeStackTrace) {
            job
                  .failedEntries()
                  .forEach(
                      entry -> entry.failureCause().ifPresent(
                          cause -> recordFailure(entry.displayPath().toString(), cause)
                      )
                  );
            return;
        }
    }
}
