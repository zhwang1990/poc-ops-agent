package com.company.opsagent.executionworker.release;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.company.opsagent.contracts.workflow.OperatorContext;
import com.company.opsagent.contracts.workflow.PolicyDecisionReference;
import com.company.opsagent.contracts.workflow.TraceContext;
import com.company.opsagent.executionworker.WorkerHttpEgressPolicy;
import com.company.opsagent.executionworker.WorkerHttpEgressTarget;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class LibertyHttpsReleaseAdapterTest {

  private final Clock clock = Clock.fixed(Instant.parse("2026-07-01T00:00:00Z"), ZoneOffset.UTC);

  @Test
  void deployUsesConfiguredBaseUrlInsteadOfRequestCarriedUrlText() throws Exception {
    AtomicReference<String> path = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/liberty/deploy", exchange -> {
      path.set(exchange.getRequestURI().getPath());
      byte[] body = "{\"status\":\"accepted\"}".getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
    try {
      int port = server.getAddress().getPort();
      LibertyHttpsReleaseAdapter adapter = new LibertyHttpsReleaseAdapter(
          "http://127.0.0.1:" + port + "/liberty",
          "liberty-release",
          HttpClient.newHttpClient(),
          new ObjectMapper(),
          new WorkerHttpEgressPolicy(List.of(new WorkerHttpEgressTarget("http", "127.0.0.1", port))),
          clock);

      ReleaseWorkerResult result = adapter.deploy(request("https://evil.example/ignored")).block();

      assertEquals(ReleaseWorkerStatus.SUCCEEDED, result.status());
      assertEquals("/liberty/deploy", path.get());
    } finally {
      server.stop(0);
    }
  }

  private ReleaseWorkerRequest request(String applicationId) {
    OffsetDateTime now = OffsetDateTime.now(clock);
    var command = new ReleaseWorkerRequest.ReleaseCommand(
        "1.0",
        "rel-1",
        "550e8400-e29b-41d4-a716-446655440000",
        "DEPLOY",
        "dev",
        applicationId,
        new ReleaseWorkerRequest.ReleaseArtifactReference("artifact-1", "WAR", "sha256:abc123"),
        List.of(new ReleaseWorkerRequest.ReleaseNodeTarget("node-1", "LIBERTY", "LIBERTY_HTTPS")),
        new OperatorContext("operator-release", List.of("ROLE_ops-release")),
        new PolicyDecisionReference("decision-release", "policy-v1", "ALLOW"),
        new TraceContext("trace-release", "request-release"),
        now);
    return new ReleaseWorkerRequest("1.0", "550e8400-e29b-41d4-a716-446655440001", now, now.plusSeconds(30), command);
  }
}
