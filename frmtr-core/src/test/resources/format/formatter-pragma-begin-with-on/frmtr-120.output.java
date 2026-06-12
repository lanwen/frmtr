// @formatter:on
public class FormatterControlInitiallyOn {

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
    ) {}
}
// @formatter:off
public class FormatterControlDisabledBlock {
  public void processBatch(int accountId, int regionId, int retryLimit, int timeoutSeconds, int batchSize, int pageNumber, int shardId, int workerCount, int priority, int auditLevel) {

  }
}
// @formatter:on
public class FormatterControlRestoredBlock {

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
    ) {}
}
