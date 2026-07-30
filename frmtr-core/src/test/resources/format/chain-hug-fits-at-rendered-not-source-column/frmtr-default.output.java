class RetentionSweeper {

    private void sweepAndReschedule(long generation) {
        timer.add(new SweepTask(config.sweepIntervalMillisForRetentionPool()) {
            @Override
            public void run() {
                List<CompletableFuture<Void>> waiters = new ArrayList<>();
                CompletableFuture.allOf(waiters.toArray(new CompletableFuture<?>[]{})).whenComplete((res, err) -> {
                    if (err != null) {
                        log.error("Received error during scheduled retention sweep.", err);
                    }
                    // Perpetual recursion, failure or not.
                    sweepAndReschedule(generation);
                });
            }
        });
    }

    private void cleanupOrphanedRecordsAndReschedule(long generation) {
        cleanupTimer.add(
            CLEANUP_TASK_KEY,
            new ScheduledRetentionCleanupTask(config.sweepIntervalMillisForRetentionPool()) {
                @Override
                public void run() {
                    List<CompletableFuture<Void>> waiters = new ArrayList<>();
                    CompletableFuture.allOf(waiters.toArray(new CompletableFuture<?>[]{})).whenComplete((res, err) -> {
                        if (err != null) {
                            log.error("Received error during orphaned record cleanup sweep.", err);
                        }
                        // Perpetual recursion, failure or not.
                        cleanupOrphanedRecordsAndReschedule(generation);
                    });
                }
            }
        );
    }
}
