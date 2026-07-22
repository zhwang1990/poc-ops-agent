package com.company.opsagent.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.opsagent.contracts.workflow.OperatorContext;
import com.company.opsagent.contracts.workflow.PolicyDecisionReference;
import com.company.opsagent.contracts.workflow.ReadOnlyCommandEnvelope;
import com.company.opsagent.contracts.workflow.SkillReference;
import com.company.opsagent.contracts.workflow.TraceContext;
import com.company.opsagent.contracts.workflow.WorkerExecutionRequest;
import com.company.opsagent.contracts.workflow.WorkerRequestSignature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 验证控制面与 Worker 共享的传输签名契约，确保请求核心字段和参数摘要被绑定到签名。
 */
class WorkerRequestSignatureTest {

  private static final String KEY_ID = "worker-key-a";
  private static final String TIMESTAMP = "2026-06-23T10:15:30Z";
  private static final String SECRET = UUID.randomUUID().toString();
  private final ObjectMapper objectMapper = new ObjectMapper();

  /**
   * 验证同一请求在相同 Key ID 和时间戳下生成稳定签名。
   */
  @Test
  void createsStableSignatureForSameRequest() {
    WorkerExecutionRequest request = request("node-a");

    String canonicalPayload = WorkerRequestSignature.canonicalPayload(KEY_ID, TIMESTAMP, request);
    String first = WorkerRequestSignature.sign(SECRET, canonicalPayload);
    String second = WorkerRequestSignature.sign(SECRET, canonicalPayload);

    assertEquals(first, second);
    assertTrue(WorkerRequestSignature.matches(first, second));
  }

  /**
   * 验证参数被纳入签名摘要，防止请求体参数被篡改后仍复用旧签名。
   */
  @Test
  void changesSignatureWhenParametersChange() {
    String firstPayload = WorkerRequestSignature.canonicalPayload(KEY_ID, TIMESTAMP, request("node-a"));
    String secondPayload = WorkerRequestSignature.canonicalPayload(KEY_ID, TIMESTAMP, request("node-b"));

    assertNotEquals(
        WorkerRequestSignature.sign(SECRET, firstPayload),
        WorkerRequestSignature.sign(SECRET, secondPayload));
  }

  /**
   * 验证签名工具拒绝空密钥，避免误用无效配置生成可预测签名。
   */
  @Test
  void rejectsBlankSecret() {
    assertThrows(IllegalArgumentException.class, () -> WorkerRequestSignature.sign(" ", "payload"));
  }

  private WorkerExecutionRequest request(String nodeName) {
    OffsetDateTime authorizedAt = OffsetDateTime.parse("2026-06-23T10:15:00Z");
    OffsetDateTime expiresAt = OffsetDateTime.parse("2026-06-23T10:15:30Z");
    var parameters = objectMapper.createObjectNode().put("nodeName", nodeName);
    var command = new ReadOnlyCommandEnvelope(
        "1.0",
        "command-1",
        "workflow-1",
        "idempotency-1",
        "READ_ONLY",
        "development",
        new SkillReference("node-health-read", "1.1.0", "node-health-input", "node-health-output"),
        parameters,
        new OperatorContext("operator-1", List.of("ROLE_ops-reader")),
        new PolicyDecisionReference("decision-1", "policy-v1", "ALLOW"),
        new TraceContext("trace-1", "request-1"),
        authorizedAt);
    return new WorkerExecutionRequest("1.0", "execution-1", authorizedAt, expiresAt, command);
  }
}
