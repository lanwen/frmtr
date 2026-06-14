public class FormatterControlInsideBlock {

    public void processBatch(
            int accountId,
            int regionId,
            int retryLimit,
            int timeoutSeconds,
            int batchSize,
            int pageNumber,
            int shardId,
            int workerCount,
            int priority,
            int auditLevel
    ) {
        // @formatter:off
        System.out.println("This operation with two very long string should not break because the formatter is off");
        // @formatter:on
        System.out.println("This operation with two very long string should break because the formatter is on");
    }
}
