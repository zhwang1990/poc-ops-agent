package com.company.opsagent.controlplane.modules.release;

public record ReleaseArtifact(
    String artifactId,
    String applicationId,
    ArtifactType artifactType,
    String checksum,
    String sourceType,
    boolean enabled) {

  public ReleaseArtifact {
    artifactId = ReleaseValues.requiredText(artifactId, "artifactId");
    applicationId = ReleaseValues.requiredText(applicationId, "applicationId");
    artifactType = ReleaseValues.required(artifactType, "artifactType");
    checksum = ReleaseValues.sha256Checksum(checksum);
    sourceType = ReleaseValues.requiredText(sourceType, "sourceType");
  }

  public static ReleaseArtifact create(
      String artifactId,
      String applicationId,
      ArtifactType artifactType,
      String checksum,
      String sourceType,
      boolean enabled) {
    return new ReleaseArtifact(artifactId, applicationId, artifactType, checksum, sourceType, enabled);
  }
}
