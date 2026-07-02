package com.company.opsagent.controlplane.bootstrap.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.opsagent.contracts.workflow.OperatorContext;
import com.company.opsagent.contracts.workflow.PolicyDecisionReference;
import com.company.opsagent.contracts.workflow.ReadOnlyCommandEnvelope;
import com.company.opsagent.contracts.workflow.SkillReference;
import com.company.opsagent.contracts.workflow.TraceContext;
import com.company.opsagent.contracts.workflow.WorkerExecutionRequest;
import com.company.opsagent.contracts.workflow.WorkerRequestSignature;
import com.company.opsagent.contracts.workflow.WorkerTransportHeaders;
import com.company.opsagent.controlplane.bootstrap.config.WorkerProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * 验证控制面调用 Worker 时按配置注入传输认证签名头。
 */
class WebClientWorkerGatewayTest {

  private static final String KEY_ID = "worker-key-a";
  private static final String SHARED_SECRET = "worker-transport-test-key-material";
  private static final Instant SIGNED_AT = Instant.parse("2026-06-23T10:15:30Z");

  private final ObjectMapper objectMapper = new ObjectMapper();

  /**
   * 验证启用 Worker 传输认证时，控制面网关会为出站请求注入签名头。
   */
  @Test
  void addsTransportSignatureHeadersWhenEnabled() {
    AtomicReference<ClientRequest> captured = new AtomicReference<>();
    WebClientWorkerGateway gateway = new WebClientWorkerGateway(
        webClient(captured),
        workerProperties(true),
        Clock.fixed(SIGNED_AT, ZoneOffset.UTC));
    WorkerExecutionRequest request = request();

    gateway.execute(request).block();

    ClientRequest sent = captured.get();
    assertNotNull(sent);
    assertEquals(KEY_ID, sent.headers().getFirst(WorkerTransportHeaders.KEY_ID));
    assertEquals("2026-06-23T10:15:30Z", sent.headers().getFirst(WorkerTransportHeaders.TIMESTAMP));
    String signature = sent.headers().getFirst(WorkerTransportHeaders.SIGNATURE);
    String payload = WorkerRequestSignature.canonicalPayload(
        KEY_ID,
        "2026-06-23T10:15:30Z",
        request);
    assertTrue(WorkerRequestSignature.matches(WorkerRequestSignature.sign(SHARED_SECRET, payload), signature));
  }

  /**
   * 验证本地开发禁用传输认证时，控制面网关不会发送签名头。
   */
  @Test
  void omitsTransportSignatureHeadersWhenDisabled() {
    AtomicReference<ClientRequest> captured = new AtomicReference<>();
    WebClientWorkerGateway gateway = new WebClientWorkerGateway(
        webClient(captured),
        workerProperties(false),
        Clock.fixed(SIGNED_AT, ZoneOffset.UTC));

    gateway.execute(request()).block();

    ClientRequest sent = captured.get();
    assertNotNull(sent);
    assertFalse(sent.headers().containsKey(WorkerTransportHeaders.KEY_ID));
    assertFalse(sent.headers().containsKey(WorkerTransportHeaders.TIMESTAMP));
    assertFalse(sent.headers().containsKey(WorkerTransportHeaders.SIGNATURE));
  }

  @Test
  void sendsWorkerRequestAsJson() {
    AtomicReference<ClientRequest> captured = new AtomicReference<>();
    WebClientWorkerGateway gateway = new WebClientWorkerGateway(
        webClient(captured),
        workerProperties(false),
        Clock.fixed(SIGNED_AT, ZoneOffset.UTC));

    gateway.execute(request()).block();

    ClientRequest sent = captured.get();
    assertNotNull(sent);
    assertEquals(MediaType.APPLICATION_JSON, sent.headers().getContentType());
  }

  private WebClient webClient(AtomicReference<ClientRequest> captured) {
    return WebClient.builder()
        .exchangeFunction(request -> {
          captured.set(request);
          return Mono.just(ClientResponse.create(HttpStatus.OK)
              .header("Content-Type", "application/json")
              .body("""
                  {
                    "contractVersion": "1.0",
                    "executionRequestId": "execution-1",
                    "commandId": "command-1",
                    "workflowId": "workflow-1",
                    "status": "SUCCEEDED",
                    "outputSchemaId": "node-health-output",
                    "output": {"status": "HEALTHY"},
                    "errorCode": null,
                    "errorMessage": null,
                    "completedAt": "2026-06-23T10:15:31Z"
                  }
                  """)
              .build());
        })
        .build();
  }

  private WorkerProperties workerProperties(boolean enabled) {
    WorkerProperties properties = new WorkerProperties();
    properties.getTransportAuth().setEnabled(enabled);
    properties.getTransportAuth().setKeyId(KEY_ID);
    properties.getTransportAuth().setSharedSecret(SHARED_SECRET);
    return properties;
  }

  private WorkerExecutionRequest request() {
    OffsetDateTime authorizedAt = OffsetDateTime.parse("2026-06-23T10:15:00Z");
    OffsetDateTime expiresAt = OffsetDateTime.parse("2026-06-23T10:16:00Z");
    var command = new ReadOnlyCommandEnvelope(
        "1.0",
        "command-1",
        "workflow-1",
        "idempotency-1",
        "READ_ONLY",
        "development",
        new SkillReference("node-health-read", "1.1.0", "node-health-input", "node-health-output"),
        objectMapper.createObjectNode().put("nodeName", "node-a"),
        new OperatorContext("operator-1", List.of("ROLE_ops-reader")),
        new PolicyDecisionReference("decision-1", "policy-v1", "ALLOW"),
        new TraceContext("trace-1", "request-1"),
        authorizedAt);
    return new WorkerExecutionRequest("1.0", "execution-1", authorizedAt, expiresAt, command);
  }
}
