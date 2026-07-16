package com.company.opsagent.contracts.workflow;

import com.company.opsagent.contracts.sqlworkbench.SqlConnectionSummary;
import com.company.opsagent.contracts.sqlworkbench.SqlControlledDmlExecutionRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlCommitRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlConfirmation;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlExecutionBinding;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlPreflightExecutionRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlPreviewSelection;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryExecutionRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlTypedParameter;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Worker 执行请求的应用层传输签名工具。
 *
 * <p>该工具只负责跨控制面和 Worker 共享的 canonical payload 与 HMAC 计算规则，不负责授权决策。
 */
public final class WorkerRequestSignature {

  private static final String SIGNATURE_VERSION = "ops-agent-worker-signature-v1";
  private static final String HMAC_ALGORITHM = "HmacSHA256";

  private WorkerRequestSignature() {
  }

  /**
   * 构造版本化 canonical payload。
   *
   * <p>payload 绑定请求核心字段与参数摘要，使签名能够覆盖执行请求身份、生命周期和主要执行内容。
   */
  public static String canonicalPayload(String keyId, String timestamp, WorkerExecutionRequest request) {
    requireText(keyId, "keyId");
    requireText(timestamp, "timestamp");
    if (request == null) {
      throw new IllegalArgumentException("request is required");
    }
    ReadOnlyCommandEnvelope command = request.command();
    SkillReference skill = command.skill();
    OperatorContext operator = command.operator();
    PolicyDecisionReference policyDecision = command.policyDecision();
    TraceContext trace = command.trace();
    return String.join("\n",
        SIGNATURE_VERSION,
        keyId,
        timestamp,
        request.contractVersion(),
        request.executionRequestId(),
        request.authorizedAt().toString(),
        request.expiresAt().toString(),
        command.contractVersion(),
        command.commandId(),
        command.workflowId(),
        command.idempotencyKey(),
        command.operationClass(),
        command.targetEnvironment(),
        skill.skillId(),
        skill.version(),
        skill.parameterSchemaId(),
        skill.outputSchemaId(),
        operator.operatorId(),
        String.join(",", operator.roles()),
        policyDecision.decisionId(),
        policyDecision.policyVersion(),
        policyDecision.decision(),
        trace.traceId(),
        trace.requestId(),
        sha256Hex(command.parameters().toString()));
  }

  /**
   * 构造 SQL 工作台只读查询执行请求的 canonical payload。
   */
  public static String canonicalSqlPayload(String keyId, String timestamp, SqlQueryExecutionRequest request) {
    requireText(keyId, "keyId");
    requireText(timestamp, "timestamp");
    if (request == null) {
      throw new IllegalArgumentException("request is required");
    }
    SqlQueryRequest query = request.query();
    OperatorContext operator = request.operator();
    PolicyDecisionReference policyDecision = request.policyDecision();
    TraceContext trace = request.trace();
    return String.join("\n",
        SIGNATURE_VERSION,
        "sql-query-execution-v1",
        keyId,
        timestamp,
        request.contractVersion(),
        request.executionRequestId(),
        request.workflowId(),
        request.validationHash(),
        request.expiresAt().toString(),
        query.contractVersion(),
        query.connectionId(),
        query.targetEnvironment(),
        query.schema(),
        query.action().name(),
        query.idempotencyKey(),
        String.valueOf(query.limits().maxRows()),
        String.valueOf(query.limits().maxBytes()),
        String.valueOf(query.limits().timeoutSeconds()),
        operator.operatorId(),
        String.join(",", operator.roles()),
        policyDecision.decisionId(),
        policyDecision.policyVersion(),
        policyDecision.decision(),
        trace.traceId(),
        trace.requestId(),
        sha256Hex(query.sql()),
        sha256Hex(query.parameters().toString()));
  }

  /**
   * 构造 SQL 工作台 DML 预检执行请求的 canonical payload。
   */
  public static String canonicalSqlDmlPreflightPayload(
      String keyId,
      String timestamp,
      SqlDmlPreflightExecutionRequest request) {
    requireText(keyId, "keyId");
    requireText(timestamp, "timestamp");
    if (request == null) {
      throw new IllegalArgumentException("request is required");
    }
    SqlQueryRequest query = request.query();
    SqlDmlPreviewSelection previewSelection = request.previewSelection();
    OperatorContext operator = request.operator();
    PolicyDecisionReference policyDecision = request.policyDecision();
    TraceContext trace = request.trace();
    return canonicalFields(
        SIGNATURE_VERSION,
        "sql-dml-preflight-execution-v1",
        keyId,
        timestamp,
        request.contractVersion(),
        request.executionRequestId(),
        request.workflowId(),
        request.validationHash(),
        request.expiresAt().toString(),
        canonicalSqlQueryFields(query),
        previewSelection.contractVersion(),
        canonicalStringList(previewSelection.sampleColumns()),
        canonicalStringList(previewSelection.maskedSampleColumns()),
        operator.operatorId(),
        canonicalStringList(operator.roles()),
        policyDecision.decisionId(),
        policyDecision.policyVersion(),
        policyDecision.decision(),
        trace.traceId(),
        trace.requestId());
  }

  /**
   * 构造 SQL 工作台受控 DML 提交请求的 canonical payload。
   */
  public static String canonicalControlledSqlDmlPayload(
      String keyId,
      String timestamp,
      SqlControlledDmlExecutionRequest request) {
    requireText(keyId, "keyId");
    requireText(timestamp, "timestamp");
    if (request == null) {
      throw new IllegalArgumentException("request is required");
    }
    SqlDmlCommitRequest commitRequest = request.commitRequest();
    SqlDmlConfirmation confirmation = commitRequest.confirmation();
    SqlDmlExecutionBinding binding = request.binding();
    OperatorContext operator = request.operator();
    PolicyDecisionReference policyDecision = request.policyDecision();
    TraceContext trace = request.trace();
    return canonicalFields(
        SIGNATURE_VERSION,
        "sql-controlled-dml-execution-v1",
        keyId,
        timestamp,
        request.contractVersion(),
        request.executionRequestId(),
        request.workflowId(),
        request.expiresAt().toString(),
        commitRequest.contractVersion(),
        canonicalSqlQueryFields(commitRequest.query()),
        confirmation.contractVersion(),
        confirmation.sqlHash(),
        canonicalStringList(confirmation.confirmedRisks()),
        confirmation.confirmationCode(),
        binding.bindingHash(),
        binding.parametersHash(),
        binding.preflightHash(),
        binding.confirmationHash(),
        operator.operatorId(),
        canonicalStringList(operator.roles()),
        policyDecision.decisionId(),
        policyDecision.policyVersion(),
        policyDecision.decision(),
        trace.traceId(),
        trace.requestId());
  }

  /**
   * 构造 SQL 工作台结果页读取请求的 canonical payload。
   */
  public static String canonicalSqlResultReadPayload(String keyId, String timestamp, String resultId) {
    requireText(keyId, "keyId");
    requireText(timestamp, "timestamp");
    resultId = requireCanonicalText(resultId, "resultId");
    return String.join("\n",
        SIGNATURE_VERSION,
        "sql-result-read-v1",
        keyId,
        timestamp,
        resultId);
  }

  /**
   * 构造 SQL 工作台连接探测请求的 canonical payload。
   */
  public static String canonicalSqlConnectionProbePayload(
      String keyId,
      String timestamp,
      SqlConnectionSummary connection) {
    requireText(keyId, "keyId");
    requireText(timestamp, "timestamp");
    if (connection == null) {
      throw new IllegalArgumentException("connection is required");
    }
    return String.join("\n",
        SIGNATURE_VERSION,
        "sql-connection-probe-v1",
        keyId,
        timestamp,
        connection.contractVersion(),
        connection.connectionId(),
        connection.targetEnvironment(),
        connection.platformType(),
        connection.host(),
        String.valueOf(connection.port()),
        connection.defaultSchema(),
        String.join(",", connection.allowedSchemas()),
        connection.credentialAlias());
  }

  /**
   * 构造 SQL 工作台数据库元数据读取请求的 canonical payload。
   */
  public static String canonicalSqlMetadataPayload(
      String keyId,
      String timestamp,
      SqlConnectionSummary connection,
      String schema) {
    requireText(keyId, "keyId");
    requireText(timestamp, "timestamp");
    if (connection == null) {
      throw new IllegalArgumentException("connection is required");
    }
    schema = requireCanonicalText(schema, "schema");
    return String.join("\n",
        SIGNATURE_VERSION,
        "sql-metadata-read-v1",
        keyId,
        timestamp,
        connection.contractVersion(),
        connection.connectionId(),
        connection.targetEnvironment(),
        connection.platformType(),
        connection.host(),
        String.valueOf(connection.port()),
        connection.defaultSchema(),
        String.join(",", connection.allowedSchemas()),
        connection.credentialAlias(),
        schema);
  }

  /**
   * 使用 HMAC-SHA256 对 canonical payload 签名，返回 Base64 字符串。
   */
  public static String sign(String sharedSecret, String canonicalPayload) {
    requireText(sharedSecret, "sharedSecret");
    requireText(canonicalPayload, "canonicalPayload");
    try {
      Mac mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(new SecretKeySpec(sharedSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
      return Base64.getEncoder().encodeToString(mac.doFinal(canonicalPayload.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception exception) {
      throw new IllegalStateException("failed to sign worker request", exception);
    }
  }

  /**
   * 常量时间比较签名，避免通过比较耗时泄露签名细节。
   */
  public static boolean matches(String expected, String actual) {
    if (expected == null || actual == null) {
      return false;
    }
    return MessageDigest.isEqual(
        expected.getBytes(StandardCharsets.UTF_8),
        actual.getBytes(StandardCharsets.UTF_8));
  }

  private static String sha256Hex(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder builder = new StringBuilder(bytes.length * 2);
      for (byte current : bytes) {
        builder.append(String.format("%02x", current));
      }
      return builder.toString();
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is not available", exception);
    }
  }

  private static String canonicalSqlQueryFields(SqlQueryRequest query) {
    return canonicalFields(
        query.contractVersion(),
        query.connectionId(),
        query.targetEnvironment(),
        query.schema(),
        query.action().name(),
        query.idempotencyKey(),
        String.valueOf(query.limits().maxRows()),
        String.valueOf(query.limits().maxBytes()),
        String.valueOf(query.limits().timeoutSeconds()),
        sha256Hex(query.sql()),
        sha256Hex(canonicalSqlParameters(query.parameters())));
  }

  private static String canonicalSqlParameters(List<SqlTypedParameter> parameters) {
    List<String> canonicalParameters = new ArrayList<>(parameters.size());
    for (SqlTypedParameter parameter : parameters) {
      canonicalParameters.add(canonicalFields(
          parameter.name(),
          parameter.type(),
          canonicalJson(parameter.value())));
    }
    return canonicalStringList(canonicalParameters);
  }

  private static String canonicalJson(JsonNode value) {
    if (value.isObject()) {
      List<String> fieldNames = new ArrayList<>();
      value.fieldNames().forEachRemaining(fieldNames::add);
      fieldNames.sort(String::compareTo);
      List<String> fields = new ArrayList<>(fieldNames.size());
      for (String fieldName : fieldNames) {
        fields.add(canonicalFields(fieldName, canonicalJson(value.get(fieldName))));
      }
      return canonicalFields("OBJECT", canonicalStringList(fields));
    }
    if (value.isArray()) {
      List<String> elements = new ArrayList<>(value.size());
      for (JsonNode element : value) {
        elements.add(canonicalJson(element));
      }
      return canonicalFields("ARRAY", canonicalStringList(elements));
    }
    return canonicalFields(value.getNodeType().name(), value.toString());
  }

  private static String canonicalStringList(List<String> values) {
    StringBuilder payload = new StringBuilder();
    appendCanonicalField(payload, String.valueOf(values.size()));
    for (String value : values) {
      appendCanonicalField(payload, value);
    }
    return payload.toString();
  }

  private static String canonicalFields(String... values) {
    StringBuilder payload = new StringBuilder();
    for (String value : values) {
      appendCanonicalField(payload, value);
    }
    return payload.toString();
  }

  private static void appendCanonicalField(StringBuilder payload, String value) {
    payload.append(value.length()).append(':').append(value);
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
  }

  private static String requireCanonicalText(String value, String name) {
    requireText(value, name);
    return value;
  }
}
