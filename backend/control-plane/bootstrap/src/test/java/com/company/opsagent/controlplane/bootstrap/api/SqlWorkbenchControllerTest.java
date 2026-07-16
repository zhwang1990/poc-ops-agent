package com.company.opsagent.controlplane.bootstrap.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import com.company.opsagent.contracts.sqlworkbench.SqlConnectionCreateRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlConnectionProbeResult;
import com.company.opsagent.contracts.sqlworkbench.SqlConnectionSummary;
import com.company.opsagent.contracts.sqlworkbench.SqlConnectionUpdateRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlAssistantAction;
import com.company.opsagent.contracts.sqlworkbench.SqlAssistantRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlAssistantResponse;
import com.company.opsagent.contracts.sqlworkbench.SqlAssistantStatus;
import com.company.opsagent.contracts.sqlworkbench.SqlAssistantSuggestion;
import com.company.opsagent.contracts.sqlworkbench.SqlDatabaseMetadata;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlCommitRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlImpactPreview;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlPreflightResult;
import com.company.opsagent.contracts.sqlworkbench.SqlMetadataColumn;
import com.company.opsagent.contracts.sqlworkbench.SqlMetadataIndex;
import com.company.opsagent.contracts.sqlworkbench.SqlMetadataObject;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryExecutionResult;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlResultPage;
import com.company.opsagent.contracts.sqlworkbench.SqlValidationReport;
import com.company.opsagent.contracts.workflow.OperatorContext;
import com.company.opsagent.contracts.workflow.PolicyDecisionReference;
import com.company.opsagent.contracts.workflow.TraceContext;
import com.company.opsagent.controlplane.modules.sqlworkbench.SqlWorkbenchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/**
 * SQL 工作台控制器的 HTTP 载荷边界测试。
 */
class SqlWorkbenchControllerTest {

  private final RecordingSqlWorkbenchService service = new RecordingSqlWorkbenchService();
  private final SqlWorkbenchController controller = new SqlWorkbenchController(service, new ObjectMapper());
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void rejectsUnknownConnectionCreateFieldsBeforeServiceLayer() throws Exception {
    var request = objectMapper.readTree("""
        {
          "contractVersion": "1.0",
          "displayName": "AS/400 Dev Sandbox",
          "targetEnvironment": "development",
          "platformType": "DB2_FOR_I",
          "host": "as400-dev.internal",
          "port": 446,
          "defaultSchema": "ORDERS",
          "allowedSchemas": ["ORDERS"],
          "capabilities": ["VALIDATE", "RUN_READ_ONLY"],
          "credentialAlias": "as400-dev-readonly",
          "maxRowsDefault": 500,
          "timeoutSecondsDefault": 30,
          "password": "must-not-be-accepted"
        }
        """);

    StepVerifier.create(controller.createConnection(request))
        .expectErrorSatisfies(error -> {
          assertInstanceOf(IllegalArgumentException.class, error);
          assertEquals("unsupported SQL connection create field: password", error.getMessage());
        })
        .verify();

    assertEquals(0, service.createCount.get());
  }

  @Test
  void rejectsUnknownConnectionUpdateFieldsBeforeServiceLayer() throws Exception {
    var request = objectMapper.readTree("""
        {
          "contractVersion": "1.0",
          "displayName": "AS/400 Reporting",
          "targetEnvironment": "test",
          "platformType": "DB2_FOR_I",
          "host": "as400-reporting.internal",
          "port": 446,
          "defaultSchema": "REPORTING",
          "allowedSchemas": ["REPORTING"],
          "capabilities": ["VALIDATE", "RUN_READ_ONLY"],
          "credentialAlias": "as400-reporting-readonly",
          "maxRowsDefault": 250,
          "timeoutSecondsDefault": 45,
          "password": "must-not-be-accepted"
        }
        """);

    StepVerifier.create(controller.updateConnection("as400-development", request))
        .expectErrorSatisfies(error -> {
          assertInstanceOf(IllegalArgumentException.class, error);
          assertEquals("unsupported SQL connection update field: password", error.getMessage());
        })
        .verify();

    assertEquals(0, service.updateCount.get());
  }

  @Test
  void passesConnectionUpdateAndDeleteThroughServiceBoundary() throws Exception {
    var request = objectMapper.readTree("""
        {
          "contractVersion": "1.0",
          "displayName": "AS/400 Reporting",
          "targetEnvironment": "test",
          "platformType": "DB2_FOR_I",
          "host": "as400-reporting.internal",
          "port": 446,
          "defaultSchema": "REPORTING",
          "allowedSchemas": ["REPORTING"],
          "capabilities": ["VALIDATE", "RUN_READ_ONLY"],
          "credentialAlias": "as400-reporting-readonly",
          "maxRowsDefault": 250,
          "timeoutSecondsDefault": 45
        }
        """);

    StepVerifier.create(controller.updateConnection("as400-development", request))
        .assertNext(response -> {
          assertEquals("as400-development", response.connectionId());
          assertEquals("AS/400 Reporting", response.displayName());
        })
        .verifyComplete();

    StepVerifier.create(controller.deleteConnection("as400-development"))
        .verifyComplete();

    assertEquals(1, service.updateCount.get());
    assertEquals("as400-development", service.lastUpdateConnectionId);
    assertEquals("REPORTING", service.lastUpdateRequest.defaultSchema());
    assertEquals(1, service.deleteCount.get());
    assertEquals("as400-development", service.lastDeleteConnectionId);
  }

  @Test
  void passesSqlAssistantRequestThroughTypedServiceBoundary() throws Exception {
    var request = objectMapper.readTree("""
        {
          "contractVersion": "1.0",
          "connectionId": "as400-development",
          "targetEnvironment": "development",
          "schema": "ORDERS",
          "assistantAction": "ANALYZE_ERROR",
          "sql": "select * from ORDERS.ORDERS",
          "limits": {
            "maxRows": 500,
            "maxBytes": 5000000,
            "timeoutSeconds": 30
          },
          "diagnosticContext": "SQL syntax is not supported",
          "idempotencyKey": "assistant-key-1"
        }
        """);

    StepVerifier.create(controller.assist(request))
        .assertNext(response -> {
          assertEquals(SqlAssistantStatus.SUCCEEDED, response.status());
          assertEquals(SqlAssistantAction.ANALYZE_ERROR, response.assistantAction());
          assertEquals(true, response.validationRequired());
        })
        .verifyComplete();

    assertEquals(1, service.assistCount.get());
    assertEquals(SqlAssistantAction.ANALYZE_ERROR, service.lastAssistantRequest.assistantAction());
    assertEquals("SQL syntax is not supported", service.lastAssistantRequest.diagnosticContext());
  }

  @Test
  void passesMetadataReadThroughServiceBoundary() {
    StepVerifier.create(controller.metadata("h2-local-test", "PUBLIC"))
        .assertNext(response -> {
          assertEquals("h2-local-test", response.connectionId());
          assertEquals("PUBLIC", response.schema());
          assertEquals("ORDERS", response.objects().getFirst().name());
        })
        .verifyComplete();

    assertEquals(1, service.metadataCount.get());
    assertEquals("h2-local-test", service.lastMetadataConnectionId);
    assertEquals("PUBLIC", service.lastMetadataSchema);
  }

  @Test
  void passesDmlCommitRequestThroughTypedServiceBoundary() throws Exception {
    var request = objectMapper.readTree("""
        {
          "contractVersion": "1.0",
          "query": {
            "contractVersion": "1.0",
            "connectionId": "as400-dev",
            "targetEnvironment": "dev",
            "schema": "ORDERS",
            "action": "COMMIT_DML",
            "sql": "update ORDERS.ORDERS set status = 'READY'",
            "parameters": [],
            "limits": {
              "maxRows": 500,
              "maxBytes": 5000000,
              "timeoutSeconds": 30
            },
            "idempotencyKey": "commit-key-1"
          },
          "confirmation": {
            "contractVersion": "1.0",
            "sqlHash": "sha256:test",
            "confirmedRisks": ["UPDATE_WITHOUT_WHERE"],
            "confirmationCode": "CONFIRM_SQL_DML_RISK"
          }
        }
        """);

    StepVerifier.create(controller.commit(request, exchange()))
        .assertNext(response -> {
          assertEquals("SUCCEEDED", response.status());
          assertEquals(1, response.affectedRows());
        })
        .verifyComplete();

    assertEquals(1, service.commitCount.get());
  }

  @Test
  void passesDmlPreflightWithExecutionContextThroughServiceBoundary() {
    SqlQueryRequest request = new SqlQueryRequest(
        "1.0",
        "as400-dev",
        "dev",
        "ORDERS",
        com.company.opsagent.contracts.sqlworkbench.SqlQueryAction.PREFLIGHT_DML,
        "update ORDERS.ORDERS set status = 'READY' where order_id = 1",
        List.of(),
        new com.company.opsagent.contracts.sqlworkbench.SqlQueryLimits(500, 5_000_000, 30),
        "preflight-key-1");

    StepVerifier.create(controller.preflight(request, exchange()))
        .assertNext(response -> assertEquals(1L, response.impactPreview().affectedRows()))
        .verifyComplete();

    assertEquals(1, service.preflightCount.get());
    assertEquals("operator-1", service.lastPreflightOperator.operatorId());
    assertEquals("policy-v1", service.lastPreflightPolicy.policyVersion());
  }

  @Test
  void rejectsUnknownDmlCommitFieldsBeforeServiceLayer() throws Exception {
    var request = objectMapper.readTree("""
        {
          "contractVersion": "1.0",
          "query": {
            "contractVersion": "1.0",
            "connectionId": "as400-dev",
            "targetEnvironment": "dev",
            "schema": "ORDERS",
            "action": "COMMIT_DML",
            "sql": "delete from ORDERS.ORDERS where order_id = 1",
            "parameters": [],
            "limits": {
              "maxRows": 500,
              "maxBytes": 5000000,
              "timeoutSeconds": 30
            },
            "idempotencyKey": "commit-key-1"
          },
          "password": "must-not-be-accepted"
        }
        """);

    StepVerifier.create(controller.commit(request, exchange()))
        .expectErrorSatisfies(error -> {
          assertInstanceOf(IllegalArgumentException.class, error);
          assertEquals("unsupported SQL DML commit field: password", error.getMessage());
        })
        .verify();

    assertEquals(0, service.commitCount.get());
  }

  private org.springframework.web.server.ServerWebExchange exchange() {
    var exchange = org.springframework.mock.web.server.MockServerWebExchange.from(
        org.springframework.mock.http.server.reactive.MockServerHttpRequest.post("/internal/sql-workbench/queries/commit"));
    exchange.getAttributes().put(
        com.company.opsagent.controlplane.bootstrap.security.PolicyEnforcementWebFilter.EXECUTION_CONTEXT_ATTRIBUTE,
        new com.company.opsagent.controlplane.modules.audit.ExecutionContext(
            "request-1",
            "trace-1",
            "operator-1",
            "operator",
            List.of("ROLE_ops-admin"),
            "internal.sql-workbench.dml.commit",
            "/internal/sql-workbench/queries/commit",
            "POST",
            "/internal/sql-workbench/queries/commit",
            "policy-v1"));
    return exchange;
  }

  private static final class RecordingSqlWorkbenchService implements SqlWorkbenchService {

    private final AtomicInteger createCount = new AtomicInteger();
    private final AtomicInteger updateCount = new AtomicInteger();
    private final AtomicInteger deleteCount = new AtomicInteger();
    private final AtomicInteger assistCount = new AtomicInteger();
    private final AtomicInteger metadataCount = new AtomicInteger();
    private final AtomicInteger commitCount = new AtomicInteger();
    private final AtomicInteger preflightCount = new AtomicInteger();
    private String lastUpdateConnectionId;
    private SqlConnectionUpdateRequest lastUpdateRequest;
    private String lastDeleteConnectionId;
    private SqlAssistantRequest lastAssistantRequest;
    private String lastMetadataConnectionId;
    private String lastMetadataSchema;
    private OperatorContext lastPreflightOperator;
    private PolicyDecisionReference lastPreflightPolicy;

    @Override
    public List<SqlConnectionSummary> listConnections() {
      throw new UnsupportedOperationException();
    }

    @Override
    public SqlConnectionSummary createConnection(SqlConnectionCreateRequest request) {
      createCount.incrementAndGet();
      throw new UnsupportedOperationException();
    }

    @Override
    public SqlConnectionSummary updateConnection(String connectionId, SqlConnectionUpdateRequest request) {
      updateCount.incrementAndGet();
      lastUpdateConnectionId = connectionId;
      lastUpdateRequest = request;
      return new SqlConnectionSummary(
          "1.0",
          connectionId,
          request.displayName(),
          request.targetEnvironment(),
          request.platformType(),
          request.host(),
          request.port(),
          request.defaultSchema(),
          request.allowedSchemas(),
          request.capabilities(),
          request.credentialAlias(),
          "PENDING_WORKER_BINDING",
          request.maxRowsDefault(),
          request.timeoutSecondsDefault());
    }

    @Override
    public void deleteConnection(String connectionId) {
      deleteCount.incrementAndGet();
      lastDeleteConnectionId = connectionId;
    }

    @Override
    public SqlConnectionProbeResult probeConnection(String connectionId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public SqlValidationReport validate(SqlQueryRequest request) {
      throw new UnsupportedOperationException();
    }

    @Override
    public SqlAssistantResponse assist(SqlAssistantRequest request) {
      assistCount.incrementAndGet();
      lastAssistantRequest = request;
      return new SqlAssistantResponse(
          "1.0",
          SqlAssistantStatus.SUCCEEDED,
          request.assistantAction(),
          "The error points to SQL syntax.",
          List.of(new SqlAssistantSuggestion(
              "Check statement syntax",
              "The parser rejected the submitted SQL before execution.",
              null)),
          List.of("AI suggestions must be validated before execution."),
          true,
          "provider:fingerprint");
    }

    @Override
    public SqlQueryExecutionResult runReadOnlyQuery(
        SqlQueryRequest request,
        OperatorContext operator,
        PolicyDecisionReference policyDecision,
        TraceContext trace) {
      throw new UnsupportedOperationException();
    }

    @Override
    public SqlDmlPreflightResult preflightControlledDml(
        SqlQueryRequest request,
        OperatorContext operator,
        PolicyDecisionReference policyDecision,
        TraceContext trace) {
      preflightCount.incrementAndGet();
      lastPreflightOperator = operator;
      lastPreflightPolicy = policyDecision;
      return new SqlDmlPreflightResult(
          "1.0",
          new SqlValidationReport(
              "1.0",
              com.company.opsagent.contracts.sqlworkbench.SqlStatementType.UPDATE,
              com.company.opsagent.contracts.sqlworkbench.SqlValidationLevel.VALIDATED,
              "sha256:test",
              List.of("ORDERS.ORDERS"),
              List.of(),
              List.of(),
              List.of()),
          new SqlDmlImpactPreview("1.0", 1L, List.of(), List.of(), List.of()));
    }

    @Override
    public SqlQueryExecutionResult commitControlledDml(
        SqlDmlCommitRequest request,
        OperatorContext operator,
        PolicyDecisionReference policyDecision,
        TraceContext trace) {
      commitCount.incrementAndGet();
      return new SqlQueryExecutionResult(
          "1.0",
          "execution-1",
          "workflow-1",
          "SUCCEEDED",
          null,
          null,
          null,
          1);
    }

    @Override
    public SqlResultPage readResultPage(String resultId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public SqlDatabaseMetadata readMetadata(String connectionId, String schema) {
      metadataCount.incrementAndGet();
      lastMetadataConnectionId = connectionId;
      lastMetadataSchema = schema;
      return new SqlDatabaseMetadata(
          "1.0",
          connectionId,
          schema,
          List.of(new SqlMetadataObject(
              schema,
              "ORDERS",
              "TABLE",
              List.of(new SqlMetadataColumn("ORDER_ID", "INTEGER", false, 1, false)),
              List.of(new SqlMetadataIndex("PRIMARY_KEY_ORDERS", true, List.of("ORDER_ID"))))),
          false,
          java.time.OffsetDateTime.parse("2026-06-27T10:30:00Z"));
    }
  }
}
