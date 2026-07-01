package com.company.opsagent.controlplane.modules.release;

public record ReleaseEnvironmentPolicy(
    TargetEnvironment targetEnvironment,
    boolean confirmationRequired,
    boolean approvalRequired,
    boolean enabled) {

  public ReleaseEnvironmentPolicy {
    targetEnvironment = ReleaseValues.required(targetEnvironment, "targetEnvironment");
  }

  public static ReleaseEnvironmentPolicy defaultFor(TargetEnvironment targetEnvironment) {
    TargetEnvironment environment = ReleaseValues.required(targetEnvironment, "targetEnvironment");
    boolean needsConfirmation = environment == TargetEnvironment.SIT || environment == TargetEnvironment.UAT;
    return new ReleaseEnvironmentPolicy(environment, needsConfirmation, false, true);
  }

  public ReleaseEnvironmentPolicy requireConfirmation(boolean required) {
    return new ReleaseEnvironmentPolicy(targetEnvironment, required, approvalRequired, enabled);
  }

  public ReleaseEnvironmentPolicy requireApproval(boolean required) {
    return new ReleaseEnvironmentPolicy(targetEnvironment, confirmationRequired, required, enabled);
  }

  public ReleaseEnvironmentPolicy enable(boolean enabled) {
    return new ReleaseEnvironmentPolicy(targetEnvironment, confirmationRequired, approvalRequired, enabled);
  }
}
