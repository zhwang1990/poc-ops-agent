package com.company.opsagent.controlplane.modules.release;

public record ReleaseNodeExecutionResult(boolean successful, String reason) {

  public static ReleaseNodeExecutionResult succeeded() {
    return new ReleaseNodeExecutionResult(true, null);
  }

  public static ReleaseNodeExecutionResult failed(String reason) {
    return new ReleaseNodeExecutionResult(false, ReleaseValues.requiredText(reason, "reason"));
  }
}
