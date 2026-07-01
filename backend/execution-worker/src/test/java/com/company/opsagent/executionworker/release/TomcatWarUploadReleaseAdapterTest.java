package com.company.opsagent.executionworker.release;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.company.opsagent.contracts.workflow.OperatorContext;
import com.company.opsagent.contracts.workflow.PolicyDecisionReference;
import com.company.opsagent.contracts.workflow.TraceContext;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class TomcatWarUploadReleaseAdapterTest {

  private final Clock clock = Clock.fixed(Instant.parse("2026-07-01T00:00:00Z"), ZoneOffset.UTC);
  private final TomcatWarUploadReleaseAdapter adapter = new TomcatWarUploadReleaseAdapter(clock);

  @Test
  void deployAcceptsRegisteredWarArtifactReference() {
    ReleaseWorkerResult result = adapter.deploy(request("artifact-1", "WAR", "sha256:abc123")).block();

    assertEquals(ReleaseWorkerStatus.SUCCEEDED, result.status());
  }

  @Test
  void deployRejectsLocalPathArtifactId() {
    ReleaseWorkerResult result = adapter.deploy(request("C:\\temp\\orders.war", "WAR", "sha256:abc123")).block();

    assertEquals(ReleaseWorkerStatus.REJECTED, result.status());
    assertEquals("TOMCAT_ARTIFACT_ID_INVALID", result.errorCode());
  }

  @Test
  void deployRejectsZipOrJarArtifact() {
    ReleaseWorkerResult zip = adapter.deploy(request("artifact-1", "ZIP", "sha256:abc123")).block();
    ReleaseWorkerResult jar = adapter.deploy(request("artifact-1", "JAR", "sha256:abc123")).block();

    assertEquals("TOMCAT_ARTIFACT_TYPE_NOT_SUPPORTED", zip.errorCode());
    assertEquals("TOMCAT_ARTIFACT_TYPE_NOT_SUPPORTED", jar.errorCode());
  }

  @Test
  void deployRejectsBlankChecksum() {
    ReleaseWorkerResult result = adapter.deploy(request("artifact-1", "WAR", " ")).block();

    assertEquals(ReleaseWorkerStatus.REJECTED, result.status());
    assertEquals("TOMCAT_ARTIFACT_CHECKSUM_REQUIRED", result.errorCode());
  }

  @Test
  void startReturnsManagementModeNotConfigured() {
    ReleaseWorkerResult result = adapter.start(request("artifact-1", "WAR", "sha256:abc123")).block();

    assertEquals(ReleaseWorkerStatus.REJECTED, result.status());
    assertEquals("SERVER_MANAGEMENT_MODE_NOT_CONFIGURED", result.errorCode());
  }

  private ReleaseWorkerRequest request(String artifactId, String artifactType, String checksum) {
    OffsetDateTime now = OffsetDateTime.now(clock);
    var command = new ReleaseWorkerRequest.ReleaseCommand(
        "1.0",
        "rel-1",
        "550e8400-e29b-41d4-a716-446655440000",
        "DEPLOY",
        "dev",
        "orders",
        new ReleaseWorkerRequest.ReleaseArtifactReference(artifactId, artifactType, checksum),
        List.of(new ReleaseWorkerRequest.ReleaseNodeTarget("node-1", "TOMCAT", "TOMCAT_WAR_UPLOAD")),
        new OperatorContext("operator-release", List.of("ROLE_ops-release")),
        new PolicyDecisionReference("decision-release", "policy-v1", "ALLOW"),
        new TraceContext("trace-release", "request-release"),
        now);
    return new ReleaseWorkerRequest("1.0", "550e8400-e29b-41d4-a716-446655440001", now, now.plusSeconds(30), command);
  }
}
