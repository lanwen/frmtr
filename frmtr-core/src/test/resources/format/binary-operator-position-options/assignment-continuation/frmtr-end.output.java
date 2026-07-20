class AssignmentContinuation {

  void update() {
    enabled = accountReady &&
      quotaAvailable &&
      regionActive &&
      policyAccepted;
  }
}
