package com.company.opsagent.controlplane.bootstrap.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.opsagent.contracts.sqlworkbench.SqlQueryAction;
import com.company.opsagent.contracts.sqlworkbench.SqlConnectionSummary;
import com.company.opsagent.contracts.sqlworkbench.SqlDatabaseMetadata;
import com.company.opsagent.contracts.sqlworkbench.SqlControlledDmlExecutionRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlCommitRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlConfirmation;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlExecutionBinding;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlPreflightExecutionRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlPreviewSelection;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryExecutionRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryLimits;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryRequest;
import com.company.opsagent.contracts.workflow.OperatorContext;
import com.company.opsagent.contracts.workflow.PolicyDecisionReference;
import com.company.opsagent.contracts.workflow.TraceContext;
import com.company.opsagent.contracts.workflow.WorkerRequestSignature;
import com.company.opsagent.contracts.workflow.WorkerTransportHeaders;
import com.company.opsagent.controlplane.bootstrap.config.WorkerProperties;
import com.company.opsagent.controlplane.modules.sqlworkbench.SqlWorkbenchException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * 验证 SQL 工作台控制面客户端只能通过受限 Worker HTTP 边界执行和读取结果。
 */
class WebClientSqlWorkbenchWorkerClientTest {

  private static final String KEY_ID = "worker-key-a";
  private static final String SHARED_SECRET = "worker-transport-test-key-material";
  private static final Instant SIGNED_AT = Instant.parse("2026-06-27T10:15:30Z");

  @Test
  void executesSqlQueryThroughSignedWorkerEndpoint() {
    List<ClientRequest> captured = new ArrayList<>();
    var client = new WebClientSqlWorkbenchWorkerClient(
        webClient(captured),
        workerProperties(true),
        Clock.fixed(SIGNED_AT, ZoneOffset.UTC));
    var request = request();

    var result = client.execute(request);

    assertEquals("SUCCEEDED", result.status());
    assertEquals("result-1", result.resultId());
    ClientRequest sent = captured.getFirst();
    assertEquals("/internal/executions/sql-query", sent.url().getPath());
    assertEquals(KEY_ID, sent.headers().getFirst(WorkerTransportHeaders.KEY_ID));
    assertEquals("2026-06-27T10:15:30Z", sent.headers().getFirst(WorkerTransportHeaders.TIMESTAMP));
    String signature = sent.headers().getFirst(WorkerTransportHeaders.SIGNATURE);
    String payload = WorkerRequestSignature.canonicalSqlPayload(
        KEY_ID,
        "2026-06-27T10:15:30Z",
        request);
    assertTrue(WorkerRequestSignature.matches(WorkerRequestSignature.sign(SHARED_SECRET, payload), signature));
  }

  @Test
  void preflightsDmlThroughSeparatelySignedWorkerEndpoint() {
    List<ClientRequest> captured = new ArrayList<>();
    var client = new WebClientSqlWorkbenchWorkerClient(
        webClient(captured),
        workerProperties(true),
        Clock.fixed(SIGNED_AT, ZoneOffset.UTC));
    SqlDmlPreflightExecutionRequest request = preflightRequest();

    var preview = client.preflightDml(request);

    assertEquals(2L, preview.affectedRows());
    ClientRequest sent = captured.getFirst();
    assertEquals("/internal/executions/sql-query/dml-preflight", sent.url().getPath());
    String signature = sent.headers().getFirst(WorkerTransportHeaders.SIGNATURE);
    String payload = WorkerRequestSignature.canonicalSqlDmlPreflightPayload(
        KEY_ID,
        "2026-06-27T10:15:30Z",
        request);
    assertTrue(WorkerRequestSignature.matches(WorkerRequestSignature.sign(SHARED_SECRET, payload), signature));
  }

  @Test
  void commitsDmlThroughSeparatelySignedWorkerEndpoint() {
    List<ClientRequest> captured = new ArrayList<>();
    var client = new WebClientSqlWorkbenchWorkerClient(
        webClient(captured),
        workerProperties(true),
        Clock.fixed(SIGNED_AT, ZoneOffset.UTC));
    SqlControlledDmlExecutionRequest request = controlledDmlRequest();

    var result = client.executeControlledDml(request);

    assertEquals("SUCCEEDED", result.status());
    ClientRequest sent = captured.getFirst();
    assertEquals("/internal/executions/sql-query/dml-commit", sent.url().getPath());
    String signature = sent.headers().getFirst(WorkerTransportHeaders.SIGNATURE);
    String payload = WorkerRequestSignature.canonicalControlledSqlDmlPayload(
        KEY_ID,
        "2026-06-27T10:15:30Z",
        request);
    assertTrue(WorkerRequestSignature.matches(WorkerRequestSignature.sign(SHARED_SECRET, payload), signature));
  }

  @Test
  void exposesControlledDmlSupportOnlyWithSignedTransport() {
    var signed = new WebClientSqlWorkbenchWorkerClient(
        webClient(new ArrayList<>()), workerProperties(true), Clock.fixed(SIGNED_AT, ZoneOffset.UTC));
    var unsigned = new WebClientSqlWorkbenchWorkerClient(
        webClient(new ArrayList<>()), workerProperties(false), Clock.fixed(SIGNED_AT, ZoneOffset.UTC));

    assertTrue(signed.supportsControlledDml("development"));
    assertFalse(unsigned.supportsControlledDml("development"));
    assertFalse(signed.supportsControlledDml("production"));
  }

  @Test
  void mapsWorkerPreflightRejectionToStableSqlWorkbenchError() {
    var client = new WebClientSqlWorkbenchWorkerClient(
        rejectingDmlWebClient(), workerProperties(true), Clock.fixed(SIGNED_AT, ZoneOffset.UTC));

    SqlWorkbenchException exception = assertThrows(
        SqlWorkbenchException.class,
        () -> client.preflightDml(preflightRequest()));

    assertEquals("SQL_DML_EGRESS_DENIED", exception.code());
  }

  @Test
  void mapsWorkerCommitRejectionToDefinitiveFailedResult() {
    var client = new WebClientSqlWorkbenchWorkerClient(
        rejectingDmlWebClient(), workerProperties(true), Clock.fixed(SIGNED_AT, ZoneOffset.UTC));
    SqlControlledDmlExecutionRequest request = controlledDmlRequest();

    var result = client.executeControlledDml(request);

    assertEquals("FAILED", result.status());
    assertEquals("SQL_DML_EGRESS_DENIED", result.errorCode());
    assertEquals(request.executionRequestId(), result.executionRequestId());
    assertEquals(request.workflowId(), result.workflowId());
  }

  @Test
  void readsSqlResultPageThroughSignedWorkerEndpoint() {
    List<ClientRequest> captured = new ArrayList<>();
    var client = new WebClientSqlWorkbenchWorkerClient(
        webClient(captured),
        workerProperties(true),
        Clock.fixed(SIGNED_AT, ZoneOffset.UTC));

    var page = client.readResultPage("result-1");

    assertEquals("result-1", page.resultId());
    assertEquals(1, page.rows().size());
    ClientRequest sent = captured.getFirst();
    assertEquals("/internal/executions/sql-query/results/result-1", sent.url().getPath());
    assertEquals(KEY_ID, sent.headers().getFirst(WorkerTransportHeaders.KEY_ID));
    assertEquals("2026-06-27T10:15:30Z", sent.headers().getFirst(WorkerTransportHeaders.TIMESTAMP));
    String signature = sent.headers().getFirst(WorkerTransportHeaders.SIGNATURE);
    String payload = WorkerRequestSignature.canonicalSqlResultReadPayload(
        KEY_ID,
        "2026-06-27T10:15:30Z",
        "result-1");
    assertTrue(WorkerRequestSignature.matches(WorkerRequestSignature.sign(SHARED_SECRET, payload), signature));
  }

  @Test
  void preservesWorkerNotFoundStatusWhenResultPageExpired() {
    List<ClientRequest> captured = new ArrayList<>();
    var client = new WebClientSqlWorkbenchWorkerClient(
        webClient(captured),
        workerProperties(true),
        Clock.fixed(SIGNED_AT, ZoneOffset.UTC));

    ResponseStatusException exception = assertThrows(
        ResponseStatusException.class,
        () -> client.readResultPage("expired-result"));

    assertEquals(HttpStatus.NOT_FOUND, exception.getStatusCode());
    assertEquals("/internal/executions/sql-query/results/expired-result", captured.getFirst().url().getPath());
  }

  @Test
  void probesSqlConnectionThroughSignedWorkerEndpoint() {
    List<ClientRequest> captured = new ArrayList<>();
    var client = new WebClientSqlWorkbenchWorkerClient(
        webClient(captured),
        workerProperties(true),
        Clock.fixed(SIGNED_AT, ZoneOffset.UTC));
    var connection = connection();

    var result = client.probe(connection);

    assertEquals("READY", result.status());
    assertEquals("as400-development", result.connectionId());
    ClientRequest sent = captured.getFirst();
    assertEquals("/internal/executions/sql-query/connections/as400-development/probe", sent.url().getPath());
    assertEquals(KEY_ID, sent.headers().getFirst(WorkerTransportHeaders.KEY_ID));
    assertEquals("2026-06-27T10:15:30Z", sent.headers().getFirst(WorkerTransportHeaders.TIMESTAMP));
    String signature = sent.headers().getFirst(WorkerTransportHeaders.SIGNATURE);
    String payload = WorkerRequestSignature.canonicalSqlConnectionProbePayload(
        KEY_ID,
        "2026-06-27T10:15:30Z",
        connection);
    assertTrue(WorkerRequestSignature.matches(WorkerRequestSignature.sign(SHARED_SECRET, payload), signature));
  }

  @Test
  void readsSqlMetadataThroughSignedWorkerEndpoint() {
    List<ClientRequest> captured = new ArrayList<>();
    var client = new WebClientSqlWorkbenchWorkerClient(
        webClient(captured),
        workerProperties(true),
        Clock.fixed(SIGNED_AT, ZoneOffset.UTC));
    var connection = connection();

    SqlDatabaseMetadata metadata = client.readMetadata(connection, "ORDERS");

    assertEquals("ORDERS", metadata.schema());
    assertEquals("CUSTOMERS", metadata.objects().getFirst().name());
    ClientRequest sent = captured.getFirst();
    assertEquals("/internal/executions/sql-query/connections/as400-development/metadata", sent.url().getPath());
    assertEquals("schema=ORDERS", sent.url().getQuery());
    assertEquals(KEY_ID, sent.headers().getFirst(WorkerTransportHeaders.KEY_ID));
    assertEquals("2026-06-27T10:15:30Z", sent.headers().getFirst(WorkerTransportHeaders.TIMESTAMP));
    String signature = sent.headers().getFirst(WorkerTransportHeaders.SIGNATURE);
    String payload = WorkerRequestSignature.canonicalSqlMetadataPayload(
        KEY_ID,
        "2026-06-27T10:15:30Z",
        connection,
        "ORDERS");
    assertTrue(WorkerRequestSignature.matches(WorkerRequestSignature.sign(SHARED_SECRET, payload), signature));
  }

  @Test
  void returnsProbeFailedWhenWorkerRejectsProbe() {
    List<ClientRequest> captured = new ArrayList<>();
    var client = new WebClientSqlWorkbenchWorkerClient(
        webClient(captured),
        workerProperties(true),
        Clock.fixed(SIGNED_AT, ZoneOffset.UTC));

    var result = client.probe(connection("missing-probe"));

    assertEquals("PROBE_FAILED", result.status());
    assertEquals("missing-probe", result.connectionId());
    assertEquals("/internal/executions/sql-query/connections/missing-probe/probe", captured.getFirst().url().getPath());
  }

  @Test
  void omitsSignatureHeadersWhenWorkerTransportAuthIsDisabled() {
    List<ClientRequest> captured = new ArrayList<>();
    var client = new WebClientSqlWorkbenchWorkerClient(
        webClient(captured),
        workerProperties(false),
        Clock.fixed(SIGNED_AT, ZoneOffset.UTC));

    client.execute(request());

    ClientRequest sent = captured.getFirst();
    assertFalse(sent.headers().containsKey(WorkerTransportHeaders.KEY_ID));
    assertFalse(sent.headers().containsKey(WorkerTransportHeaders.TIMESTAMP));
    assertFalse(sent.headers().containsKey(WorkerTransportHeaders.SIGNATURE));
  }

  private WebClient webClient(List<ClientRequest> captured) {
    return WebClient.builder()
        .exchangeFunction(request -> {
          captured.add(request);
          assertNotNull(request);
          if (request.url().getPath().contains("/missing-probe/probe")) {
            return Mono.just(ClientResponse.create(HttpStatus.NOT_FOUND)
                .header("Content-Type", "application/json")
                .body("{}")
                .build());
          }
          if (request.url().getPath().endsWith("/probe")) {
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "application/json")
                .body("""
                    {
                      "contractVersion": "1.0",
                      "connectionId": "as400-development",
                      "status": "READY",
                      "message": "SQL connection probe succeeded",
                      "probedAt": "2026-06-27T10:15:31Z"
                    }
                    """)
                .build());
          }
          if (request.url().getPath().endsWith("/metadata")) {
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "application/json")
                .body("""
                    {
                      "contractVersion": "1.0",
                      "connectionId": "as400-development",
                      "schema": "ORDERS",
                      "objects": [
                        {
                          "schema": "ORDERS",
                          "name": "CUSTOMERS",
                          "type": "TABLE",
                          "columns": [
                            {
                              "name": "CUSTOMER_ID",
                              "type": "VARCHAR",
                              "nullable": false,
                              "ordinalPosition": 1,
                              "masked": false
                            }
                          ],
                          "indexes": [
                            {
                              "name": "PRIMARY_KEY_CUSTOMERS",
                              "unique": true,
                              "columns": ["CUSTOMER_ID"]
                            }
                          ]
                        }
                      ],
                      "truncated": false,
                      "refreshedAt": "2026-06-27T10:15:31Z"
                    }
                    """)
                .build());
          }
          if (request.url().getPath().endsWith("/dml-preflight")) {
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "application/json")
                .body("""
                    {
                      "contractVersion": "1.0",
                      "affectedRows": 2,
                      "sampleColumns": [],
                      "sampleRows": [],
                      "unverifiedItems": []
                    }
                    """)
                .build());
          }
          if (request.url().getPath().endsWith("/result-1")) {
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "application/json")
                .body("""
                    {
                      "contractVersion": "1.0",
                      "resultId": "result-1",
                      "columns": [{"name": "ID", "type": "INTEGER", "masked": false}],
                      "rows": [[1]],
                      "nextCursor": null,
                      "truncated": false,
                      "expiresAt": "2026-06-27T10:30:00Z"
                    }
                    """)
                .build());
          }
          if (request.url().getPath().endsWith("/expired-result")) {
            return Mono.just(ClientResponse.create(HttpStatus.NOT_FOUND)
                .header("Content-Type", "application/json")
                .body("{}")
                .build());
          }
          return Mono.just(ClientResponse.create(HttpStatus.OK)
              .header("Content-Type", "application/json")
              .body("""
                  {
                    "contractVersion": "1.0",
                    "executionRequestId": "execution-1",
                    "workflowId": "workflow-1",
                    "status": "SUCCEEDED",
                    "resultId": "result-1",
                    "errorCode": null,
                    "errorMessage": null
                  }
                  """)
              .build());
        })
        .build();
  }

  private WebClient rejectingDmlWebClient() {
    return WebClient.builder()
        .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.FORBIDDEN)
            .header("Content-Type", "application/problem+json")
            .body("{\"errorCode\":\"SQL_DML_EGRESS_DENIED\",\"detail\":\"denied\"}")
            .build()))
        .build();
  }

  private SqlConnectionSummary connection() {
    return connection("as400-development");
  }

  private SqlConnectionSummary connection(String connectionId) {
    return new SqlConnectionSummary(
        "1.0",
        connectionId,
        "AS/400 Development",
        "development",
        "DB2_FOR_I",
        "as400-dev.internal",
        446,
        "ORDERS",
        List.of("ORDERS"),
        List.of(SqlQueryAction.VALIDATE, SqlQueryAction.RUN_READ_ONLY, SqlQueryAction.PREFLIGHT_DML),
        "as400-dev-readonly",
        "READY",
        500,
        30);
  }

  private WorkerProperties workerProperties(boolean enabled) {
    WorkerProperties properties = new WorkerProperties();
    properties.getTransportAuth().setEnabled(enabled);
    properties.getTransportAuth().setKeyId(KEY_ID);
    properties.getTransportAuth().setSharedSecret(SHARED_SECRET);
    return properties;
  }

  private SqlQueryExecutionRequest request() {
    var query = new SqlQueryRequest(
        "1.0",
        "as400-development",
        "development",
        "ORDERS",
        SqlQueryAction.RUN_READ_ONLY,
        "select * from ORDERS.ORDERS",
        List.of(),
        new SqlQueryLimits(500, 10_000_000, 30),
        "idempotency-1");
    return new SqlQueryExecutionRequest(
        "1.0",
        "execution-1",
        "workflow-1",
        query,
        "validation-hash-1",
        new OperatorContext("operator-1", List.of("ROLE_ops-reader")),
        new PolicyDecisionReference("decision-1", "policy-v1", "ALLOW"),
        new TraceContext("trace-1", "request-1"),
        OffsetDateTime.parse("2026-06-27T10:16:00Z"));
  }

  private SqlDmlPreflightExecutionRequest preflightRequest() {
    return new SqlDmlPreflightExecutionRequest(
        "1.0",
        "preflight-execution-1",
        "preflight-workflow-1",
        dmlQuery(SqlQueryAction.PREFLIGHT_DML),
        "validation-hash-1",
        new SqlDmlPreviewSelection("1.0", List.of("ORDER_ID"), List.of()),
        new OperatorContext("operator-1", List.of("ROLE_ops-admin")),
        new PolicyDecisionReference("decision-1", "policy-v1", "ALLOW"),
        new TraceContext("trace-1", "request-1"),
        OffsetDateTime.parse("2026-06-27T10:16:00Z"));
  }

  private SqlControlledDmlExecutionRequest controlledDmlRequest() {
    SqlQueryRequest query = dmlQuery(SqlQueryAction.COMMIT_DML);
    return new SqlControlledDmlExecutionRequest(
        "1.0",
        "dml-execution-1",
        "dml-workflow-1",
        new SqlDmlCommitRequest(
            "1.0",
            query,
            new SqlDmlConfirmation(
                "1.0",
                "sha256:" + "a".repeat(64),
                List.of("CONTROLLED_DML_CONFIRMED"),
                SqlDmlConfirmation.RISK_CONFIRMATION_CODE)),
        new SqlDmlExecutionBinding(
            "b".repeat(64),
            "c".repeat(64),
            "d".repeat(64),
            "e".repeat(64)),
        new OperatorContext("operator-1", List.of("ROLE_ops-admin")),
        new PolicyDecisionReference("decision-1", "policy-v1", "ALLOW"),
        new TraceContext("trace-1", "request-1"),
        OffsetDateTime.parse("2026-06-27T10:16:00Z"));
  }

  private SqlQueryRequest dmlQuery(SqlQueryAction action) {
    return new SqlQueryRequest(
        "1.0",
        "as400-development",
        "development",
        "ORDERS",
        action,
        "update ORDERS.ORDERS set status = 'READY' where order_id = 42",
        List.of(),
        new SqlQueryLimits(500, 10_000_000, 30),
        "dml-idempotency-1");
  }
}
