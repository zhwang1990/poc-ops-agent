package com.company.opsagent.controlplane.modules.release;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ReleaseCatalogModelTest {

  @Test
  void releaseServerRejectsProductionEnvironment() {
    IllegalArgumentException error = assertThrows(
        IllegalArgumentException.class,
        () -> ReleaseServer.create(
            "node-1",
            "prod",
            ServerType.TOMCAT,
            ManagementMode.TOMCAT_WAR_UPLOAD,
            "https://tomcat.example",
            true));

    assertEquals("targetEnvironment must be dev, sit or uat", error.getMessage());
  }

  @Test
  void releaseServerAcceptsNonProductionTomcatWarUpload() {
    ReleaseServer server = ReleaseServer.create(
        "node-1",
        "sit",
        ServerType.TOMCAT,
        ManagementMode.TOMCAT_WAR_UPLOAD,
        "https://tomcat.example",
        true);

    assertEquals(TargetEnvironment.SIT, server.targetEnvironment());
    assertEquals(ServerType.TOMCAT, server.serverType());
    assertEquals(ManagementMode.TOMCAT_WAR_UPLOAD, server.managementMode());
  }

  @Test
  void environmentPolicyRequiresConfirmationForSitAndUatByDefault() {
    assertTrue(ReleaseEnvironmentPolicy.defaultFor(TargetEnvironment.SIT).confirmationRequired());
    assertTrue(ReleaseEnvironmentPolicy.defaultFor(TargetEnvironment.UAT).confirmationRequired());
  }

  @Test
  void releaseArtifactRequiresWarAndSha256Checksum() {
    ReleaseArtifact artifact = ReleaseArtifact.create(
        "artifact-1",
        "orders",
        ArtifactType.WAR,
        "sha256:abc123",
        "TOMCAT_UPLOAD",
        true);

    assertEquals(ArtifactType.WAR, artifact.artifactType());
    assertThrows(IllegalArgumentException.class, () -> ReleaseArtifact.create(
        "artifact-2",
        "orders",
        ArtifactType.WAR,
        "md5:abc123",
        "TOMCAT_UPLOAD",
        true));
  }
}
