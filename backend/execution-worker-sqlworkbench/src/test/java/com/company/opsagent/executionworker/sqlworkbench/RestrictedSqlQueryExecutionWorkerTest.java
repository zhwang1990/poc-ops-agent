package com.company.opsagent.executionworker.sqlworkbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.company.opsagent.contracts.sqlworkbench.SqlControlledDmlExecutionRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlCommitRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlConfirmation;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlExecutionBinding;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlPreflightExecutionRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlPreviewSelection;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryAction;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryExecutionRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryLimits;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryRequest;
import com.company.opsagent.contracts.workflow.OperatorContext;
import com.company.opsagent.contracts.workflow.PolicyDecisionReference;
import com.company.opsagent.contracts.workflow.TraceContext;
import java.sql.SQLSyntaxErrorException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class RestrictedSqlQueryExecutionWorkerTest {

  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-12T00:00:00Z"), ZoneOffset.UTC);

  @Test
  void rejectsDmlHiddenInsideReadOnlyExecutionEnvelope() {
    var worker = new RestrictedSqlQueryExecutionWorker(new CalciteSqlReadOnlyGuard(), request -> "result-1", CLOCK);

    var result = worker.execute(request("update ORDERS.ORDERS set status = 'READY'", "development", 30));

    assertEquals("REJECTED", result.status());
    assertEquals("SQL_NOT_READ_ONLY", result.errorCode());
  }

  @Test
  void rejectsExpiredRequest() {
    var worker = new RestrictedSqlQueryExecutionWorker(new CalciteSqlReadOnlyGuard(), request -> "result-1", CLOCK);

    var result = worker.execute(request("select * from ORDERS.ORDERS", "development", -1));

    assertEquals("REJECTED", result.status());
    assertEquals("REQUEST_EXPIRED", result.errorCode());
  }

  @Test
  void acceptsValidatedSelect() {
    var worker = new RestrictedSqlQueryExecutionWorker(new CalciteSqlReadOnlyGuard(), request -> "result-1", CLOCK);

    var result = worker.execute(request("select * from ORDERS.ORDERS", "development", 30));

    assertEquals("SUCCEEDED", result.status());
    assertEquals("result-1", result.resultId());
  }

  @Test
  void acceptsProductionReadOnlyQuery() {
    var worker = new RestrictedSqlQueryExecutionWorker(new CalciteSqlReadOnlyGuard(), request -> "result-1", CLOCK);

    var result = worker.execute(request(
        "select * from ORDERS.ORDERS",
        "production",
        SqlQueryAction.RUN_READ_ONLY,
        30));

    assertEquals("SUCCEEDED", result.status());
    assertEquals("result-1", result.resultId());
  }

  @Test
  void acceptsControlledDmlInSitAndReturnsAffectedRows() {
    var worker = new RestrictedSqlQueryExecutionWorker(
        new CalciteSqlReadOnlyGuard(),
        new CalciteSqlDmlGuard(),
        executor(2),
        request -> reactor.core.publisher.Mono.error(new UnsupportedOperationException()),
        enabledDmlPolicy(),
        allowingWriteCapabilityValidator(),
        CLOCK);

    var result = worker.executeControlledDml(controlledDmlRequest(
        "update ORDERS.ORDERS set status = 'READY' where order_id = 42"));

    assertEquals("SUCCEEDED", result.status());
    assertEquals(2, result.affectedRows());
  }

  @Test
  void rejectsControlledDmlWhenSixArgumentConstructorHasNoWriteCapabilityValidator() {
    AtomicBoolean databaseAccessed = new AtomicBoolean();
    var worker = new RestrictedSqlQueryExecutionWorker(
        new CalciteSqlReadOnlyGuard(),
        new CalciteSqlDmlGuard(),
        executor(databaseAccessed),
        request -> reactor.core.publisher.Mono.error(new UnsupportedOperationException()),
        enabledDmlPolicy(),
        CLOCK);

    var result = worker.executeControlledDml(controlledDmlRequest(
        "update ORDERS.ORDERS set status = 'READY' where order_id = 42"));

    assertEquals("REJECTED", result.status());
    assertEquals("SQL_DML_WORKER_DISABLED", result.errorCode());
    assertFalse(databaseAccessed.get());
  }

  @Test
  void rejectsCommitWhenWriteCapabilityIsDisabled() {
    AtomicBoolean databaseAccessed = new AtomicBoolean();
    var worker = new RestrictedSqlQueryExecutionWorker(
        new CalciteSqlReadOnlyGuard(),
        new CalciteSqlDmlGuard(),
        executor(databaseAccessed),
        request -> reactor.core.publisher.Mono.error(new UnsupportedOperationException()),
        new WorkerSqlDmlExecutionPolicy(List.of(descriptor(false, null))),
        CLOCK);

    var result = worker.executeControlledDml(controlledDmlRequest(
        "update ORDERS.ORDERS set status = 'READY' where order_id = 42"));

    assertEquals("REJECTED", result.status());
    assertEquals("SQL_DML_WORKER_DISABLED", result.errorCode());
    assertFalse(databaseAccessed.get());
  }

  @Test
  void rejectsCommitWhenWriteCredentialAliasIsMissing() {
    AtomicBoolean databaseAccessed = new AtomicBoolean();
    var worker = new RestrictedSqlQueryExecutionWorker(
        new CalciteSqlReadOnlyGuard(),
        new CalciteSqlDmlGuard(),
        executor(databaseAccessed),
        request -> reactor.core.publisher.Mono.error(new UnsupportedOperationException()),
        new WorkerSqlDmlExecutionPolicy(List.of(descriptor(true, null))),
        CLOCK);

    var result = worker.executeControlledDml(controlledDmlRequest(
        "update ORDERS.ORDERS set status = 'READY' where order_id = 42"));

    assertEquals("REJECTED", result.status());
    assertEquals("SQL_DML_WORKER_DISABLED", result.errorCode());
    assertFalse(databaseAccessed.get());
  }

  @Test
  void rejectsDisabledDmlPreflightBeforeInsertPreview() {
    AtomicBoolean previewAccessed = new AtomicBoolean();
    var worker = new RestrictedSqlQueryExecutionWorker(
        new CalciteSqlReadOnlyGuard(),
        new CalciteSqlDmlGuard(),
        executor(1),
        request -> {
          previewAccessed.set(true);
          return reactor.core.publisher.Mono.empty();
        },
        new WorkerSqlDmlExecutionPolicy(List.of(descriptor(false, null))),
        CLOCK);

    WorkerSqlEgressException exception = assertThrows(
        WorkerSqlEgressException.class,
        () -> worker.preflightDml(preflightDmlRequest(
            "insert into ORDERS.ORDERS (ORDER_ID, STATUS) values (42, 'READY')")).block());

    assertEquals("SQL_DML_WORKER_DISABLED", exception.errorCode());
    assertFalse(previewAccessed.get());
  }

  @Test
  void rejectsMissingWriteCredentialDmlPreflightBeforeInsertPreview() {
    AtomicBoolean previewAccessed = new AtomicBoolean();
    var worker = new RestrictedSqlQueryExecutionWorker(
        new CalciteSqlReadOnlyGuard(),
        new CalciteSqlDmlGuard(),
        executor(1),
        request -> {
          previewAccessed.set(true);
          return reactor.core.publisher.Mono.empty();
        },
        new WorkerSqlDmlExecutionPolicy(List.of(descriptor(true, null))),
        CLOCK);

    WorkerSqlEgressException exception = assertThrows(
        WorkerSqlEgressException.class,
        () -> worker.preflightDml(preflightDmlRequest(
            "insert into ORDERS.ORDERS (ORDER_ID, STATUS) values (42, 'READY')")).block());

    assertEquals("SQL_DML_WORKER_DISABLED", exception.errorCode());
    assertFalse(previewAccessed.get());
  }

  @Test
  void rejectsEgressDeniedDmlPreflightBeforeInsertPreview() {
    AtomicBoolean previewAccessed = new AtomicBoolean();
    WorkerSqlConnectionDescriptor descriptor = descriptor(true, "as400-sit-writer");
    SqlDmlWriteCapabilityValidator writeCapabilityValidator = new ConfiguredSqlDataSourceRegistry(
        new WorkerSqlEgressPolicy(List.of(descriptor), List.of()),
        alias -> SqlTestSecretMaterial.password(),
        new Jt400DataSourceFactory(),
        new H2SqlDataSourceFactory());
    var worker = new RestrictedSqlQueryExecutionWorker(
        new CalciteSqlReadOnlyGuard(),
        new CalciteSqlDmlGuard(),
        executor(1),
        request -> {
          previewAccessed.set(true);
          return reactor.core.publisher.Mono.empty();
        },
        enabledDmlPolicy(),
        writeCapabilityValidator,
        CLOCK);

    WorkerSqlEgressException exception = assertThrows(
        WorkerSqlEgressException.class,
        () -> worker.preflightDml(preflightDmlRequest(
            "insert into ORDERS.ORDERS (ORDER_ID, STATUS) values (42, 'READY')")).block());

    assertEquals("SQL_EGRESS_NOT_ALLOWED", exception.errorCode());
    assertFalse(previewAccessed.get());
  }

  @Test
  void rejectsExpiredControlledDmlBeforeDatabaseAccess() {
    AtomicBoolean databaseAccessed = new AtomicBoolean();
    var worker = new RestrictedSqlQueryExecutionWorker(
        new CalciteSqlReadOnlyGuard(),
        new CalciteSqlDmlGuard(),
        executor(databaseAccessed),
        request -> reactor.core.publisher.Mono.error(new UnsupportedOperationException()),
        enabledDmlPolicy(),
        CLOCK);

    var result = worker.executeControlledDml(controlledDmlRequest(
        "delete from ORDERS.ORDERS where order_id = 42", -1));

    assertEquals("REJECTED", result.status());
    assertEquals("REQUEST_EXPIRED", result.errorCode());
    assertFalse(databaseAccessed.get());
  }

  @Test
  void rejectsInsertSelectOutsideControlledDmlSubsetBeforeDatabaseAccess() {
    AtomicBoolean databaseAccessed = new AtomicBoolean();
    var worker = new RestrictedSqlQueryExecutionWorker(
        new CalciteSqlReadOnlyGuard(),
        new CalciteSqlDmlGuard(),
        executor(databaseAccessed),
        request -> reactor.core.publisher.Mono.error(new UnsupportedOperationException()),
        enabledDmlPolicy(),
        CLOCK);

    var result = worker.executeControlledDml(controlledDmlRequest(
        "insert into ORDERS.ORDERS (order_id, status) select order_id, status from ORDERS.ARCHIVE"));

    assertEquals("REJECTED", result.status());
    assertEquals("SQL_NOT_CONTROLLED_DML", result.errorCode());
    assertFalse(databaseAccessed.get());
  }

  @Test
  void rejectsCommitDmlAtLegacyReadEndpointBeforeDatabaseAccess() {
    AtomicBoolean databaseAccessed = new AtomicBoolean();
    var worker = new RestrictedSqlQueryExecutionWorker(
        new CalciteSqlReadOnlyGuard(),
        new CalciteSqlDmlGuard(),
        executor(databaseAccessed),
        CLOCK);

    var result = worker.execute(request(
        "update ORDERS.ORDERS set status = 'READY' where order_id = 42",
        "sit",
        SqlQueryAction.COMMIT_DML,
        30));

    assertEquals("REJECTED", result.status());
    assertEquals("SQL_DML_LEGACY_ENVELOPE_REJECTED", result.errorCode());
    assertFalse(databaseAccessed.get());
  }

  @Test
  void rollsBackControlledDmlWhenJdbcExecutionFails() throws Exception {
    JdbcDataSource database = new JdbcDataSource();
    database.setURL("jdbc:h2:mem:dml-rollback;DB_CLOSE_DELAY=-1");
    try (var connection = database.getConnection(); var statement = connection.createStatement()) {
      statement.execute("create schema ORDERS");
      statement.execute("create table ORDERS.ORDERS (ORDER_ID integer primary key, STATUS varchar(20) not null)");
      statement.execute("insert into ORDERS.ORDERS values (1, 'PENDING'), (2, 'READY')");
    }
    var connection = spy(database.getConnection());
    DataSource dataSource = org.mockito.Mockito.mock(DataSource.class);
    when(dataSource.getConnection()).thenReturn(connection);
    Clock clock = CLOCK;
    var jdbcExecutor = new JdbcSqlQueryExecutor(
        request -> dataSource,
        new InMemorySqlResultStore(clock),
        new com.fasterxml.jackson.databind.ObjectMapper(),
        clock);
    var worker = new RestrictedSqlQueryExecutionWorker(
        new CalciteSqlReadOnlyGuard(),
        new CalciteSqlDmlGuard(),
        jdbcExecutor,
        request -> reactor.core.publisher.Mono.error(new UnsupportedOperationException()),
        enabledDmlPolicy(),
        allowingWriteCapabilityValidator(),
        clock);

    var result = worker.executeControlledDml(controlledDmlRequest(
        "update ORDERS set ORDER_ID = 2 where ORDER_ID = 1 or ORDER_ID = 2"));

    assertEquals("FAILED", result.status());
    assertEquals("SQL_EXECUTION_FAILED", result.errorCode());
    verify(connection).rollback();
    try (var verification = database.getConnection();
        var statement = verification.createStatement();
        var rows = statement.executeQuery("select count(*) from ORDERS.ORDERS where ORDER_ID = 1")) {
      assertTrue(rows.next());
      assertEquals(1, rows.getInt(1));
    }
  }

  @Test
  void routesControlledDmlThroughWriteCredentialAlias() {
    AtomicReference<String> resolvedAlias = new AtomicReference<>();
    AtomicReference<String> resolvedUsername = new AtomicReference<>();
    DataSource expected = org.mockito.Mockito.mock(DataSource.class);
    Jt400DataSourceFactory dataSourceFactory = new Jt400DataSourceFactory() {
      @Override
      public DataSource create(String systemName, String username, char[] password) {
        resolvedUsername.set(username);
        return expected;
      }
    };
    WorkerSqlConnectionDescriptor descriptor = descriptor(true, "as400-sit-writer");
    var registry = new ConfiguredSqlDataSourceRegistry(
        new WorkerSqlEgressPolicy(
            List.of(descriptor),
            List.of(new WorkerSqlEgressTarget(descriptor.host(), descriptor.port()))),
        alias -> {
          resolvedAlias.set(alias);
          return SqlTestSecretMaterial.password();
        },
        dataSourceFactory,
        new H2SqlDataSourceFactory());
    SqlControlledDmlExecutionRequest request = controlledDmlRequest(
        "update ORDERS.ORDERS set status = 'READY' where order_id = 42");

    DataSource resolved = registry.resolve(legacyExecutionRequest(request));

    assertSame(expected, resolved);
    assertEquals("as400-sit-writer", resolvedAlias.get());
    assertEquals("as400-sit-writer", resolvedUsername.get());
  }

  @Test
  void rejectsProductionControlledDmlBeforeWorkerSubmission() {
    assertThrows(IllegalArgumentException.class, () -> request(
        "delete from ORDERS.ORDERS where order_id = 42",
        "production",
        SqlQueryAction.COMMIT_DML,
        30));
  }

  @Test
  void mapsEgressPolicyRejectionToRejectedResult() {
    var worker = new RestrictedSqlQueryExecutionWorker(
        new CalciteSqlReadOnlyGuard(),
        request -> {
          throw new WorkerSqlEgressException("SQL_EGRESS_NOT_ALLOWED", "SQL egress target is not allowed");
        },
        CLOCK);

    var result = worker.execute(request("select * from ORDERS.ORDERS", "development", 30));

    assertEquals("REJECTED", result.status());
    assertEquals("SQL_EGRESS_NOT_ALLOWED", result.errorCode());
  }

  @Test
  void includesSafeRootCauseForExecutionFailure() {
    var worker = new RestrictedSqlQueryExecutionWorker(
        new CalciteSqlReadOnlyGuard(),
        request -> {
          throw new IllegalStateException(
              "read-only JDBC query failed",
              new SQLSyntaxErrorException(
                  "Table \"ELADREFP\" not found; SQL statement: select * from eladrefp [42102-224]",
                  "42S02",
                  42102));
        },
        CLOCK);

    var result = worker.execute(request("select * from eladrefp", "development", 30));

    assertEquals("FAILED", result.status());
    assertEquals("SQL_EXECUTION_FAILED", result.errorCode());
    assertEquals(
        String.join(
            System.lineSeparator(),
            "SQL query execution failed",
            "failureType=SQLSyntaxErrorException",
            "sqlState=42S02",
            "vendorCode=42102",
            "message=Table \"ELADREFP\" not found"),
        result.errorMessage());
  }

  private SqlQueryExecutionRequest request(String sql, String environment, int expiresInSeconds) {
    return request(sql, environment, SqlQueryAction.RUN_READ_ONLY, expiresInSeconds);
  }

  private SqlQueryExecutionRequest request(
      String sql,
      String environment,
      SqlQueryAction action,
      int expiresInSeconds) {
    var query = new SqlQueryRequest(
        "1.0",
        "as400-development",
        environment,
        "ORDERS",
        action,
        sql,
        List.of(),
        new SqlQueryLimits(500, 5_000_000, 30),
        "key");
    return new SqlQueryExecutionRequest(
        "1.0",
        "execution-1",
        "workflow-1",
        query,
        "sha256:test",
        new OperatorContext("operator-1", List.of("ROLE_ops-reader")),
        new PolicyDecisionReference("decision-1", "policy-v1", "ALLOW"),
        new TraceContext("trace-1", "request-1"),
        OffsetDateTime.now(CLOCK).plusSeconds(expiresInSeconds));
  }

  private SqlQueryExecutor executor(int affectedRows) {
    return new SqlQueryExecutor() {
      @Override
      public String execute(SqlQueryExecutionRequest request) {
        return "result-1";
      }

      @Override
      public int executeDml(SqlQueryExecutionRequest request) {
        return affectedRows;
      }
    };
  }

  private SqlQueryExecutor executor(AtomicBoolean databaseAccessed) {
    return new SqlQueryExecutor() {
      @Override
      public String execute(SqlQueryExecutionRequest request) {
        databaseAccessed.set(true);
        return "result-1";
      }

      @Override
      public int executeDml(SqlQueryExecutionRequest request) {
        databaseAccessed.set(true);
        return 1;
      }
    };
  }

  private WorkerSqlDmlExecutionPolicy enabledDmlPolicy() {
    return new WorkerSqlDmlExecutionPolicy(List.of(descriptor(true, "as400-sit-writer")));
  }

  private SqlDmlWriteCapabilityValidator allowingWriteCapabilityValidator() {
    return new SqlDmlWriteCapabilityValidator() {
      @Override
      public void assertPreflightAllowed(SqlDmlPreflightExecutionRequest request) {
      }

      @Override
      public void assertCommitAllowed(SqlControlledDmlExecutionRequest request) {
      }
    };
  }

  private WorkerSqlConnectionDescriptor descriptor(boolean dmlEnabled, String dmlCredentialAlias) {
    return new WorkerSqlConnectionDescriptor(
        "as400-development",
        "sit",
        "DB2_FOR_I",
        "as400-sit.internal",
        446,
        "as400-sit-readonly",
        "as400-sit-readonly",
        true,
        dmlEnabled,
        dmlCredentialAlias,
        dmlCredentialAlias);
  }

  private SqlControlledDmlExecutionRequest controlledDmlRequest(String sql) {
    return controlledDmlRequest(sql, 30);
  }

  private SqlControlledDmlExecutionRequest controlledDmlRequest(String sql, int expiresInSeconds) {
    SqlDmlCommitRequest commitRequest = new SqlDmlCommitRequest(
        "1.0",
        query(sql, "sit", SqlQueryAction.COMMIT_DML),
        new SqlDmlConfirmation(
            "1.0",
            "sha256:sql",
            List.of("CONTROLLED_DML"),
            SqlDmlConfirmation.RISK_CONFIRMATION_CODE));
    return new SqlControlledDmlExecutionRequest(
        "1.0",
        "execution-1",
        "workflow-1",
        commitRequest,
        new SqlDmlExecutionBinding(
            "sha256:binding",
            "sha256:parameters",
            "sha256:preflight",
            "sha256:confirmation"),
        new OperatorContext("operator-1", List.of("ROLE_sql-operator")),
        new PolicyDecisionReference("decision-1", "policy-v1", "ALLOW"),
        new TraceContext("trace-1", "request-1"),
        OffsetDateTime.now(CLOCK).plusSeconds(expiresInSeconds));
  }

  private SqlDmlPreflightExecutionRequest preflightDmlRequest(String sql) {
    return new SqlDmlPreflightExecutionRequest(
        "1.0",
        "preflight-execution-1",
        "workflow-1",
        query(sql, "sit", SqlQueryAction.PREFLIGHT_DML),
        "sha256:validation",
        new SqlDmlPreviewSelection("1.0", List.of(), List.of()),
        new OperatorContext("operator-1", List.of("ROLE_sql-operator")),
        new PolicyDecisionReference("decision-1", "policy-v1", "ALLOW"),
        new TraceContext("trace-1", "request-1"),
        OffsetDateTime.now(CLOCK).plusSeconds(30));
  }

  private SqlQueryExecutionRequest legacyExecutionRequest(SqlControlledDmlExecutionRequest request) {
    return new SqlQueryExecutionRequest(
        "1.0",
        request.executionRequestId(),
        request.workflowId(),
        request.commitRequest().query(),
        request.binding().bindingHash(),
        request.operator(),
        request.policyDecision(),
        request.trace(),
        request.expiresAt());
  }

  private SqlQueryRequest query(String sql, String environment, SqlQueryAction action) {
    return new SqlQueryRequest(
        "1.0",
        "as400-development",
        environment,
        "ORDERS",
        action,
        sql,
        List.of(),
        new SqlQueryLimits(500, 5_000_000, 30),
        "key");
  }
}
