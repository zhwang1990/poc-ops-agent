package com.company.opsagent.controlplane.modules.release;

public record ReleaseEnvironmentPolicy(
    TargetEnvironment targetEnvironment,
    boolean allowDeploy,
    boolean allowStart,
    boolean allowStop,
    boolean allowRollback,
    boolean confirmationRequired,
    int timeoutSeconds,
    boolean stopOnNodeFailure,
    boolean logAnalysisEnabled,
    boolean enabled) {

  public ReleaseEnvironmentPolicy {
    targetEnvironment = ReleaseValues.required(targetEnvironment, "targetEnvironment");
    if (timeoutSeconds < 1) {
      throw new IllegalArgumentException("timeoutSeconds must be positive");
    }
  }

  public static ReleaseEnvironmentPolicy defaultFor(TargetEnvironment targetEnvironment) {
    TargetEnvironment environment = ReleaseValues.required(targetEnvironment, "targetEnvironment");
    boolean needsConfirmation = environment == TargetEnvironment.SIT || environment == TargetEnvironment.UAT;
    return new ReleaseEnvironmentPolicy(environment, true, true, true, true, needsConfirmation, 600, true, false, true);
  }

  public ReleaseEnvironmentPolicy requireConfirmation(boolean required) {
    return new ReleaseEnvironmentPolicy(
        targetEnvironment,
        allowDeploy,
        allowStart,
        allowStop,
        allowRollback,
        required,
        timeoutSeconds,
        stopOnNodeFailure,
        logAnalysisEnabled,
        enabled);
  }

  public ReleaseEnvironmentPolicy withLogAnalysis(boolean enabled) {
    return new ReleaseEnvironmentPolicy(
        targetEnvironment,
        allowDeploy,
        allowStart,
        allowStop,
        allowRollback,
        confirmationRequired,
        timeoutSeconds,
        stopOnNodeFailure,
        enabled,
        this.enabled);
  }

  public ReleaseEnvironmentPolicy enable(boolean enabled) {
    return new ReleaseEnvironmentPolicy(
        targetEnvironment,
        allowDeploy,
        allowStart,
        allowStop,
        allowRollback,
        confirmationRequired,
        timeoutSeconds,
        stopOnNodeFailure,
        logAnalysisEnabled,
        enabled);
  }
}
