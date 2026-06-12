// @formatter:off
public class FormatterControlDisabledClass {
  public void processBatch(int accountId, int regionId, int retryLimit, int timeoutSeconds, int batchSize, int pageNumber, int shardId, int workerCount, int priority, int auditLevel) {

  }
}
// @formatter:on
public class FormatterControlEnabledClass {

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
