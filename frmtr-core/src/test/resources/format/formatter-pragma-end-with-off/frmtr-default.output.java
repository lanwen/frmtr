// @formatter:off
public class FormatterControlFirstDisabledClass {
  public void processBatch(int accountId, int regionId, int retryLimit, int timeoutSeconds, int batchSize, int pageNumber, int shardId, int workerCount, int priority, int auditLevel) {

  }
}
// @formatter:on
public class FormatterControlFirstEnabledClass {

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
public class FormatterControlSecondDisabledClass {
  public void processBatch(int accountId, int regionId, int retryLimit, int timeoutSeconds, int batchSize, int pageNumber, int shardId, int workerCount, int priority, int auditLevel) {

  }
}
public class FormatterControlAfterOpenDisable {
  public void processBatch(int accountId, int regionId, int retryLimit, int timeoutSeconds, int batchSize, int pageNumber, int shardId, int workerCount, int priority, int auditLevel) {

  }
}
