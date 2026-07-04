package com.company.opsagent.executionworker.sqlworkbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.company.opsagent.contracts.sqlworkbench.SqlQueryAction;
import com.company.opsagent.contracts.sqlworkbench.SqlConnectionSummary;
import com.company.opsagent.contracts.sqlworkbench.SqlDatabaseMetadata;
import com.company.opsagent.contracts.sqlworkbench.SqlMetadataColumn;
import com.company.opsagent.contracts.sqlworkbench.SqlMetadataObject;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryExecutionRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryLimits;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlResultColumn;
import com.company.opsagent.contracts.sqlworkbench.SqlResultPage;
import com.company.opsagent.contracts.workflow.OperatorContext;
import com.company.opsagent.contracts.workflow.PolicyDecisionReference;
import com.company.opsagent.contracts.workflow.TraceContext;
import com.company.opsagent.contracts.workflow.WorkerRequestSignature;
import com.company.opsagent.contracts.workflow.WorkerTransportHeaders;
import com.fasterxml.jackson.databind.node.IntNode;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.server.ResponseStatusException;

/**
 * 验证 SQL Worker 入口复用 Worker 传输认证边界。
 */
class SqlQueryExecutionControllerTest {

  private static final String KEY_ID = "worker-key-a";
  private static final String SHARED_SECRET = "worker-transport-test-key-material";
  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-27T10:15:30Z"), ZoneOffset.UTC);

  @Test
  void rejectsUnsignedExecutionWhenTransportAuthIsEnabled() {
    var controller = controller();

    assertThrows(ResponseStatusException.class, () -> controller.execute(new HttpHeaders(), request()).block());
  }

  @Test
  void acceptsSignedExecutionWhenTransportAuthIsEnabled() {
    var request = request();
    var controller = controller();

    var result = controller.execute(signedExecutionHeaders(request), request).block();

    assertEquals("SUCCEEDED", result.status());
    assertEquals("result-1", result.resultId());
  }

  @Test
  void readsStoredResultOnlyWithValidSignature() {
    InMemorySqlResultStore store = new InMemorySqlResultStore(CLOCK);
    store.save(page());
    var controller = controller(store);

    assertThrows(ResponseStatusException.class, () -> controller.readResult(new HttpHeaders(), "result-1").block());

    var page = controller.readResult(signedResultReadHeaders("result-1"), "result-1").block();
    assertEquals("result-1", page.resultId());
    assertEquals(1, page.rows().size());
  }

  @Test
  void probesConnectionOnlyWithValidSignature() {
    var connection = connection();
    var controller = controller(new InMemorySqlResultStore(CLOCK));

    assertThrows(
        ResponseStatusException.class,
        () -> controller.probeConnection(new HttpHeaders(), connection.connectionId(), connection).block());

    var result = controller.probeConnection(
        signedConnectionProbeHeaders(connection),
        connection.connectionId(),
        connection).block();
    assertEquals("READY", result.status());
    assertEquals("as400-development", result.connectionId());
  }

  @Test
  void readsMetadataOnlyWithValidSignature() {
    var connection = connection();
    var controller = controller(new InMemorySqlResultStore(CLOCK));

    assertThrows(
        ResponseStatusException.class,
        () -> controller.readMetadata(new HttpHeaders(), connection.connectionId(), "ORDERS", connection).block());

    var metadata = controller.readMetadata(
        signedMetadataHeaders(connection, "ORDERS"),
        connection.connectionId(),
        "ORDERS",
        connection).block();
    assertEquals("ORDERS", metadata.schema());
    assertEquals("ORDERS", metadata.objects().getFirst().name());
  }

  private SqlQueryExecutionController controller() {
    return controller(new InMemorySqlResultStore(CLOCK));
  }

  private SqlQueryExecutionController controller(SqlResultStore store) {
    var worker = new RestrictedSqlQueryExecutionWorker(
        new CalciteSqlReadOnlyGuard(),
        request -> "result-1",
        CLOCK);
    return new SqlQueryExecutionController(worker, store, authenticator(), probeWorker(), metadataReader());
  }

  private SqlConnectionProbeWorker probeWorker() {
    return new SqlConnectionProbeWorker(
        new WorkerSqlEgressPolicy(
            List.of(new WorkerSqlConnectionDescriptor(
                "as400-development",
                "development",
                "as400-dev.internal",
                446,
                "as400-dev-readonly",
                true)),
            List.of(new WorkerSqlEgressTarget("as400-dev.internal", 446))),
        alias -> "database-password".toCharArray(),
        CLOCK);
  }

  private SqlWorkerTransportAuthenticator authenticator() {
    SqlWorkerTransportAuthProperties properties = new SqlWorkerTransportAuthProperties();
    properties.setEnabled(true);
    properties.setKeyId(KEY_ID);
    properties.setSharedSecret(SHARED_SECRET);
    properties.setMaxClockSkew(java.time.Duration.ofSeconds(30));
    return new SqlWorkerTransportAuthenticator(properties, CLOCK);
  }

  private SqlMetadataReader metadataReader() {
    return (connection, schema) -> new SqlDatabaseMetadata(
        "1.0",
        connection.connectionId(),
        schema,
        List.of(new SqlMetadataObject(
            schema,
            "ORDERS",
            "TABLE",
            List.of(new SqlMetadataColumn("ORDER_ID", "INTEGER", false, 1, false)),
            List.of())),
        false,
        OffsetDateTime.now(CLOCK));
  }

  private HttpHeaders signedExecutionHeaders(SqlQueryExecutionRequest request) {
    HttpHeaders headers = baseHeaders();
    String timestamp = OffsetDateTime.now(CLOCK).toString();
    String payload = WorkerRequestSignature.canonicalSqlPayload(KEY_ID, timestamp, request);
    headers.set(WorkerTransportHeaders.TIMESTAMP, timestamp);
    headers.set(WorkerTransportHeaders.SIGNATURE, WorkerRequestSignature.sign(SHARED_SECRET, payload));
    return headers;
  }

  private HttpHeaders signedResultReadHeaders(String resultId) {
    HttpHeaders headers = baseHeaders();
    String timestamp = OffsetDateTime.now(CLOCK).toString();
    String payload = WorkerRequestSignature.canonicalSqlResultReadPayload(KEY_ID, timestamp, resultId);
    headers.set(WorkerTransportHeaders.TIMESTAMP, timestamp);
    headers.set(WorkerTransportHeaders.SIGNATURE, WorkerRequestSignature.sign(SHARED_SECRET, payload));
    return headers;
  }

  private HttpHeaders signedConnectionProbeHeaders(SqlConnectionSummary connection) {
    HttpHeaders headers = baseHeaders();
    String timestamp = OffsetDateTime.now(CLOCK).toString();
    String payload = WorkerRequestSignature.canonicalSqlConnectionProbePayload(KEY_ID, timestamp, connection);
    headers.set(WorkerTransportHeaders.TIMESTAMP, timestamp);
    headers.set(WorkerTransportHeaders.SIGNATURE, WorkerRequestSignature.sign(SHARED_SECRET, payload));
    return headers;
  }

  private HttpHeaders signedMetadataHeaders(SqlConnectionSummary connection, String schema) {
    HttpHeaders headers = baseHeaders();
    String timestamp = OffsetDateTime.now(CLOCK).toString();
    String payload = WorkerRequestSignature.canonicalSqlMetadataPayload(KEY_ID, timestamp, connection, schema);
    headers.set(WorkerTransportHeaders.TIMESTAMP, timestamp);
    headers.set(WorkerTransportHeaders.SIGNATURE, WorkerRequestSignature.sign(SHARED_SECRET, payload));
    return headers;
  }

  private HttpHeaders baseHeaders() {
    HttpHeaders headers = new HttpHeaders();
    headers.set(WorkerTransportHeaders.KEY_ID, KEY_ID);
    return headers;
  }

  private SqlResultPage page() {
    return new SqlResultPage(
        "1.0",
        "result-1",
        List.of(new SqlResultColumn("ID", "INTEGER", false)),
        List.of(List.of(IntNode.valueOf(1))),
        null,
        false,
        OffsetDateTime.now(CLOCK).plusMinutes(15));
  }

  private SqlConnectionSummary connection() {
    return new SqlConnectionSummary(
        "1.0",
        "as400-development",
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
        OffsetDateTime.now(CLOCK).plusSeconds(30));
  }
}
