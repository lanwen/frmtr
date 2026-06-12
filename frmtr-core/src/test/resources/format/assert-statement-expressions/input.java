public class AssertStatements {

  public void assertBooleanExpression(String payload) {
    assert (payload != null);
  }

  public void assertValueExpression(String payload) {
    assert (payload != null) : "payload required";
  }

}