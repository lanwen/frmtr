/**
 * @format
 */
 public enum WorkflowState {

  QUEUED, RUNNING, FINISHED;

}

public enum WorkflowState {

  ACTIVE("abc"), PAUSED("abc");

  public static final String legacyStatusValue = "legacy";

  private final String value;

  public WorkflowState(String value) {
    this.value = value;
  }

  public String toString() {
    return "status";
  }

}

class ClassWithWorkflowState {

  public static enum AllowedTransitions {

    INITIAL, FINAL

  }

}
