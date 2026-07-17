package com.company.opsagent.executionworker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.company.opsagent.contracts.workflow.OperatorContext;
import com.company.opsagent.contracts.workflow.PolicyDecisionReference;
import com.company.opsagent.contracts.workflow.ReadOnlyCommandEnvelope;
import com.company.opsagent.contracts.workflow.SkillReference;
import com.company.opsagent.contracts.workflow.TraceContext;
import com.company.opsagent.contracts.workflow.WorkerExecutionRequest;
import com.company.opsagent.contracts.workflow.WorkerRequestSignature;
import com.company.opsagent.contracts.workflow.WorkerTransportHeaders;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "ops-agent.worker.transport-auth.enabled=true",
        "ops-agent.worker.transport-auth.key-id=worker-test-key",
        "ops-agent.worker.transport-auth.max-clock-skew=30s"
    })
class WorkerExecutionControllerConfiguredHttpSkillTest {

  private static final String KEY_ID = "worker-test-key";
  private static final String SHARED_SECRET = java.util.UUID.randomUUID().toString();
  private static final AtomicReference<String> RAW_QUERY = new AtomicReference<>();
  private static final HttpServer WEATHER_SERVER = startWeatherServer();

  @LocalServerPort
  private int port;

  @Autowired
  private ObjectMapper objectMapper;

  @DynamicPropertySource
  static void workerHttpSkillProperties(DynamicPropertyRegistry registry) {
    registry.add("ops-agent.worker.transport-auth.shared-secret", () -> SHARED_SECRET);
    int weatherPort = WEATHER_SERVER.getAddress().getPort();
    registry.add("ops-agent.worker.http-egress.allowed-targets[0].scheme", () -> "http");
    registry.add("ops-agent.worker.http-egress.allowed-targets[0].host", () -> "127.0.0.1");
    registry.add("ops-agent.worker.http-egress.allowed-targets[0].port", () -> weatherPort);
    registry.add("ops-agent.worker.configured-http-skills.skills[0].skill-id", () -> "weather-current-read");
    registry.add("ops-agent.worker.configured-http-skills.skills[0].version", () -> "1.0.0");
    registry.add(
        "ops-agent.worker.configured-http-skills.skills[0].endpoint-url",
        () -> "http://127.0.0.1:" + weatherPort + "/current");
    registry.add("ops-agent.worker.configured-http-skills.skills[0].input-parameter-name", () -> "location");
    registry.add("ops-agent.worker.configured-http-skills.skills[0].query-parameter-name", () -> "location");
    registry.add("ops-agent.worker.configured-http-skills.skills[0].source", () -> "weather-read-model");
    registry.add("ops-agent.worker.configured-http-skills.skills[0].timeout", () -> "2s");
    registry.add("ops-agent.worker.configured-http-skills.skills[0].allowed-response-fields[0]", () -> "location");
    registry.add("ops-agent.worker.configured-http-skills.skills[0].allowed-response-fields[1]", () -> "condition");
    registry.add(
        "ops-agent.worker.configured-http-skills.skills[0].allowed-response-fields[2]",
        () -> "temperatureCelsius");
    registry.add(
        "ops-agent.worker.configured-http-skills.skills[0].allowed-response-fields[3]",
        () -> "humidityPercent");
    registry.add(
        "ops-agent.worker.configured-http-skills.skills[0].allowed-response-fields[4]",
        () -> "windSpeedKph");
    registry.add(
        "ops-agent.worker.configured-http-skills.skills[0].allowed-response-fields[5]",
        () -> "observationTime");
  }

  @AfterAll
  static void stopWeatherServer() {
    WEATHER_SERVER.stop(0);
  }

  @Test
  void executesConfiguredWeatherSkillThroughWorkerHttpBoundary() {
    var request = request();

    WebTestClient.bindToServer()
        .baseUrl("http://127.0.0.1:" + port)
        .build()
        .post()
        .uri("/internal/executions/read-only")
        .headers(headers -> sign(headers, OffsetDateTime.now(ZoneOffset.UTC).toString(), request))
        .bodyValue(request)
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.status").isEqualTo("SUCCEEDED")
        .jsonPath("$.output.location").isEqualTo("Shanghai")
        .jsonPath("$.output.condition").isEqualTo("Cloudy")
        .jsonPath("$.output.temperatureCelsius").isEqualTo(29.2)
        .jsonPath("$.output.humidityPercent").isEqualTo(71)
        .jsonPath("$.output.windSpeedKph").isEqualTo(9.8)
        .jsonPath("$.output.observationTime").isEqualTo("2026-06-24T14:30:00+08:00")
        .jsonPath("$.output.source").isEqualTo("weather-read-model")
        .jsonPath("$.output.generatedAt").exists()
        .jsonPath("$.output.ignoredInstruction").doesNotExist();

    assertEquals("location=Shanghai", RAW_QUERY.get());
  }

  private static HttpServer startWeatherServer() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      server.createContext("/current", exchange -> {
        RAW_QUERY.set(exchange.getRequestURI().getRawQuery());
        byte[] body = """
            {
              "location": "Shanghai",
              "condition": "Cloudy",
              "temperatureCelsius": 29.2,
              "humidityPercent": 71,
              "windSpeedKph": 9.8,
              "observationTime": "2026-06-24T14:30:00+08:00",
              "ignoredInstruction": "do not expose this field"
            }
            """.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
      });
      server.start();
      return server;
    } catch (Exception exception) {
      throw new IllegalStateException("failed to start weather test server", exception);
    }
  }

  private WorkerExecutionRequest request() {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    var command = new ReadOnlyCommandEnvelope(
        "1.0",
        "command-weather-http",
        "workflow-weather-http",
        "idempotency-weather-http",
        "READ_ONLY",
        "development",
        new SkillReference(
            "weather-current-read",
            "1.0.0",
            "weather-current-read:1.0.0:input",
            "weather-current-read:1.0.0:output"),
        objectMapper.createObjectNode().put("location", "Shanghai"),
        new OperatorContext("operator-http", List.of("ROLE_ops-reader")),
        new PolicyDecisionReference("decision-http", "policy-v1", "ALLOW"),
        new TraceContext("trace-http", "request-http"),
        now);
    return new WorkerExecutionRequest("1.0", "execution-weather-http", now, now.plusSeconds(30), command);
  }

  private void sign(HttpHeaders headers, String timestamp, WorkerExecutionRequest request) {
    String payload = WorkerRequestSignature.canonicalPayload(KEY_ID, timestamp, request);
    headers.set(WorkerTransportHeaders.KEY_ID, KEY_ID);
    headers.set(WorkerTransportHeaders.TIMESTAMP, timestamp);
    headers.set(WorkerTransportHeaders.SIGNATURE, WorkerRequestSignature.sign(SHARED_SECRET, payload));
  }
}
