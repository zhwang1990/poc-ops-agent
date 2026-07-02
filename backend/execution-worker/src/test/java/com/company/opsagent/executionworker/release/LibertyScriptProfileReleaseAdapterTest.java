package com.company.opsagent.executionworker.release;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.opsagent.contracts.workflow.OperatorContext;
import com.company.opsagent.contracts.workflow.PolicyDecisionReference;
import com.company.opsagent.contracts.workflow.TraceContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LibertyScriptProfileReleaseAdapterTest {

  private final Clock clock = Clock.fixed(Instant.parse("2026-07-01T00:00:00Z"), ZoneOffset.UTC);

  @TempDir
  Path tempDir;

  @Test
  void deployRunsConfiguredScriptProfileWithoutShell() throws Exception {
    byte[] artifactBytes = "war-content".getBytes(StandardCharsets.UTF_8);
    Files.write(tempDir.resolve("artifact-1.war"), artifactBytes);
    Path output = tempDir.resolve("script-output.txt");
    LibertyScriptProfileReleaseAdapter adapter = adapter(output);

    ReleaseWorkerResult result = adapter.deploy(request(checksum(artifactBytes))).block();

    assertEquals(ReleaseWorkerStatus.SUCCEEDED, result.status());
    String captured = Files.readString(output, StandardCharsets.UTF_8);
    assertTrue(captured.contains("artifact-1.war"));
    assertTrue(captured.contains("defaultServer"));
    assertTrue(captured.contains("orders"));
  }

  @Test
  void deployRunsConfiguredScriptProfileWithoutArtifact() throws Exception {
    Path output = tempDir.resolve("script-output.txt");
    LibertyScriptProfileReleaseAdapter adapter = adapterWithoutArtifactTemplate(output);

    ReleaseWorkerResult result = adapter.deploy(requestWithoutArtifact()).block();

    assertEquals(ReleaseWorkerStatus.SUCCEEDED, result.status());
    String captured = Files.readString(output, StandardCharsets.UTF_8);
    assertTrue(captured.contains("defaultServer"));
    assertTrue(captured.contains("orders"));
  }

  @Test
  void deployRejectsUnknownScriptProfileParameter() throws Exception {
    byte[] artifactBytes = "war-content".getBytes(StandardCharsets.UTF_8);
    Files.write(tempDir.resolve("artifact-1.war"), artifactBytes);
    ReleaseWorkerResult result = adapter(tempDir.resolve("script-output.txt"))
        .deploy(request(
            checksum(artifactBytes),
            new ReleaseWorkerRequest.ReleaseScriptProfile(
                "liberty-war-deploy",
                List.of(
                    new ReleaseWorkerRequest.ReleaseScriptParameter("serverName", "defaultServer"),
                    new ReleaseWorkerRequest.ReleaseScriptParameter("unexpected", "value")))))
        .block();

    assertEquals(ReleaseWorkerStatus.REJECTED, result.status());
    assertEquals("LIBERTY_SCRIPT_PARAMETER_NOT_ALLOWED", result.errorCode());
  }

  @Test
  void deployRejectsSensitiveScriptProfileParameterName() throws Exception {
    byte[] artifactBytes = "war-content".getBytes(StandardCharsets.UTF_8);
    Files.write(tempDir.resolve("artifact-1.war"), artifactBytes);
    ReleaseWorkerResult result = adapter(tempDir.resolve("script-output.txt"))
        .deploy(request(
            checksum(artifactBytes),
            new ReleaseWorkerRequest.ReleaseScriptProfile(
                "liberty-war-deploy",
                List.of(
                    new ReleaseWorkerRequest.ReleaseScriptParameter("serverName", "defaultServer"),
                    new ReleaseWorkerRequest.ReleaseScriptParameter("applicationName", "orders"),
                    new ReleaseWorkerRequest.ReleaseScriptParameter("apiToken", "value")))))
        .block();

    assertEquals(ReleaseWorkerStatus.REJECTED, result.status());
    assertEquals("LIBERTY_SCRIPT_PROFILE_INVALID", result.errorCode());
  }

  private LibertyScriptProfileReleaseAdapter adapter(Path output) {
    ReleaseWorkerProperties.Liberty.ScriptProfile profile = new ReleaseWorkerProperties.Liberty.ScriptProfile();
    profile.setExecutablePath(javaExecutable());
    profile.setArguments(List.of(
        "-cp",
        System.getProperty("java.class.path"),
        LibertyScriptProbe.class.getName(),
        output.toString(),
        "{{artifactPath}}",
        "{{param.serverName}}",
        "{{param.applicationName}}"));
    profile.setRequiredParameters(List.of("serverName", "applicationName"));
    profile.setAllowedParameters(List.of("serverName", "applicationName"));
    profile.setTimeout(Duration.ofSeconds(10));
    return new LibertyScriptProfileReleaseAdapter(
        tempDir,
        Map.of("liberty-war-deploy", profile),
        clock);
  }

  private LibertyScriptProfileReleaseAdapter adapterWithoutArtifactTemplate(Path output) {
    ReleaseWorkerProperties.Liberty.ScriptProfile profile = new ReleaseWorkerProperties.Liberty.ScriptProfile();
    profile.setExecutablePath(javaExecutable());
    profile.setArguments(List.of(
        "-cp",
        System.getProperty("java.class.path"),
        LibertyScriptProbe.class.getName(),
        output.toString(),
        "{{param.serverName}}",
        "{{param.applicationName}}"));
    profile.setRequiredParameters(List.of("serverName", "applicationName"));
    profile.setAllowedParameters(List.of("serverName", "applicationName"));
    profile.setTimeout(Duration.ofSeconds(10));
    return new LibertyScriptProfileReleaseAdapter(
        tempDir,
        Map.of("liberty-war-deploy", profile),
        clock);
  }

  private ReleaseWorkerRequest request(String checksum) {
    return request(
        checksum,
        new ReleaseWorkerRequest.ReleaseScriptProfile(
            "liberty-war-deploy",
            List.of(
                new ReleaseWorkerRequest.ReleaseScriptParameter("serverName", "defaultServer"),
                new ReleaseWorkerRequest.ReleaseScriptParameter("applicationName", "orders"))));
  }

  private ReleaseWorkerRequest request(String checksum, ReleaseWorkerRequest.ReleaseScriptProfile scriptProfile) {
    return request(new ReleaseWorkerRequest.ReleaseArtifactReference("artifact-1", "WAR", checksum, "artifact-1.war"), scriptProfile);
  }

  private ReleaseWorkerRequest requestWithoutArtifact() {
    return request(
        (ReleaseWorkerRequest.ReleaseArtifactReference) null,
        new ReleaseWorkerRequest.ReleaseScriptProfile(
            "liberty-war-deploy",
            List.of(
                new ReleaseWorkerRequest.ReleaseScriptParameter("serverName", "defaultServer"),
                new ReleaseWorkerRequest.ReleaseScriptParameter("applicationName", "orders"))));
  }

  private ReleaseWorkerRequest request(
      ReleaseWorkerRequest.ReleaseArtifactReference artifact,
      ReleaseWorkerRequest.ReleaseScriptProfile scriptProfile) {
    OffsetDateTime now = OffsetDateTime.now(clock);
    var command = new ReleaseWorkerRequest.ReleaseCommand(
        "1.0",
        "rel-1",
        "550e8400-e29b-41d4-a716-446655440000",
        "DEPLOY",
        "dev",
        "orders",
        artifact,
        List.of(new ReleaseWorkerRequest.ReleaseNodeTarget(
            "node-1",
            "LIBERTY",
            "LIBERTY_SCRIPT_PROFILE",
            "https://liberty-dev.example",
            "/orders",
            "liberty-dev",
            scriptProfile)),
        new OperatorContext("operator-release", List.of("ROLE_ops-release")),
        new PolicyDecisionReference("decision-release", "policy-v1", "ALLOW"),
        new TraceContext("trace-release", "request-release"),
        now);
    return new ReleaseWorkerRequest("1.0", "550e8400-e29b-41d4-a716-446655440001", now, now.plusSeconds(30), command);
  }

  private String checksum(byte[] bytes) throws Exception {
    return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  private Path javaExecutable() {
    String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
    return Path.of(System.getProperty("java.home"), "bin", executable);
  }
}
