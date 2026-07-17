package com.company.opsagent.executionworker;

import com.company.opsagent.contracts.workflow.OperatorContext;
import com.company.opsagent.contracts.workflow.PolicyDecisionReference;
import com.company.opsagent.contracts.workflow.ReadOnlyCommandEnvelope;
import com.company.opsagent.contracts.workflow.SkillReference;
import com.company.opsagent.contracts.workflow.TraceContext;
import com.company.opsagent.contracts.workflow.WorkerExecutionRequest;
import com.company.opsagent.contracts.workflow.WorkerRequestSignature;
import com.company.opsagent.contracts.workflow.WorkerTransportHeaders;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 验证独立 Worker HTTP 边界仅接受版本化只读执行请求。
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "ops-agent.worker.transport-auth.enabled=true",
        "ops-agent.worker.transport-auth.key-id=worker-test-key",
        "ops-agent.worker.transport-auth.max-clock-skew=30s"
    })
class WorkerExecutionControllerTest {

  private static final String KEY_ID = "worker-test-key";
  private static final String SHARED_SECRET = java.util.UUID.randomUUID().toString();

  @LocalServerPort
  private int port;

  @Autowired
  private ObjectMapper objectMapper;

  @DynamicPropertySource
  static void transportAuthProperties(DynamicPropertyRegistry registry) {
    registry.add("ops-agent.worker.transport-auth.shared-secret", () -> SHARED_SECRET);
  }

  /**
   * 验证合法签名的只读请求可以通过 Worker HTTP 边界并执行成功。
   */
  @Test
  void executesReadOnlyRequestThroughHttpBoundary() {
    var request = request("execution-http", "node-http");

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
        .jsonPath("$.output.nodeName").isEqualTo("node-http");
  }

  /**
   * 验证缺少签名头的请求会在 HTTP 边界被拒绝，不会进入执行器。
   */
  @Test
  void rejectsUnsignedRequestAtHttpBoundary() {
    WebTestClient.bindToServer()
        .baseUrl("http://127.0.0.1:" + port)
        .build()
        .post()
        .uri("/internal/executions/read-only")
        .bodyValue(request("execution-unsigned", "node-http"))
        .exchange()
        .expectStatus().isUnauthorized();
  }

  /**
   * 验证签名不匹配的请求会被拒绝，防止调用方伪造控制面请求。
   */
  @Test
  void rejectsRequestWithInvalidSignature() {
    var request = request("execution-invalid-signature", "node-http");
    WebTestClient.bindToServer()
        .baseUrl("http://127.0.0.1:" + port)
        .build()
        .post()
        .uri("/internal/executions/read-only")
        .headers(headers -> {
          headers.set(WorkerTransportHeaders.KEY_ID, KEY_ID);
          headers.set(WorkerTransportHeaders.TIMESTAMP, OffsetDateTime.now(ZoneOffset.UTC).toString());
          headers.set(WorkerTransportHeaders.SIGNATURE, "invalid-signature");
        })
        .bodyValue(request)
        .exchange()
        .expectStatus().isUnauthorized();
  }

  /**
   * 验证签名时间戳超出漂移窗口时请求会被拒绝，降低重放风险。
   */
  @Test
  void rejectsRequestWithStaleSignatureTimestamp() {
    var request = request("execution-stale-signature", "node-http");
    WebTestClient.bindToServer()
        .baseUrl("http://127.0.0.1:" + port)
        .build()
        .post()
        .uri("/internal/executions/read-only")
        .headers(headers -> sign(headers, "2026-01-01T00:00:00Z", request))
        .bodyValue(request)
        .exchange()
        .expectStatus().isUnauthorized();
  }

  private WorkerExecutionRequest request(String executionRequestId, String nodeName) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    var command = new ReadOnlyCommandEnvelope(
        "1.0",
        "command-" + executionRequestId,
        "workflow-http",
        "idempotency-" + executionRequestId,
        "READ_ONLY",
        "development",
        new SkillReference("node-health-read", "1.1.0", "node-health-input", "node-health-output"),
        objectMapper.createObjectNode().put("nodeName", nodeName),
        new OperatorContext("operator-http", List.of("ROLE_ops-reader")),
        new PolicyDecisionReference("decision-http", "policy-v1", "ALLOW"),
        new TraceContext("trace-http", "request-http"),
        now);
    return new WorkerExecutionRequest("1.0", executionRequestId, now, now.plusSeconds(30), command);
  }

  private void sign(HttpHeaders headers, String timestamp, WorkerExecutionRequest request) {
    String payload = WorkerRequestSignature.canonicalPayload(KEY_ID, timestamp, request);
    headers.set(WorkerTransportHeaders.KEY_ID, KEY_ID);
    headers.set(WorkerTransportHeaders.TIMESTAMP, timestamp);
    headers.set(WorkerTransportHeaders.SIGNATURE, WorkerRequestSignature.sign(SHARED_SECRET, payload));
  }
}
