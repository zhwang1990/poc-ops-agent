package com.company.opsagent.contracts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.opsagent.contracts.agent.AgentTaskResult;
import com.company.opsagent.contracts.agent.AgentToolResult;
import com.company.opsagent.contracts.events.AgentRuntimeProgressPayload;
import com.company.opsagent.contracts.events.AgentRuntimeProgressPayloadKind;
import com.company.opsagent.contracts.events.AgentToolCallCompletedPayload;
import com.company.opsagent.contracts.events.AgentToolCallRejectedPayload;
import com.company.opsagent.contracts.events.AgentToolCallRequestedPayload;
import com.company.opsagent.contracts.events.SemanticEvent;
import com.company.opsagent.contracts.events.SemanticEventType;
import com.company.opsagent.contracts.events.WorkflowStartedPayload;
import com.company.opsagent.contracts.sqlworkbench.SqlAssistantAction;
import com.company.opsagent.contracts.sqlworkbench.SqlAssistantRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlAssistantResponse;
import com.company.opsagent.contracts.sqlworkbench.SqlAssistantStatus;
import com.company.opsagent.contracts.sqlworkbench.SqlAssistantSuggestion;
import com.company.opsagent.contracts.sqlworkbench.SqlConnectionCreateRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlConnectionUpdateRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryAction;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryLimits;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryRequest;
import com.company.opsagent.contracts.workflow.OperatorContext;
import com.company.opsagent.contracts.workflow.PolicyDecisionReference;
import com.company.opsagent.contracts.workflow.ReadOnlyCommandEnvelope;
import com.company.opsagent.contracts.workflow.SkillReference;
import com.company.opsagent.contracts.workflow.TraceContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;

/**
 * 验证跨模块契约在信任边界拒绝写操作和不一致事件。
 */
class ContractsTest {

  @Test
  void rejectsNonReadOnlyCommand() {
    assertThrows(IllegalArgumentException.class, () -> new ReadOnlyCommandEnvelope(
        "1.0",
        "command-1",
        "workflow-1",
        "idempotency-1",
        "WRITE",
        "development",
        new SkillReference("node-health-read", "1.1.0", "input", "output"),
        new ObjectMapper().createObjectNode(),
        new OperatorContext("operator-1", List.of("ROLE_ops-reader")),
        new PolicyDecisionReference("decision-1", "policy-v1", "ALLOW"),
        new TraceContext("trace-1", "request-1"),
        OffsetDateTime.now()));
  }

  @Test
  void rejectsMismatchedSemanticEventPayload() {
    assertThrows(IllegalArgumentException.class, () -> new SemanticEvent(
        "1.0",
        "event-1",
        "workflow-1",
        1,
        OffsetDateTime.now(),
        SemanticEventType.SKILL_ROUTED,
        new WorkflowStartedPayload(SemanticEventType.WORKFLOW_STARTED, "command-1", "operator-1")));
  }

  @Test
  void acceptsAgentToolSemanticEventPayloadsAndSchemaTypes() throws Exception {
    OffsetDateTime now = OffsetDateTime.now();
    String workflowId = "11111111-1111-4111-8111-111111111111";
    new SemanticEvent(
        "1.0",
        "22222222-2222-4222-8222-222222222222",
        workflowId,
        2,
        now,
        SemanticEventType.AGENT_TOOL_CALL_REQUESTED,
        new AgentToolCallRequestedPayload(
            SemanticEventType.AGENT_TOOL_CALL_REQUESTED,
            "tool-call-1",
            1,
            "node-health",
            "1.0.0",
            "node-health:1.0.0:input",
            "development",
            "sha256:abc123"));
    new SemanticEvent(
        "1.0",
        "33333333-3333-4333-8333-333333333333",
        workflowId,
        3,
        now,
        SemanticEventType.AGENT_TOOL_CALL_COMPLETED,
        new AgentToolCallCompletedPayload(
            SemanticEventType.AGENT_TOOL_CALL_COMPLETED,
            "tool-call-1",
            1,
            "node-health",
            "1.0.0",
            "SUCCEEDED",
            "node-health:1.0.0:output"));
    new SemanticEvent(
        "1.0",
        "44444444-4444-4444-8444-444444444444",
        workflowId,
        4,
        now,
        SemanticEventType.AGENT_TOOL_CALL_REJECTED,
        new AgentToolCallRejectedPayload(
            SemanticEventType.AGENT_TOOL_CALL_REJECTED,
            "tool-call-2",
            2,
            "restart-node",
            "1.0.0",
            "POLICY_DENIED",
            "operator is not allowed",
            "policy-v1:workflow-1:tool-call-2"));

    JsonNode schema = new ObjectMapper()
        .readTree(Path.of("events/semantic-event-v1.schema.json").toFile());
    List<String> typeEnum = StreamSupport.stream(
            schema.path("properties").path("type").path("enum").spliterator(),
            false)
        .map(JsonNode::asText)
        .toList();
    assertTrue(typeEnum.contains("AGENT_TOOL_CALL_REQUESTED"));
    assertTrue(typeEnum.contains("AGENT_TOOL_CALL_COMPLETED"));
    assertTrue(typeEnum.contains("AGENT_TOOL_CALL_REJECTED"));
  }

  @Test
  void acceptsAgentRuntimeProgressSemanticEventWithoutSdkEventNames() throws Exception {
    OffsetDateTime now = OffsetDateTime.now();
    new SemanticEvent(
        "1.0",
        "55555555-5555-4555-8555-555555555555",
        "11111111-1111-4111-8111-111111111111",
        10_001,
        now,
        SemanticEventType.AGENT_RUNTIME_PROGRESS,
        new AgentRuntimeProgressPayload(
            SemanticEventType.AGENT_RUNTIME_PROGRESS,
            AgentRuntimeProgressPayloadKind.MODEL_CALL_COMPLETED,
            "model call completed",
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            12,
            5,
            17,
            0.42,
            false));

    JsonNode schema = new ObjectMapper()
        .readTree(Path.of("events/semantic-event-v1.schema.json").toFile());
    List<String> typeEnum = StreamSupport.stream(
            schema.path("properties").path("type").path("enum").spliterator(),
            false)
        .map(JsonNode::asText)
        .toList();
    assertTrue(typeEnum.contains("AGENT_RUNTIME_PROGRESS"));
    assertNoSchemaPropertyNamed(schema, Set.of("sourceEventType", "delta", "thinking"));
  }

  @Test
  void rejectsProductionSqlWorkbenchRequests() {
    assertThrows(IllegalArgumentException.class, () -> new SqlQueryRequest(
        "1.0",
        "as400-production",
        "production",
        "ORDERS",
        SqlQueryAction.RUN_READ_ONLY,
        "select * from orders",
        List.of(),
        new SqlQueryLimits(100, 1_000_000, 30),
        "sql-query-1"));
  }

  @Test
  void rejectsProductionSqlAssistantRequests() {
    assertThrows(IllegalArgumentException.class, () -> new SqlAssistantRequest(
        "1.0",
        "as400-production",
        "production",
        "ORDERS",
        SqlAssistantAction.OPTIMIZE_SQL,
        "select * from orders",
        new SqlQueryLimits(100, 1_000_000, 30),
        null,
        "sql-assistant-1"));
  }

  @Test
  void successfulSqlAssistantResponsesRequireRevalidation() {
    assertThrows(IllegalArgumentException.class, () -> new SqlAssistantResponse(
        "1.0",
        SqlAssistantStatus.SUCCEEDED,
        SqlAssistantAction.OPTIMIZE_SQL,
        "Use explicit columns.",
        List.of(new SqlAssistantSuggestion("Limit columns", "Reduce returned data.", "select id from orders")),
        List.of("Validate before execution."),
        false,
        "provider:fingerprint"));
  }

  @Test
  void rejectsUnsafeSqlWorkbenchConnectionCreateRequests() {
    assertThrows(IllegalArgumentException.class, () -> connectionCreateRequest(
        "production",
        "as400-prod-readonly",
        List.of("ORDERS")));
    assertThrows(IllegalArgumentException.class, () -> connectionCreateRequest(
        "development",
        " ",
        List.of("ORDERS")));
    assertThrows(IllegalArgumentException.class, () -> connectionCreateRequest(
        "development",
        "as400-dev-readonly",
        List.of()));
  }

  @Test
  void acceptsConfiguredSqlWorkbenchPlatformTypes() {
    for (String platformType : List.of("DB2_FOR_I", "H2", "MYSQL", "h2", "mysql")) {
      SqlConnectionCreateRequest request = connectionCreateRequest(
          "development",
          "sql-dev-readonly",
          List.of("ORDERS"),
          platformType);

      assertEquals(platformType.toUpperCase(), request.platformType());
    }
  }

  @Test
  void acceptsSqlWorkbenchConnectionUpdateRequest() {
    SqlConnectionUpdateRequest request = new SqlConnectionUpdateRequest(
        "1.0",
        "AS/400 Reporting",
        "test",
        "DB2_FOR_I",
        "as400-reporting.internal",
        446,
        "REPORTING",
        List.of("REPORTING"),
        List.of(SqlQueryAction.VALIDATE, SqlQueryAction.RUN_READ_ONLY, SqlQueryAction.PREFLIGHT_DML),
        "as400-reporting-readonly",
        250,
        45);

    assertEquals("AS/400 Reporting", request.displayName());
    assertEquals("test", request.targetEnvironment());
    assertEquals("REPORTING", request.defaultSchema());
    assertEquals(250, request.maxRowsDefault());
  }

  @Test
  void sqlWorkbenchConnectionCreateSchemaRejectsSecretFields() throws Exception {
    JsonNode schema = new ObjectMapper()
        .readTree(Path.of("sqlworkbench/sql-connection-create-request-v1.schema.json").toFile());

    assertTrue(schema.path("additionalProperties").isBoolean());
    assertEquals(false, schema.path("additionalProperties").asBoolean());
    assertTrue(!schema.path("properties").has("password"));
    assertTrue(!schema.path("properties").has("username"));
    assertTrue(!schema.path("properties").has("jdbcUrl"));
    List<String> platformTypes = StreamSupport.stream(
            schema.path("properties").path("platformType").path("enum").spliterator(),
            false)
        .map(JsonNode::asText)
        .toList();
    assertEquals(List.of("DB2_FOR_I", "H2", "MYSQL"), platformTypes);
  }

  @Test
  void sqlWorkbenchConnectionUpdateSchemaRejectsSecretFields() throws Exception {
    JsonNode schema = new ObjectMapper()
        .readTree(Path.of("sqlworkbench/sql-connection-update-request-v1.schema.json").toFile());

    assertTrue(schema.path("additionalProperties").isBoolean());
    assertEquals(false, schema.path("additionalProperties").asBoolean());
    assertTrue(!schema.path("properties").has("password"));
    assertTrue(!schema.path("properties").has("username"));
    assertTrue(!schema.path("properties").has("jdbcUrl"));
  }

  @Test
  void sqlAssistantSchemasRejectSecretAndResultFields() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode requestSchema = mapper
        .readTree(Path.of("sqlworkbench/sql-assistant-request-v1.schema.json").toFile());
    JsonNode responseSchema = mapper
        .readTree(Path.of("sqlworkbench/sql-assistant-response-v1.schema.json").toFile());

    assertEquals(false, requestSchema.path("additionalProperties").asBoolean());
    assertEquals(false, responseSchema.path("additionalProperties").asBoolean());
    assertTrue(!requestSchema.path("properties").has("apiKey"));
    assertTrue(!requestSchema.path("properties").has("password"));
    assertTrue(!requestSchema.path("properties").has("rows"));
    assertTrue(!responseSchema.path("properties").has("apiKey"));
    assertTrue(!responseSchema.path("properties").has("providerResponseBody"));
    assertTrue(!responseSchema.path("properties").has("rows"));
  }

  @Test
  void releaseCommandSchemaRejectsProductionEnvironment() throws Exception {
    JsonNode schema = new ObjectMapper()
        .readTree(Path.of("release/release-command-v1.schema.json").toFile());

    List<String> targetEnvironments = enumValues(schema, "targetEnvironment");

    assertEquals(List.of("dev", "sit", "uat"), targetEnvironments);
    assertFalse(targetEnvironments.contains("prod"));
    assertFalse(targetEnvironments.contains("production"));
  }

  @Test
  void releaseCommandSchemaOnlyAllowsWarArtifacts() throws Exception {
    JsonNode schema = new ObjectMapper()
        .readTree(Path.of("release/release-command-v1.schema.json").toFile());

    JsonNode artifactType = schema.path("properties")
        .path("artifact")
        .path("properties")
        .path("type");

    assertEquals("WAR", artifactType.path("const").asText());
    assertFalse(artifactType.has("enum"));
  }

  @Test
  void releaseCommandSchemaAllowsArtifactlessLibertyScriptProfiles() throws Exception {
    JsonNode schema = new ObjectMapper()
        .readTree(Path.of("release/release-command-v1.schema.json").toFile());

    List<String> required = StreamSupport.stream(schema.path("required").spliterator(), false)
        .map(JsonNode::asText)
        .toList();
    assertFalse(required.contains("artifact"));

    List<String> artifactTypes = StreamSupport.stream(
            schema.path("properties").path("artifact").path("type").spliterator(),
            false)
        .map(JsonNode::asText)
        .toList();
    assertTrue(artifactTypes.containsAll(List.of("object", "null")));

    JsonNode nodeProperties = schema.path("properties").path("nodes").path("items").path("properties");
    List<String> managementModes = StreamSupport.stream(
            nodeProperties.path("managementMode").path("enum").spliterator(),
            false)
        .map(JsonNode::asText)
        .toList();
    assertTrue(managementModes.contains("LIBERTY_SCRIPT_PROFILE"));
    assertTrue(nodeProperties.has("scriptProfile"));
    JsonNode definition = nodeProperties.path("scriptProfile").path("properties").path("definition");
    assertEquals(List.of("object", "null"), StreamSupport.stream(definition.path("type").spliterator(), false)
        .map(JsonNode::asText)
        .toList());
    for (String fieldName : List.of(
        "executablePath",
        "arguments",
        "requiredParameters",
        "allowedParameters",
        "successExitCodes",
        "timeoutSeconds",
        "workingDirectory",
        "targetEnvironments",
        "approved",
        "enabled")) {
      assertTrue(definition.path("properties").has(fieldName), "missing script profile definition field: " + fieldName);
    }
  }

  @Test
  void releaseEventsSchemaRejectsCredentialPayloadFields() throws Exception {
    JsonNode schema = new ObjectMapper()
        .readTree(Path.of("release/release-events-v1.schema.json").toFile());

    assertNoSchemaPropertyNamed(schema, Set.of("credential", "credentials", "secret", "password"));
    assertTrue(enumValues(schema, "type").containsAll(List.of(
        "RELEASE_CREATED",
        "RELEASE_CONFIRMED",
        "RELEASE_NODE_STARTED",
        "RELEASE_NODE_COMPLETED",
        "RELEASE_NODE_FAILED",
        "RELEASE_PARTIAL_FAILED",
        "RELEASE_ROLLBACK_STARTED",
        "RELEASE_ROLLBACK_FAILED",
        "RELEASE_MANUAL_INTERVENTION_REQUIRED")));
  }

  @Test
  void releaseEventsSchemaRequiresAuditContext() throws Exception {
    JsonNode schema = new ObjectMapper()
        .readTree(Path.of("release/release-events-v1.schema.json").toFile());

    List<String> required = StreamSupport.stream(schema.path("required").spliterator(), false)
        .map(JsonNode::asText)
        .toList();
    assertTrue(required.contains("audit"));
    JsonNode audit = schema.path("properties").path("audit");
    for (String fieldName : List.of("action", "resource", "policyVersion", "result", "reason", "traceId", "requestId")) {
      assertTrue(audit.path("properties").has(fieldName), "missing audit field: " + fieldName);
    }
  }

  @Test
  void rejectsInvalidSqlQueryLimits() {
    assertThrows(IllegalArgumentException.class, () -> new SqlQueryLimits(0, 1_000_000, 30));
  }

  @Test
  void acceptsAgentRuntimeTaskStatuses() {
    // Agent 任务结果状态必须和 JSON Schema、前端 Zod Schema 保持一致。
    for (String status : List.of(
        "SUCCEEDED",
        "FAILED_TERMINAL",
        "REJECTED",
        "AGENT_RUNTIME_DISABLED",
        "AGENT_RUNTIME_NOT_CONFIGURED",
        "AGENT_RUNTIME_FAILED")) {
      new AgentTaskResult(
          "1.0",
          "task-" + status.toLowerCase().replace('_', '-'),
          "workflow-1",
          status,
          "diagnostic completed",
          0,
          OffsetDateTime.now());
    }
  }

  @Test
  void acceptsAgentTaskResultWithStructuredToolResults() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    JsonNode output = mapper.createObjectNode()
        .put("location", "Shanghai")
        .put("condition", "Sunny")
        .put("temperatureCelsius", 31.2);
    AgentToolResult toolResult = new AgentToolResult(
        "1.0",
        "tool-call-weather-1",
        "task-weather-1",
        "workflow-weather-1",
        "SUCCEEDED",
        "weather-current-read:1.0.0:output",
        output,
        null,
        null,
        OffsetDateTime.now());

    AgentTaskResult result = new AgentTaskResult(
        "1.0",
        "task-weather-1",
        "workflow-weather-1",
        "SUCCEEDED",
        "weather query completed",
        1,
        OffsetDateTime.now(),
        List.of(toolResult));

    assertEquals(1, result.toolResults().size());
    JsonNode schema = mapper.readTree(Path.of("agent/agent-task-result-v1.schema.json").toFile());
    assertTrue(schema.path("properties").has("toolResults"));
    assertTrue(StreamSupport.stream(schema.path("required").spliterator(), false)
        .map(JsonNode::asText)
        .anyMatch("toolResults"::equals));
  }

  @Test
  void rejectsObsoleteAgentTaskResultStatus() {
    // 旧的 FAILED 状态信息量不足，不能继续作为跨模块契约状态传递。
    assertThrows(IllegalArgumentException.class, () -> new AgentTaskResult(
        "1.0",
        "task-1",
        "workflow-1",
        "FAILED",
        "diagnostic failed",
        0,
        OffsetDateTime.now()));
  }

  @Test
  void providesWeatherCurrentSkillPackageForAgentScopeAndRegistry() throws Exception {
    Path skillPackage = Path.of("skills/packages/weather-current");
    Path agentScopeSkill = Path.of("../skills/weather-current/SKILL.md");

    assertTrue(Files.exists(agentScopeSkill));
    assertTrue(Files.exists(skillPackage.resolve("manifest.json")));
    assertTrue(Files.exists(skillPackage.resolve("manifest.signature.json")));
    assertTrue(Files.exists(skillPackage.resolve("input.schema.json")));
    assertTrue(Files.exists(skillPackage.resolve("output.schema.json")));
    assertTrue(Files.exists(skillPackage.resolve("tests/happy-path.json")));
    assertTrue(Files.exists(skillPackage.resolve("tests/invalid-parameters.json")));
    assertTrue(Files.exists(skillPackage.resolve("tests/policy-denied.json")));

    ObjectMapper mapper = new ObjectMapper();
    JsonNode manifest = mapper.readTree(skillPackage.resolve("manifest.json").toFile());
    assertTrue("weather-current-read".equals(manifest.path("skillId").asText()));
    assertTrue("1.0.0".equals(manifest.path("version").asText()));
    assertTrue(manifest.path("readOnly").asBoolean());
    assertTrue("READ_ONLY".equals(manifest.path("riskLevel").asText()));

    JsonNode inputSchema = mapper.readTree(skillPackage.resolve("input.schema.json").toFile());
    assertTrue(inputSchema.path("properties").has("location"));
    assertTrue(StreamSupport.stream(inputSchema.path("required").spliterator(), false)
        .map(JsonNode::asText)
        .anyMatch("location"::equals));

    JsonNode outputSchema = mapper.readTree(skillPackage.resolve("output.schema.json").toFile());
    assertTrue(outputSchema.path("$id").asText().contains("weather-current-read/1.0.0/output.schema.json"));
    assertTrue(outputSchema.path("properties").has("condition"));
    assertTrue(outputSchema.path("properties").has("temperatureCelsius"));
  }

  @Test
  void providesSqlAssistantAdviceSkillPackageForAgentScopeAndRegistry() throws Exception {
    Path skillPackage = Path.of("skills/packages/sql-assistant-advice");
    Path agentScopeSkill = Path.of("../skills/sql-assistant-advice/SKILL.md");

    assertTrue(Files.exists(agentScopeSkill));
    assertTrue(Files.exists(skillPackage.resolve("manifest.json")));
    assertTrue(Files.exists(skillPackage.resolve("manifest.signature.json")));
    assertTrue(Files.exists(skillPackage.resolve("input.schema.json")));
    assertTrue(Files.exists(skillPackage.resolve("output.schema.json")));
    assertTrue(Files.exists(skillPackage.resolve("tests/happy-path.json")));
    assertTrue(Files.exists(skillPackage.resolve("tests/invalid-parameters.json")));
    assertTrue(Files.exists(skillPackage.resolve("tests/policy-denied.json")));

    ObjectMapper mapper = new ObjectMapper();
    JsonNode manifest = mapper.readTree(skillPackage.resolve("manifest.json").toFile());
    assertEquals("sql-assistant-advice-read", manifest.path("skillId").asText());
    assertEquals("1.0.0", manifest.path("version").asText());
    assertTrue(manifest.path("readOnly").asBoolean());
    assertEquals("READ_ONLY", manifest.path("riskLevel").asText());
    assertEquals("WORKFLOW", manifest.path("executor").asText());

    JsonNode inputSchema = mapper.readTree(skillPackage.resolve("input.schema.json").toFile());
    assertEquals(false, inputSchema.path("additionalProperties").asBoolean());
    assertTrue(inputSchema.path("properties").has("sql"));
    assertTrue(inputSchema.path("properties").has("limits"));
    assertTrue(StreamSupport.stream(inputSchema.path("properties").path("targetEnvironment").path("enum").spliterator(), false)
        .map(JsonNode::asText)
        .noneMatch("production"::equals));

    JsonNode outputSchema = mapper.readTree(skillPackage.resolve("output.schema.json").toFile());
    assertTrue(outputSchema.path("$id").asText().contains("sql-assistant-advice-read/1.0.0/output.schema.json"));
    assertTrue(outputSchema.path("properties").has("suggestions"));
    assertTrue(outputSchema.path("properties").has("validationRequired"));
  }

  private SqlConnectionCreateRequest connectionCreateRequest(
      String targetEnvironment,
      String credentialAlias,
      List<String> allowedSchemas) {
    return connectionCreateRequest(targetEnvironment, credentialAlias, allowedSchemas, "DB2_FOR_I");
  }

  private SqlConnectionCreateRequest connectionCreateRequest(
      String targetEnvironment,
      String credentialAlias,
      List<String> allowedSchemas,
      String platformType) {
    return new SqlConnectionCreateRequest(
        "1.0",
        "AS/400 Development",
        targetEnvironment,
        platformType,
        "as400-dev.internal",
        446,
        "ORDERS",
        allowedSchemas,
        List.of(SqlQueryAction.VALIDATE, SqlQueryAction.RUN_READ_ONLY, SqlQueryAction.PREFLIGHT_DML),
        credentialAlias,
        500,
        30);
  }

  private List<String> enumValues(JsonNode schema, String propertyName) {
    return StreamSupport.stream(
            schema.path("properties").path(propertyName).path("enum").spliterator(),
            false)
        .map(JsonNode::asText)
        .toList();
  }

  private void assertNoSchemaPropertyNamed(JsonNode node, Set<String> forbiddenNames) {
    if (node.has("properties")) {
      node.path("properties").fieldNames().forEachRemaining(fieldName ->
          assertFalse(forbiddenNames.contains(fieldName), "forbidden schema property: " + fieldName));
    }
    node.elements().forEachRemaining(child -> assertNoSchemaPropertyNamed(child, forbiddenNames));
  }
}
