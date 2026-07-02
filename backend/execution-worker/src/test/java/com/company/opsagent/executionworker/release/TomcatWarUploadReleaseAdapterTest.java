package com.company.opsagent.executionworker.release;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.company.opsagent.contracts.workflow.OperatorContext;
import com.company.opsagent.contracts.workflow.PolicyDecisionReference;
import com.company.opsagent.contracts.workflow.TraceContext;
import com.company.opsagent.executionworker.WorkerHttpEgressPolicy;
import com.company.opsagent.executionworker.WorkerHttpEgressTarget;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TomcatWarUploadReleaseAdapterTest {

  private final Clock clock = Clock.fixed(Instant.parse("2026-07-01T00:00:00Z"), ZoneOffset.UTC);

  @TempDir
  Path tempDir;

  @Test
  void deployUploadsWarToTomcatManager() throws Exception {
    byte[] artifactBytes = "hello-war".getBytes(StandardCharsets.UTF_8);
    Files.write(tempDir.resolve("artifact-1.war"), artifactBytes);
    AtomicReference<String> method = new AtomicReference<>();
    AtomicReference<String> query = new AtomicReference<>();
    AtomicReference<String> authorization = new AtomicReference<>();
    AtomicReference<String> requestBody = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/manager/text/deploy", exchange -> {
      method.set(exchange.getRequestMethod());
      query.set(exchange.getRequestURI().getRawQuery());
      authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
      requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      byte[] response = "OK - Deployed application at context path [/orders]".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, response.length);
      exchange.getResponseBody().write(response);
      exchange.close();
    });
    server.start();
    try {
      TomcatWarUploadReleaseAdapter adapter = adapter(server.getAddress().getPort());

      ReleaseWorkerResult result = adapter.deploy(request(
          "artifact-1",
          "WAR",
          checksum(artifactBytes),
          "artifact-1.war",
          "http://127.0.0.1:" + server.getAddress().getPort() + "/manager/text",
          "/orders",
          "tomcat-dev")).block();

      assertEquals(ReleaseWorkerStatus.SUCCEEDED, result.status());
      assertEquals("PUT", method.get());
      assertEquals("path=%2Forders&update=true", query.get());
      assertEquals(
          "Basic " + Base64.getEncoder().encodeToString("manager:secret".getBytes(StandardCharsets.UTF_8)),
          authorization.get());
      assertEquals("hello-war", requestBody.get());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void deployRejectsLocalPathArtifactId() {
    ReleaseWorkerResult result = adapter(18080)
        .deploy(request("C:\\temp\\orders.war", "WAR", "sha256:abc123"))
        .block();

    assertEquals(ReleaseWorkerStatus.REJECTED, result.status());
    assertEquals("TOMCAT_ARTIFACT_ID_INVALID", result.errorCode());
  }

  @Test
  void deployRejectsZipOrJarArtifact() {
    TomcatWarUploadReleaseAdapter adapter = adapter(18080);
    ReleaseWorkerResult zip = adapter.deploy(request("artifact-1", "ZIP", "sha256:abc123")).block();
    ReleaseWorkerResult jar = adapter.deploy(request("artifact-1", "JAR", "sha256:abc123")).block();

    assertEquals("TOMCAT_ARTIFACT_TYPE_NOT_SUPPORTED", zip.errorCode());
    assertEquals("TOMCAT_ARTIFACT_TYPE_NOT_SUPPORTED", jar.errorCode());
  }

  @Test
  void deployRejectsBlankChecksum() {
    ReleaseWorkerResult result = adapter(18080).deploy(request("artifact-1", "WAR", " ")).block();

    assertEquals(ReleaseWorkerStatus.REJECTED, result.status());
    assertEquals("TOMCAT_ARTIFACT_CHECKSUM_REQUIRED", result.errorCode());
  }

  @Test
  void startReturnsManagementModeNotConfigured() {
    ReleaseWorkerResult result = adapter(18080).start(request("artifact-1", "WAR", "sha256:abc123")).block();

    assertEquals(ReleaseWorkerStatus.REJECTED, result.status());
    assertEquals("SERVER_MANAGEMENT_MODE_NOT_CONFIGURED", result.errorCode());
  }

  private TomcatWarUploadReleaseAdapter adapter(int port) {
    return new TomcatWarUploadReleaseAdapter(
        tempDir,
        Map.of("tomcat-dev", new ReleaseWorkerProperties.Tomcat.Credential("manager", "secret")),
        HttpClient.newHttpClient(),
        new WorkerHttpEgressPolicy(List.of(new WorkerHttpEgressTarget("http", "127.0.0.1", port))),
        clock);
  }

  private ReleaseWorkerRequest request(String artifactId, String artifactType, String checksum) {
    return request(
        artifactId,
        artifactType,
        checksum,
        "artifact-1.war",
        "http://127.0.0.1:18080/manager/text",
        "/orders",
        "tomcat-dev");
  }

  private ReleaseWorkerRequest request(
      String artifactId,
      String artifactType,
      String checksum,
      String storageKey,
      String managementEndpoint,
      String applicationPath,
      String credentialAlias) {
    OffsetDateTime now = OffsetDateTime.now(clock);
    var command = new ReleaseWorkerRequest.ReleaseCommand(
        "1.0",
        "rel-1",
        "550e8400-e29b-41d4-a716-446655440000",
        "DEPLOY",
        "dev",
        "orders",
        new ReleaseWorkerRequest.ReleaseArtifactReference(artifactId, artifactType, checksum, storageKey),
        List.of(new ReleaseWorkerRequest.ReleaseNodeTarget(
            "node-1",
            "TOMCAT",
            "TOMCAT_WAR_UPLOAD",
            managementEndpoint,
            applicationPath,
            credentialAlias)),
        new OperatorContext("operator-release", List.of("ROLE_ops-release")),
        new PolicyDecisionReference("decision-release", "policy-v1", "ALLOW"),
        new TraceContext("trace-release", "request-release"),
        now);
    return new ReleaseWorkerRequest("1.0", "550e8400-e29b-41d4-a716-446655440001", now, now.plusSeconds(30), command);
  }

  private String checksum(byte[] bytes) throws Exception {
    return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
  }
}
