// frmtr-ignore-start
public class FormatterControlFrmtrIgnored {
  public void processBatch(int accountId, int regionId, int retryLimit, int timeoutSeconds, int batchSize, int pageNumber, int shardId, int workerCount, int priority, int auditLevel) {

  }
}
/* frmtr-ignore-end */
public class FormatterControlFrmtrRestored {

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
/* @formatter:off */
public class FormatterControlJavaDisabled {
  public void processBatch(int accountId, int regionId, int retryLimit, int timeoutSeconds, int batchSize, int pageNumber, int shardId, int workerCount, int priority, int auditLevel) {

  }
}
// @formatter:on
public class FormatterControlJavaRestored {

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
