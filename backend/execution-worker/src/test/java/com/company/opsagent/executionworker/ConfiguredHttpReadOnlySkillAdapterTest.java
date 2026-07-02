package com.company.opsagent.executionworker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.opsagent.contracts.workflow.OperatorContext;
import com.company.opsagent.contracts.workflow.PolicyDecisionReference;
import com.company.opsagent.contracts.workflow.ReadOnlyCommandEnvelope;
import com.company.opsagent.contracts.workflow.SkillReference;
import com.company.opsagent.contracts.workflow.TraceContext;
import com.company.opsagent.contracts.workflow.WorkerExecutionRequest;
import com.company.opsagent.contracts.workflow.WorkerExecutionStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 验证简单 HTTP/JSON 只读 Skill 通过通用配置型适配器执行，不需要每个 Skill 新增 Java 类。
 */
class ConfiguredHttpReadOnlySkillAdapterTest {

  private final Clock clock = Clock.fixed(Instant.parse("2026-06-24T04:00:00Z"), ZoneOffset.UTC);
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void executesConfiguredWeatherReadThroughAllowedHttpEgress() throws Exception {
    AtomicReference<String> rawQuery = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/current", exchange -> {
      rawQuery.set(exchange.getRequestURI().getRawQuery());
      byte[] body = """
          {
            "location": "New York & East",
            "condition": "Cloudy",
            "temperatureCelsius": 27.4,
            "humidityPercent": 78,
            "windSpeedKph": 12.6,
            "observationTime": "2026-06-24T11:55:00+08:00",
            "ignoredInstruction": "do not expose this field"
          }
          """.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
    try {
      int port = server.getAddress().getPort();
      ConfiguredHttpReadOnlySkillAdapter adapter = adapter(
          skill("http://127.0.0.1:" + port + "/current"),
          List.of(new WorkerHttpEgressTarget("http", "127.0.0.1", port)));

      var output = adapter.execute(command("New York & East"));

      assertEquals("location=New+York+%26+East", rawQuery.get());
      assertEquals("New York & East", output.get("location").asText());
      assertEquals("Cloudy", output.get("condition").asText());
      assertEquals(27.4, output.get("temperatureCelsius").asDouble());
      assertEquals(78, output.get("humidityPercent").asInt());
      assertEquals(12.6, output.get("windSpeedKph").asDouble());
      assertEquals("2026-06-24T11:55:00+08:00", output.get("observationTime").asText());
      assertEquals("weather-read-model", output.get("source").asText());
      assertEquals("2026-06-24T04:00:00Z", output.get("generatedAt").asText());
      assertTrue(output.get("ignoredInstruction") == null);
    } finally {
      server.stop(0);
    }
  }

  @Test
  void executesConfiguredWeatherReadWhenOptionalSourceIsOmitted() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/current", exchange -> {
      byte[] body = """
          {
            "location": "Shanghai",
            "condition": "Cloudy",
            "temperatureCelsius": 29.2,
            "humidityPercent": 71,
            "windSpeedKph": 9.8,
            "observationTime": "2026-06-24T14:30:00+08:00"
          }
          """.getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().add("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
    try {
      int port = server.getAddress().getPort();
      ConfiguredHttpReadOnlySkillAdapter adapter = adapter(
          skillWithoutSource("http://127.0.0.1:" + port + "/current"),
          List.of(new WorkerHttpEgressTarget("http", "127.0.0.1", port)));

      var output = adapter.execute(command("Shanghai"));

      assertEquals("Shanghai", output.get("location").asText());
      assertNull(output.get("source"));
      assertEquals("2026-06-24T04:00:00Z", output.get("generatedAt").asText());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void rejectsConfiguredSkillWhenHttpEgressTargetIsNotAllowed() {
    ConfiguredHttpReadOnlySkillAdapter adapter = adapter(skill("https://weather.internal/current"), List.of());
    RestrictedReadOnlyExecutionWorker worker = new RestrictedReadOnlyExecutionWorker(List.of(adapter), clock);

    var result = worker.execute(request("Shanghai", 60));

    assertEquals(WorkerExecutionStatus.REJECTED, result.status());
    assertEquals("HTTP_EGRESS_NOT_ALLOWED", result.errorCode());
  }

  @Test
  void rejectsConfiguredSkillWhenEndpointIsMissing() {
    ConfiguredHttpReadOnlySkillAdapter adapter = adapter(skill(""), List.of());
    RestrictedReadOnlyExecutionWorker worker = new RestrictedReadOnlyExecutionWorker(List.of(adapter), clock);

    var result = worker.execute(request("Shanghai", 60));

    assertEquals(WorkerExecutionStatus.REJECTED, result.status());
    assertEquals("HTTP_SKILL_SOURCE_NOT_CONFIGURED", result.errorCode());
  }

  @Test
  void rejectsBlankRequiredInputBeforeHttpCall() {
    ConfiguredHttpReadOnlySkillAdapter adapter = adapter(
        skill("https://weather.internal/current"),
        List.of(new WorkerHttpEgressTarget("https", "weather.internal", 443)));
    RestrictedReadOnlyExecutionWorker worker = new RestrictedReadOnlyExecutionWorker(List.of(adapter), clock);

    var result = worker.execute(request(" ", 60));

    assertEquals(WorkerExecutionStatus.REJECTED, result.status());
    assertEquals("INVALID_PARAMETERS", result.errorCode());
    assertTrue(result.errorMessage().contains("location"));
  }

  private ConfiguredHttpReadOnlySkillAdapter adapter(
      ConfiguredHttpReadOnlySkillProperties.Skill skill,
      List<WorkerHttpEgressTarget> allowedTargets) {
    ConfiguredHttpReadOnlySkillProperties properties = new ConfiguredHttpReadOnlySkillProperties();
    properties.setSkills(List.of(skill));
    return new ConfiguredHttpReadOnlySkillAdapter(
        HttpClient.newHttpClient(),
        objectMapper,
        clock,
        new WorkerHttpEgressPolicy(allowedTargets),
        properties);
  }

  private ConfiguredHttpReadOnlySkillProperties.Skill skill(String endpointUrl) {
    ConfiguredHttpReadOnlySkillProperties.Skill skill = new ConfiguredHttpReadOnlySkillProperties.Skill();
    skill.setSkillId("weather-current-read");
    skill.setVersion("1.0.0");
    skill.setEndpointUrl(endpointUrl);
    skill.setInputParameterName("location");
    skill.setQueryParameterName("location");
    skill.setSource("weather-read-model");
    skill.setTimeout(Duration.ofSeconds(2));
    skill.setAllowedResponseFields(List.of(
        "location",
        "condition",
        "temperatureCelsius",
        "humidityPercent",
        "windSpeedKph",
        "observationTime"));
    return skill;
  }

  private ConfiguredHttpReadOnlySkillProperties.Skill skillWithoutSource(String endpointUrl) {
    ConfiguredHttpReadOnlySkillProperties.Skill skill = new ConfiguredHttpReadOnlySkillProperties.Skill();
    skill.setSkillId("weather-current-read");
    skill.setVersion("1.0.0");
    skill.setEndpointUrl(endpointUrl);
    skill.setInputParameterName("location");
    skill.setQueryParameterName("location");
    skill.setTimeout(Duration.ofSeconds(2));
    skill.setAllowedResponseFields(List.of(
        "location",
        "condition",
        "temperatureCelsius",
        "humidityPercent",
        "windSpeedKph",
        "observationTime"));
    return skill;
  }

  private WorkerExecutionRequest request(String location, long expiresInSeconds) {
    OffsetDateTime now = OffsetDateTime.now(clock);
    return new WorkerExecutionRequest(
        "1.0",
        "execution-1",
        now,
        now.plusSeconds(expiresInSeconds),
        command(location));
  }

  private ReadOnlyCommandEnvelope command(String location) {
    OffsetDateTime now = OffsetDateTime.now(clock);
    var parameters = objectMapper.createObjectNode().put("location", location);
    return new ReadOnlyCommandEnvelope(
        "1.0",
        "command-1",
        "workflow-1",
        "idempotency-1",
        "READ_ONLY",
        "development",
        new SkillReference(
            "weather-current-read",
            "1.0.0",
            "weather-current-read:1.0.0:input",
            "weather-current-read:1.0.0:output"),
        parameters,
        new OperatorContext("operator-1", List.of("ROLE_ops-reader")),
        new PolicyDecisionReference("decision-1", "policy-v1", "ALLOW"),
        new TraceContext("trace-1", "request-1"),
        now);
  }
}
