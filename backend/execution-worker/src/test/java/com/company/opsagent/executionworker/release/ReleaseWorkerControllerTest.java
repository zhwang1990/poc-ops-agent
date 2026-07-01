package com.company.opsagent.executionworker.release;

import com.company.opsagent.contracts.workflow.OperatorContext;
import com.company.opsagent.contracts.workflow.PolicyDecisionReference;
import com.company.opsagent.contracts.workflow.TraceContext;
import com.company.opsagent.contracts.workflow.WorkerRequestSignature;
import com.company.opsagent.contracts.workflow.WorkerTransportHeaders;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "ops-agent.worker.transport-auth.enabled=true",
        "ops-agent.worker.transport-auth.key-id=worker-test-key",
        "ops-agent.worker.transport-auth.shared-secret=worker-transport-test-key-material",
        "ops-agent.worker.transport-auth.max-clock-skew=30s"
    })
class ReleaseWorkerControllerTest {

  private static final String KEY_ID = "worker-test-key";
  private static final String SHARED_SECRET = "worker-transport-test-key-material";

  @LocalServerPort
  private int port;

  @Test
  void rejectsProductionEnvironment() {
    ReleaseWorkerRequest request = request("prod", "TOMCAT_WAR_UPLOAD", OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(30));

    post(request)
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.status").isEqualTo("REJECTED")
        .jsonPath("$.errorCode").isEqualTo("TARGET_ENVIRONMENT_NOT_ALLOWED");
  }

  @Test
  void rejectsDisabledManagementMode() {
    ReleaseWorkerRequest request = request("dev", "DISABLED", OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(30));

    post(request)
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.status").isEqualTo("REJECTED")
        .jsonPath("$.errorCode").isEqualTo("SERVER_MANAGEMENT_MODE_DISABLED");
  }

  @Test
  void returnsStableErrorWhenAdapterIsNotRegistered() {
    ReleaseWorkerRequest request = request("dev", "TOMCAT_MANAGER_API", OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(30));

    post(request)
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.status").isEqualTo("REJECTED")
        .jsonPath("$.errorCode").isEqualTo("SERVER_MANAGEMENT_MODE_NOT_CONFIGURED");
  }

  @Test
  void rejectsExpiredReleaseRequest() {
    ReleaseWorkerRequest request = request("dev", "TOMCAT_WAR_UPLOAD", OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(30));

    post(request)
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.status").isEqualTo("REJECTED")
        .jsonPath("$.errorCode").isEqualTo("RELEASE_REQUEST_EXPIRED");
  }

  private WebTestClient.ResponseSpec post(ReleaseWorkerRequest request) {
    String timestamp = OffsetDateTime.now(ZoneOffset.UTC).toString();
    return WebTestClient.bindToServer()
        .baseUrl("http://127.0.0.1:" + port)
        .build()
        .post()
        .uri("/internal/release/execute")
        .headers(headers -> sign(headers, timestamp, request))
        .bodyValue(request)
        .exchange();
  }

  private ReleaseWorkerRequest request(String targetEnvironment, String managementMode, OffsetDateTime expiresAt) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    var command = new ReleaseWorkerRequest.ReleaseCommand(
        "1.0",
        "rel-1",
        "550e8400-e29b-41d4-a716-446655440000",
        "DEPLOY",
        targetEnvironment,
        "orders",
        new ReleaseWorkerRequest.ReleaseArtifactReference("artifact-1", "WAR", "sha256:abc123"),
        List.of(new ReleaseWorkerRequest.ReleaseNodeTarget("node-1", "TOMCAT", managementMode)),
        new OperatorContext("operator-release", List.of("ROLE_ops-release")),
        new PolicyDecisionReference("decision-release", "policy-v1", "ALLOW"),
        new TraceContext("trace-release", "request-release"),
        now);
    return new ReleaseWorkerRequest("1.0", "550e8400-e29b-41d4-a716-446655440001", now, expiresAt, command);
  }

  private void sign(HttpHeaders headers, String timestamp, ReleaseWorkerRequest request) {
    String payload = ReleaseWorkerRequestSignature.canonicalPayload(KEY_ID, timestamp, request);
    headers.set(WorkerTransportHeaders.KEY_ID, KEY_ID);
    headers.set(WorkerTransportHeaders.TIMESTAMP, timestamp);
    headers.set(WorkerTransportHeaders.SIGNATURE, WorkerRequestSignature.sign(SHARED_SECRET, payload));
  }
}
