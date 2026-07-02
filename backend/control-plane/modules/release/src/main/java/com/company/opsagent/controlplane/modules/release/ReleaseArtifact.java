package com.company.opsagent.controlplane.modules.release;

public record ReleaseArtifact(
    String artifactId,
    String applicationId,
    TargetEnvironment targetEnvironment,
    ArtifactType artifactType,
    String checksum,
    String originalFilename,
    String storageKey,
    long byteSize,
    String uploadedBy,
    String sourceType,
    boolean enabled) {

  public ReleaseArtifact {
    artifactId = ReleaseValues.requiredText(artifactId, "artifactId");
    applicationId = ReleaseValues.requiredText(applicationId, "applicationId");
    targetEnvironment = ReleaseValues.required(targetEnvironment, "targetEnvironment");
    artifactType = ReleaseValues.required(artifactType, "artifactType");
    checksum = ReleaseValues.sha256Checksum(checksum);
    originalFilename = ReleaseValues.requiredText(originalFilename, "originalFilename");
    storageKey = ReleaseValues.requiredText(storageKey, "storageKey");
    if (byteSize < 0) {
      throw new IllegalArgumentException("byteSize must not be negative");
    }
    uploadedBy = ReleaseValues.requiredText(uploadedBy, "uploadedBy");
    sourceType = ReleaseValues.requiredText(sourceType, "sourceType");
  }

  public static ReleaseArtifact create(
      String artifactId,
      String applicationId,
      ArtifactType artifactType,
      String checksum,
      String sourceType,
      boolean enabled) {
    return new ReleaseArtifact(
        artifactId,
        applicationId,
        TargetEnvironment.DEV,
        artifactType,
        checksum,
        artifactId + ".war",
        sourceType,
        0,
        "system",
        sourceType,
        enabled);
  }

  public static ReleaseArtifact create(
      String artifactId,
      String applicationId,
      String targetEnvironment,
      ArtifactType artifactType,
      String checksum,
      String originalFilename,
      String storageKey,
      long byteSize,
      String uploadedBy,
      String sourceType,
      boolean enabled) {
    return new ReleaseArtifact(
        artifactId,
        applicationId,
        TargetEnvironment.from(targetEnvironment),
        artifactType,
        checksum,
        originalFilename,
        storageKey,
        byteSize,
        uploadedBy,
        sourceType,
        enabled);
  }
}
