package com.company.opsagent.controlplane.modules.release;

public record ReleaseApplication(
    String applicationId,
    String displayName,
    ArtifactType artifactType,
    String healthCheckPath,
    boolean enabled) {

  public ReleaseApplication {
    applicationId = ReleaseValues.requiredText(applicationId, "applicationId");
    displayName = ReleaseValues.requiredText(displayName, "displayName");
    artifactType = ReleaseValues.required(artifactType, "artifactType");
    healthCheckPath = ReleaseValues.requiredText(healthCheckPath, "healthCheckPath");
  }

  public static ReleaseApplication create(
      String applicationId,
      String displayName,
      ArtifactType artifactType,
      String healthCheckPath,
      boolean enabled) {
    return new ReleaseApplication(applicationId, displayName, artifactType, healthCheckPath, enabled);
  }
}
