import com.example.spi.PublishedContract;
import com.example.spi.RequiredContract;

/**
 * This is the comment describing the interface
 */
public /*a*/ interface /*b*/ CatalogGateway
  /*a*/ extends /*b*/ /*a*/ PublishedContract /*b*/ /*a*/, /*b*/ /*a*/ RequiredContract
/*b*/ {
  // comment
  /**
   * Javadoc
   * @param context request context
   * @param options sync options
   * @throws Exception Exception comment
   * @throws RuntimeException RuntimeException comment
   */
  public void syncCatalog(
    RequestContext /*a*/ context /*b*/ /*a*/,
    /*b*/ /*a*/ SyncOptions /*b*/ /*a*/ options /*b*/,
    AuditTrail auditTrail
  ) /*a*/ throws /*b*/ Exception /*a*/, /*b*/ RuntimeException /*a*/; /*b*/
}
