public class MethodReferenceSamples {

  public void referenceToAStaticMethod() {
    call(UserMapper::normalize);
  }

  public referenceToAConstructor() {
    call(InvoiceRecord::new);
  }

  public referenceToAnInstanceMethodOfAnArbitraryObjectOfAParticularType() {
    call(MessageHandler::handle);
  }

  public referenceToAnInstanceMethodOfAParticularObject() {
    call(auditLogger::record);
  }

}
